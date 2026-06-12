package com.voiceshopping.web.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.ai.asr.ASRService;
import com.voiceshopping.ai.asr.ASRServiceFactory;
import com.voiceshopping.ai.tts.TTSService;
import com.voiceshopping.common.dto.AgentStatus;
import com.voiceshopping.common.dto.AsrFinalResult;
import com.voiceshopping.common.dto.AsrPartialResult;
import com.voiceshopping.common.dto.VoiceError;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full-duplex WebSocket handler for the voice channel.
 * Protocol: BinaryMessage = PCM audio, TextMessage = JSON signal.
 * Pipeline: ASR → (TODO: Agent) → TTS
 */
@Slf4j
@Component
public class VoiceWebSocketHandler extends AbstractWebSocketHandler {

    private final ASRServiceFactory asrServiceFactory;
    private final TTSService ttsService;
    private final ObjectMapper objectMapper;

    // Track ASRService per session for lifecycle management
    private final Map<String, ASRService> sessionAsrMap = new ConcurrentHashMap<>();

    public VoiceWebSocketHandler(ASRServiceFactory asrServiceFactory,
                                 TTSService ttsService,
                                 ObjectMapper objectMapper) {
        this.asrServiceFactory = asrServiceFactory;
        this.ttsService = ttsService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        log.info("WebSocket connection established: {}", sessionId);

        // Create a dedicated ASRService instance per session
        ASRService sessionAsr = asrServiceFactory.create();
        sessionAsrMap.put(sessionId, sessionAsr);

        // Start ASR and subscribe to results
        sessionAsr.start()
                .doOnSubscribe(s -> log.info("[{}] ASR subscribed, connecting to DashScope...", sessionId))
                .subscribe(
                        result -> handleAsrResult(session, result),
                        error -> {
                            log.error("[{}] ASR stream error", sessionId, error);
                            sendTextSafely(session, new VoiceError("ASR_ERROR", error.getMessage()));
                        },
                        () -> log.info("[{}] ASR stream onComplete", sessionId)
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
     * Process ASR recognition results and trigger TTS on sentence end.
     */
    private void handleAsrResult(WebSocketSession session, RecognitionResult result) {
        String text = result.getSentence().getText();
        String sid = session.getId();

        if (!result.isSentenceEnd()) {
            if (text != null && !text.isEmpty()) {
                sendTextSafely(session, new AsrPartialResult(text));
            }
            return;
        }

        // Sentence end
        log.info("[{}] ASR sentence-end: text={}", sid, text);
        sendTextSafely(session, new AsrFinalResult(text));

        // TODO: Agent integration — replace echo with agent response
        String agentText = "我是Agent, 我将重复你说的话:" + text;

        log.info("[{}] TTS starting for text length={}", sid, agentText.length());
        ttsService.synthesize(agentText)
                .doOnSubscribe(s -> log.info("[{}] TTS subscribed, connecting to DashScope...", sid))
                .doOnNext(frame -> log.debug("[{}] TTS frame: {} bytes", sid, frame.length))
                .doOnComplete(() -> log.info("[{}] TTS flow complete, sending done", sid))
                .subscribe(
                        pcmFrame -> sendBinarySafely(session, pcmFrame),
                        error -> {
                            log.error("[{}] TTS synthesis error", sid, error);
                            sendTextSafely(session, new VoiceError("TTS_ERROR", error.getMessage()));
                        },
                        () -> {
                            log.info("[{}] Sending AgentStatus done", sid);
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
