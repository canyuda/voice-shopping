package com.voiceshopping.web.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers {@link VoiceWebSocketHandler} at {@code /ws/voice} with
 * {@link AuthHandshakeInterceptor}. The interceptor performs Sa-Token-based
 * sub-protocol authentication, lifting {@code userId} (from token) and
 * {@code sessionId} (from query) into the WS session attributes.
 * <p>
 * {@code supportedProtocols} is intentionally not declared on the registry —
 * the {@code Bearer-{token}} sub-protocol pattern is verified at runtime by
 * the interceptor and the response header is echoed back per RFC 6455.
 * Allows all origins for debugging purposes.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final VoiceWebSocketHandler voiceWebSocketHandler;

    public WebSocketConfig(VoiceWebSocketHandler voiceWebSocketHandler) {
        this.voiceWebSocketHandler = voiceWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(voiceWebSocketHandler, "/ws/voice")
                .addInterceptors(new AuthHandshakeInterceptor())
                .setAllowedOrigins("*");
    }
}
