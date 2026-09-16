package com.assignment.marketdata.gateway;

import java.net.URI;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import com.assignment.marketdata.session.SessionRegistry;

/**
 * The {@code /ws/session} endpoint: authenticates the connection from its {@code token} query
 * parameter, enforces one live socket per user, and releases the socket reference on disconnect.
 */
@Component
public class SessionWebSocketHandler extends TextWebSocketHandler {

	private static final Logger logger = LoggerFactory.getLogger(SessionWebSocketHandler.class);

	private static final String SESSION_TERMINATED = "{\"type\":\"session_terminated\"}";

	private static final CloseStatus INVALID_TOKEN = new CloseStatus(4001, "invalid token");

	private static final CloseStatus SUPERSEDED = new CloseStatus(4000, "superseded");

	private static final String USER_ID_ATTRIBUTE = "marketdata.userId";

	private final SessionRegistry sessionRegistry;

	public SessionWebSocketHandler(SessionRegistry sessionRegistry) {
		this.sessionRegistry = sessionRegistry;
	}

	/**
	 * The handshake is always accepted so that an unauthenticated client observes a real WebSocket
	 * close frame carrying code 4001, rather than an HTTP error from a rejected handshake.
	 */
	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		String token = extractToken(session.getUri());
		Optional<String> userId = this.sessionRegistry.attachWebSocket(token, session,
				this::terminateSuperseded);
		if (userId.isEmpty()) {
			session.close(INVALID_TOKEN);
			return;
		}
		session.getAttributes().put(USER_ID_ATTRIBUTE, userId.get());
		logger.info("Session WebSocket opened for user {} (socket {})", userId.get(), session.getId());
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		releaseSocket(session, "closed with " + status);
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		releaseSocket(session, "transport error: " + exception);
	}

	private void terminateSuperseded(String userId, WebSocketSession supersededSocket) {
		logger.info("Superseding session WebSocket for user {}: closing socket {}", userId,
				supersededSocket.getId());
		try {
			if (supersededSocket.isOpen()) {
				supersededSocket.sendMessage(new TextMessage(SESSION_TERMINATED));
			}
			supersededSocket.close(SUPERSEDED);
		}
		catch (Exception ex) {
			// The old client may already be gone; the newcomer connects either way.
			logger.warn("Could not cleanly terminate superseded socket {} for user {}: {}",
					supersededSocket.getId(), userId, ex.toString());
		}
	}

	/**
	 * Clears this socket from its session entry while leaving the entry itself in place, so the
	 * token remains usable for a reconnect.
	 */
	private void releaseSocket(WebSocketSession session, String cause) {
		Object userId = session.getAttributes().get(USER_ID_ATTRIBUTE);
		if (!(userId instanceof String id)) {
			return;
		}
		boolean cleared = this.sessionRegistry.detachWebSocket(id, session);
		if (cleared) {
			logger.info("Released session WebSocket for user {} (socket {}, {})", id, session.getId(),
					cause);
		}
		else {
			logger.debug("Socket {} for user {} was already replaced; leaving the current "
					+ "reference intact ({})", session.getId(), id, cause);
		}
	}

	private static String extractToken(URI uri) {
		if (uri == null) {
			return null;
		}
		return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("token");
	}
}
