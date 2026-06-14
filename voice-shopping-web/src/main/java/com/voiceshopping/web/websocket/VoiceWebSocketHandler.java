package com.voiceshopping.web.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.ai.asr.ASRService;
import com.voiceshopping.ai.asr.ASRServiceFactory;
import com.voiceshopping.ai.tts.TTSService;
import com.voiceshopping.business.orchestrator.OrchestratorService;
import com.voiceshopping.business.session.SessionService;
import com.voiceshopping.common.dto.AgentDisplay;
import com.voiceshopping.common.dto.AgentStatus;
import com.voiceshopping.common.dto.AsrFinalResult;
import com.voiceshopping.common.dto.AsrPartialResult;
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
 * The handshake interceptor lifts {@code sessionId} and {@code userId} from the
 * connect URL into session attributes; this handler reads them on connect and
 * delegates each ASR sentence-end to {@link OrchestratorService}, fanning out
 * the result as: JSON {@code agent_display} (display blocks) → streaming TTS
 * frames of {@code speechText} → JSON {@code agent_status:done}.
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
    private final ObjectMapper objectMapper;

    // Track ASRService per session for lifecycle management
    private final Map<String, ASRService> sessionAsrMap = new ConcurrentHashMap<>();

    public VoiceWebSocketHandler(ASRServiceFactory asrServiceFactory,
                                 TTSService ttsService,
                                 OrchestratorService orchestratorService,
                                 SessionService sessionService,
                                 ObjectMapper objectMapper) {
        this.asrServiceFactory = asrServiceFactory;
        this.ttsService = ttsService;
        this.orchestratorService = orchestratorService;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String wsId = session.getId();
        String bizSessionId = (String) session.getAttributes().get(VoiceHandshakeInterceptor.ATTR_SESSION_ID);
        Long userId = (Long) session.getAttributes().get(VoiceHandshakeInterceptor.ATTR_USER_ID);
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
        String sessionId = session.getId();
        log.info("WebSocket connection closed: {}, status: {}", sessionId, status);

        ASRService sessionAsr = sessionAsrMap.remove(sessionId);
        if (sessionAsr != null) {
            sessionAsr.stop();
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

        String bizSessionId = (String) session.getAttributes().get(VoiceHandshakeInterceptor.ATTR_SESSION_ID);
        Long userId = (Long) session.getAttributes().get(VoiceHandshakeInterceptor.ATTR_USER_ID);

        EmotionResult reply;
        try {
            reply = orchestratorService.handle(bizSessionId, userId, text);
        } catch (Exception e) {
            log.error("[{}] Orchestrator handle failed for bizSessionId={}, userId={} — falling back",
                    wsId, bizSessionId, userId, e);
            sendTextSafely(session, new VoiceError("AGENT_ERROR", e.getMessage()));
            // Fallback: still drive TTS with a soothing prompt so the channel is not silent.
            speak(session, FALLBACK_REPLY);
            return;
        }

        // Publish display blocks BEFORE audio so the UI can render cards while
        // the user hears the spoken reply.
        sendTextSafely(session, new AgentDisplay(
                reply.displayBlocks() == null ? List.of() : reply.displayBlocks()));

        speak(session, reply.speechText());
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
