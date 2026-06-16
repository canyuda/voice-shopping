package com.voiceshopping.web.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.ai.agent.AgentFactory;
import com.voiceshopping.ai.asr.ASRService;
import com.voiceshopping.ai.asr.ASRServiceFactory;
import com.voiceshopping.ai.tts.TTSService;
import com.voiceshopping.business.memory.LongTermMemoryWriter;
import com.voiceshopping.business.orchestrator.OrchestratorService;
import com.voiceshopping.business.session.SessionService;
import com.voiceshopping.common.dto.agent.StreamChunk;
import com.voiceshopping.common.dto.AgentDisplay;
import com.voiceshopping.common.dto.AgentStatus;
import com.voiceshopping.common.dto.AsrFinalResult;
import com.voiceshopping.common.dto.AsrPartialResult;
import com.voiceshopping.common.dto.StreamDone;
import com.voiceshopping.common.dto.StreamProducts;
import com.voiceshopping.common.dto.StreamText;
import com.voiceshopping.common.dto.VoiceError;
import com.voiceshopping.common.dto.agent.EmotionResult;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full-duplex WebSocket handler for the voice channel.
 * Protocol: BinaryMessage = PCM audio, TextMessage = JSON signal.
 * Pipeline: ASR → Orchestrator → TTS
 * <p>
 * The handshake interceptor ({@link AuthHandshakeInterceptor}) lifts
 * {@code sessionId} from the connect URL and {@code userId} from the
 * Sa-Token sub-protocol token into session attributes; this handler reads
 * them on connect and delegates each ASR sentence-end to
 * {@link OrchestratorService}, fanning out the result as: JSON
 * {@code agent_display} (display blocks) → streaming TTS frames of
 * {@code speechText} → JSON {@code agent_status:done}.
 * <p>
 * <b>Scope contract:</b> WS handshake does NOT write the
 * {@code SessionScopeCache}. Callers SHOULD invoke
 * {@code POST /api/v1/session/start} before opening the WS so the scope
 * cache is primed; if they don't, downstream recommendation requests will
 * follow the documented "scope cache miss → platform-wide" fallback (a
 * WARN log is emitted but no exception is thrown).
 * <p>
 * If the orchestrator throws, falls back to a fixed network-hiccup TTS prompt
 * so the channel never goes silent.
 */
@Slf4j
@Component
public class VoiceWebSocketHandler extends AbstractWebSocketHandler {

    private static final String FALLBACK_REPLY = "这边网络有点卡，你再说一次？";
    private static final String DEFAULT_CHANNEL = "HOME_ENTRY";

    private final ASRServiceFactory asrServiceFactory;
    private final TTSService ttsService;
    private final OrchestratorService orchestratorService;
    private final SessionService sessionService;
    private final AgentFactory agentFactory;
    private final LongTermMemoryWriter longTermMemoryWriter;
    private final ObjectMapper objectMapper;

    // Track ASRService per session for lifecycle management
    private final Map<String, ASRService> sessionAsrMap = new ConcurrentHashMap<>();

