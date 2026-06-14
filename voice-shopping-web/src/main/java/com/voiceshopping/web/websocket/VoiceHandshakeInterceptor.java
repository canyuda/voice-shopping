package com.voiceshopping.web.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Hoists {@code sessionId} and {@code userId} from the connect URL into the
 * WebSocket session attributes so the handler can route an utterance through
 * {@link com.voiceshopping.business.orchestrator.OrchestratorService} without
 * ad-hoc URL parsing.
 * <p>
 * Connect URL pattern: {@code ws://host/ws/voice?userId=1&sessionId=sess-xyz}.
 * Missing parameters are tolerated — the handler is responsible for the
 * downstream contract (currently both are required by the orchestrator).
 */
@Slf4j
public class VoiceHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_SESSION_ID = "sessionId";
    public static final String ATTR_USER_ID = "userId";

    @Override
    public boolean beforeHandshake(@NonNull ServerHttpRequest request,
                                   @NonNull ServerHttpResponse response,
                                   @NonNull WebSocketHandler wsHandler,
                                   @NonNull Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            log.warn("Non-servlet handshake request, skipping query parsing");
            return true;
        }

        String sessionId = servletRequest.getServletRequest().getParameter("sessionId");
        String userIdRaw = servletRequest.getServletRequest().getParameter("userId");

        if (sessionId != null && !sessionId.isBlank()) {
            attributes.put(ATTR_SESSION_ID, sessionId);
        }
        if (userIdRaw != null && !userIdRaw.isBlank()) {
            try {
                attributes.put(ATTR_USER_ID, Long.parseLong(userIdRaw));
            } catch (NumberFormatException e) {
                log.warn("Invalid userId in handshake query: {}", userIdRaw);
            }
        }

        log.info("Handshake query → sessionId={}, userId={}",
                attributes.get(ATTR_SESSION_ID), attributes.get(ATTR_USER_ID));
        return true;
    }

    @Override
    public void afterHandshake(@NonNull ServerHttpRequest request,
                               @NonNull ServerHttpResponse response,
                               @NonNull WebSocketHandler wsHandler,
                               Exception exception) {
        // no-op
    }
}
