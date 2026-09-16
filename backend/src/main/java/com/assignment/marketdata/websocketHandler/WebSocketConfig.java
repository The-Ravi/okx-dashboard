package com.assignment.marketdata.websocketHandler;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Maps the session handler onto {@code /ws/session}.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

	private final SessionWebSocketHandler sessionWebSocketHandler;

	public WebSocketConfig(SessionWebSocketHandler sessionWebSocketHandler) {
		this.sessionWebSocketHandler = sessionWebSocketHandler;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		// The dev frontend is served from a different port, so same-origin-only would reject it.
		registry.addHandler(this.sessionWebSocketHandler, "/ws/session").setAllowedOriginPatterns("*");
	}
}