    public VoiceWebSocketHandler(ASRServiceFactory asrServiceFactory,
                                 TTSService ttsService,
                                 OrchestratorService orchestratorService,
                                 SessionService sessionService,
                                 AgentFactory agentFactory,
                                 LongTermMemoryWriter longTermMemoryWriter,
                                 ObjectMapper objectMapper) {
        this.asrServiceFactory = asrServiceFactory;
        this.ttsService = ttsService;
        this.orchestratorService = orchestratorService;
        this.sessionService = sessionService;
        this.agentFactory = agentFactory;
        this.longTermMemoryWriter = longTermMemoryWriter;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String wsId = session.getId();
        String bizSessionId = (String) session.getAttributes().get(AuthHandshakeInterceptor.ATTR_SESSION_ID);
        Long userId = (Long) session.getAttributes().get(AuthHandshakeInterceptor.ATTR_USER_ID);
        log.info("WebSocket connection established: wsId={}, bizSessionId={}, userId={}",
                wsId, bizSessionId, userId);

        // Pre-create the business session so the orchestrator's find-only contract is satisfied.
        if (bizSessionId != null && userId != null) {
            try {
                sessionService.getOrCreate(bizSessionId, null, userId, DEFAULT_CHANNEL);
            } catch (Exception e) {
                log.warn("Failed to pre-create session bizSessionId={}: {}", bizSessionId, e.getMessage());
            }
        }

        // Create a dedicated ASRService instance per WS connection
        ASRService sessionAsr = asrServiceFactory.create();
        sessionAsrMap.put(wsId, sessionAsr);

        // Start ASR and subscribe to results
        sessionAsr.start()
                .doOnSubscribe(s -> log.info("[{}] ASR subscribed, connecting to DashScope...", wsId))
                .subscribe(
                        result -> handleAsrResult(session, result),
                        error -> {
                            log.error("[{}] ASR stream error", wsId, error);
                            sendTextSafely(session, new VoiceError("ASR_ERROR", error.getMessage()));
                        },
                        () -> log.info("[{}] ASR stream onComplete", wsId)
                );
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        ASRService sessionAsr = sessionAsrMap.get(session.getId());
        if (sessionAsr != null) {
            byte[] payload = new byte[message.getPayloadLength()];
            message.getPayload().get(payload);
            // Detect silence frames for debugging ASR timeout
            if (isAllZero(payload)) {
                log.info("Received silence frame ({} bytes) from session {}", payload.length, session.getId());
            }
            sessionAsr.sendFrame(payload);
        }
    }

