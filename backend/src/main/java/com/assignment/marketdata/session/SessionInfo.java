package com.assignment.marketdata.session;

import org.springframework.web.socket.WebSocketSession;

/**
 * Server-side state for one authenticated user: the issued token plus the session WebSocket
 * once the client opens it. Internal state, never serialised to clients.
 */
public class SessionInfo {

	private final String token;

	// Written and read from different container threads.
	private volatile WebSocketSession webSocketSession;

	public SessionInfo(String token, WebSocketSession webSocketSession) {
		this.token = token;
		this.webSocketSession = webSocketSession;
	}

	public String getToken() {
		return this.token;
	}

	/**
	 * The session WebSocket, or {@code null} until the client connects.
	 */
	public WebSocketSession getWebSocketSession() {
		return this.webSocketSession;
	}

	public void setWebSocketSession(WebSocketSession webSocketSession) {
		this.webSocketSession = webSocketSession;
	}
}
