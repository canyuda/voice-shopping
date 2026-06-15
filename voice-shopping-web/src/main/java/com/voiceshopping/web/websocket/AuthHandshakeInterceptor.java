package com.voiceshopping.web.websocket;

import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.Map;

/**
 * Sub-protocol-based handshake authentication.
 * <p>
 * Client contract: {@code new WebSocket(url, ['Bearer-' + token])} with
 * {@code sessionId} carried as a query parameter. The interceptor lifts
 * {@code userId} (from token) and {@code sessionId} into session attributes.
 * <p>
 * RFC 6455 §4.2.2 mandates that the server echo back the chosen sub-protocol
 * in the 101 response — without it the browser drops the connection. We do
 * that explicitly in step 6, regardless of whether downstream registers
 * {@code supportedProtocols}.
 */
@Slf4j
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_SESSION_ID = "sessionId";
    public static final String ATTR_USER_ID = "userId";

    private static final String BEARER_PREFIX = "Bearer-";
    private static final String SEC_WS_PROTOCOL = "Sec-WebSocket-Protocol";

    @Override
    public boolean beforeHandshake(@NonNull ServerHttpRequest request,
                                   @NonNull ServerHttpResponse response,
                                   @NonNull WebSocketHandler wsHandler,
                                   @NonNull Map<String, Object> attributes) {

        // 1. Read Sec-WebSocket-Protocol header
        String protocolHeader = firstSubProtocol(request.getHeaders());
        if (protocolHeader == null || !protocolHeader.startsWith(BEARER_PREFIX)) {
            log.warn("WS handshake rejected: missing or malformed sub-protocol header");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        // 2. Strip prefix
        String token = protocolHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            log.warn("WS handshake rejected: empty token after Bearer- prefix");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        // 3. Resolve token → userId via Sa-Token
        Long userId;
        try {
            Object loginId = StpUtil.getLoginIdByToken(token);
            if (loginId == null) {
                log.warn("WS handshake rejected: token not recognized");
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            userId = Long.parseLong(loginId.toString());
        } catch (NumberFormatException e) {
            log.warn("WS handshake rejected: loginId is not numeric, token={}", token);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        // 4. Read sessionId from query
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            log.warn("WS handshake rejected: non-servlet request, cannot read query");
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        String sessionId = servletRequest.getServletRequest().getParameter("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("WS handshake rejected: missing sessionId query");
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        // 5. Bind to session attributes
        attributes.put(ATTR_USER_ID, userId);
        attributes.put(ATTR_SESSION_ID, sessionId);

        // 6. RFC 6455: echo the negotiated sub-protocol back, otherwise the
        //    browser will treat the connection as failed.
        response.getHeaders().add(SEC_WS_PROTOCOL, BEARER_PREFIX + token);

        log.info("WS handshake accepted: userId={}, sessionId={}", userId, sessionId);
        return true;
    }

    @Override
    public void afterHandshake(@NonNull ServerHttpRequest request,
                               @NonNull ServerHttpResponse response,
                               @NonNull WebSocketHandler wsHandler,
                               Exception exception) {
        // no-op
    }

    /**
     * Pick the first {@code Sec-WebSocket-Protocol} value. Browsers may pack
     * multiple protocol names into a single comma-separated header value when
     * the {@code WebSocket} constructor receives an array — split on comma to
     * find the {@code Bearer-} entry.
     */
    private String firstSubProtocol(HttpHeaders headers) {
        List<String> values = headers.get(SEC_WS_PROTOCOL);
        if (values == null || values.isEmpty()) {
            return null;
        }
        for (String raw : values) {
            for (String token : raw.split(",")) {
                String trimmed = token.trim();
                if (trimmed.startsWith(BEARER_PREFIX)) {
                    return trimmed;
                }
            }
        }
        // No Bearer- entry — return the first raw value so the upstream
        // prefix check fails and we surface a 401 rather than a 500.
        return values.getFirst().trim();
    }
}