    private boolean isAllZero(byte[] data) {
        for (byte b : data) {
            if (b != 0) return false;
        }
        return true;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String wsId = session.getId();
        log.info("WebSocket connection closed: wsId={}, status={}", wsId, status);

        ASRService sessionAsr = sessionAsrMap.remove(wsId);
        if (sessionAsr != null) {
            sessionAsr.stop();
        }

        String bizSessionId = (String) session.getAttributes().get(AuthHandshakeInterceptor.ATTR_SESSION_ID);
        Long userId = (Long) session.getAttributes().get(AuthHandshakeInterceptor.ATTR_USER_ID);

        // Long-term memory writeback. ShortTermMemory acts as the idempotency gate
        // inside flushOnSessionEnd — multiple triggers (this WS close + a later
        // SessionExpireListener firing for the same TTL key) all converge into a
        // single PG write because a successful flush clears ShortTermMemory.
        if (bizSessionId != null && userId != null) {
            try {
                longTermMemoryWriter.flushOnSessionEnd(bizSessionId, userId);
            } catch (Exception e) {
                // flushOnSessionEnd is @Async; only the fail-fast guard exceptions
                // (null/blank arg) reach here. Don't let close-handling fail.
                log.warn("flushOnSessionEnd dispatch failed on WS close: bizSessionId={}, userId={}",
                        bizSessionId, userId, e);
            }
        }

        // Release the cached AgentSet so InMemoryMemory references are GC-eligible.
        if (bizSessionId != null) {
            agentFactory.remove(bizSessionId);
            log.debug("AgentFactory cache evicted on close: bizSessionId={}", bizSessionId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error for session {}", session.getId(), exception);
    }

    /**
     * Process ASR recognition results and trigger Orchestrator → TTS on sentence end.
     */
    private void handleAsrResult(WebSocketSession session, RecognitionResult result) {
        String text = result.getSentence().getText();
        String wsId = session.getId();

        if (!result.isSentenceEnd()) {
            if (text != null && !text.isEmpty()) {
                sendTextSafely(session, new AsrPartialResult(text));
            }
            return;
        }

        // Sentence end
        log.info("[{}] ASR sentence-end: text={}", wsId, text);
        sendTextSafely(session, new AsrFinalResult(text));

        String bizSessionId = (String) session.getAttributes().get(AuthHandshakeInterceptor.ATTR_SESSION_ID);
        Long userId = (Long) session.getAttributes().get(AuthHandshakeInterceptor.ATTR_USER_ID);

        // 订阅流式输出
        try {
            orchestratorService.streamHandle(bizSessionId, userId, text)
                    .subscribe(
                            chunk -> handleStreamChunk(session, chunk),
                            error -> {
                                log.error("[{}] streamHandle error for bizSessionId={}, userId={} — falling back",
                                        wsId, bizSessionId, userId, error);
                                sendTextSafely(session, new VoiceError("AGENT_ERROR", error.getMessage()));
                                speak(session, FALLBACK_REPLY);
                            },
                            () -> {
                                log.info("[{}] streamHandle complete, sending StreamDone", wsId);
                                sendTextSafely(session, new StreamDone());
                            }
                    );
        } catch (Exception e) {
            log.error("[{}] streamHandle failed to start for bizSessionId={}, userId={} — falling back",
                    wsId, bizSessionId, userId, e);
            sendTextSafely(session, new VoiceError("AGENT_ERROR", e.getMessage()));
            speak(session, FALLBACK_REPLY);
        }
    }

    /**
     * 按 StreamChunk 类型分发到对应的 WebSocket 信令。
     */
    private void handleStreamChunk(WebSocketSession session, StreamChunk chunk) {
        switch (chunk.type()) {
            case PRODUCTS -> sendTextSafely(session, new StreamProducts(
                    chunk.products() instanceof List<?> list ? list : List.of()));
            case TEXT -> sendTextSafely(session, new StreamText(chunk.text()));
            case AUDIO -> {
                if (chunk.audio() != null) {
                    byte[] pcm = new byte[chunk.audio().remaining()];
                    chunk.audio().get(pcm);
                    sendBinarySafely(session, pcm);
                }
            }
        }
    }

    /**
     * Stream a TTS reply. Emits AgentStatus("done") on completion or VoiceError on failure.
     */
    private void speak(WebSocketSession session, String agentText) {
        String wsId = session.getId();
        if (agentText == null || agentText.isBlank()) {
            log.warn("[{}] Empty TTS text, skipping synthesis", wsId);
            sendTextSafely(session, new AgentStatus("done"));
            return;
        }
        log.info("[{}] TTS starting for text length={}", wsId, agentText.length());
        ttsService.synthesize(agentText)
                .doOnSubscribe(s -> log.info("[{}] TTS subscribed, connecting to DashScope...", wsId))
                .doOnNext(frame -> log.debug("[{}] TTS frame: {} bytes", wsId, frame.length))
                .doOnComplete(() -> log.info("[{}] TTS flow complete, sending done", wsId))
                .subscribe(
                        pcmFrame -> sendBinarySafely(session, pcmFrame),
                        error -> {
                            log.error("[{}] TTS synthesis error", wsId, error);
                            sendTextSafely(session, new VoiceError("TTS_ERROR", error.getMessage()));
                        },
                        () -> {
                            log.info("[{}] Sending AgentStatus done", wsId);
                            sendTextSafely(session, new AgentStatus("done"));
                        }
                );
    }

    /**
     * Send a JSON text signal safely — skip if session is closed.
     */
    private void sendTextSafely(WebSocketSession session, Object signal) {
        if (!session.isOpen()) {
            log.warn("Session {} already closed, skipping text signal", session.getId());
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(signal);
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            log.warn("Failed to send text signal to session {}: {}", session.getId(), e.getMessage());
        }
    }

    /**
     * Send a binary PCM frame safely — skip if session is closed.
     */
    private void sendBinarySafely(WebSocketSession session, byte[] pcmFrame) {
        if (!session.isOpen()) {
            log.warn("Session {} already closed, skipping binary frame", session.getId());
            return;
        }
        try {
            session.sendMessage(new BinaryMessage(pcmFrame));
        } catch (IOException e) {
            log.warn("Failed to send binary frame to session {}: {}", session.getId(), e.getMessage());
        }
    }
}
