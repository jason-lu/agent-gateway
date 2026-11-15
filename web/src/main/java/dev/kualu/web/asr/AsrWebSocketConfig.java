package dev.kualu.web.asr;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the streaming ASR endpoint. The handshake is a plain GET through
 * the servlet filter chain, so the JWT filter (query-param fallback included)
 * authenticates it like any other {@code /api/v1/**} call — hence any origin is
 * allowed, matching the REST API's token-gated posture.
 */
@Configuration
@EnableWebSocket
public class AsrWebSocketConfig implements WebSocketConfigurer {

    private final AsrWebSocketHandler handler;

    public AsrWebSocketConfig(AsrWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/asr/stream").setAllowedOriginPatterns("*");
    }
}
