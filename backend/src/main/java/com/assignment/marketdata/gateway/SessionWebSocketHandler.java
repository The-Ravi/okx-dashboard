package com.assignment.marketdata.gateway;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import com.assignment.marketdata.model.ErrorMessage;
import com.assignment.marketdata.httphandlers.OkxOrderBookClient;
import com.assignment.marketdata.services.MarketService;
import com.assignment.marketdata.session.SessionRegistry;
import com.assignment.marketdata.session.SupersededSocketHandler;

/**
 * The {@code /ws/session} endpoint: authenticates the connection from its {@code token} query
 * parameter, enforces one live socket per user, carries order book subscribe and unsubscribe
 * traffic, and gives back every resource the connection held when it ends.
 */
@Component
public class SessionWebSocketHandler extends TextWebSocketHandler implements SupersededSocketHandler {

	private static final Logger logger = LoggerFactory.getLogger(SessionWebSocketHandler.class);

	private static final String SESSION_TERMINATED = "{\"type\":\"session_terminated\"}";

	private static final CloseStatus INVALID_TOKEN = new CloseStatus(4001, "invalid token");

	private static final CloseStatus SUPERSEDED = new CloseStatus(4000, "superseded");

	private static final String USER_ID_ATTRIBUTE = "marketdata.userId";

	private static final String SUBSCRIPTIONS_ATTRIBUTE = "marketdata.subscriptions";

	private final SessionRegistry sessionRegistry;

	private final OkxOrderBookClient orderBookClient;

	private final MarketService marketService;

	private final ObjectMapper objectMapper;

	public SessionWebSocketHandler(SessionRegistry sessionRegistry, OkxOrderBookClient orderBookClient,
			MarketService marketService, ObjectMapper objectMapper) {
		this.sessionRegistry = sessionRegistry;
		this.orderBookClient = orderBookClient;
		this.marketService = marketService;
		this.objectMapper = objectMapper;
	}

	/**
	 * The handshake is always accepted so that an unauthenticated client observes a real WebSocket
	 * close frame carrying code 4001, rather than an HTTP error from a rejected handshake.
	 */
	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		String token = extractToken(session.getUri());
		Optional<String> userId = this.sessionRegistry.attachWebSocket(token, session, this);
		if (userId.isEmpty()) {
			session.close(INVALID_TOKEN);
			return;
		}
		session.getAttributes().put(USER_ID_ATTRIBUTE, userId.get());
		session.getAttributes().put(SUBSCRIPTIONS_ATTRIBUTE, new ClientSubscriptions(this.orderBookClient));
		logger.info("Session WebSocket opened for user {} (socket {})", userId.get(), session.getId());
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		try {
			JsonNode root;
			try {
				root = this.objectMapper.readTree(message.getPayload());
			}
			catch (Exception ex) {
				sendError(session, "malformed message");
				return;
			}
			if (root == null || !root.isObject()) {
				sendError(session, "malformed message");
				return;
			}
			String op = root.path("op").asText(null);
			String instId = root.path("instId").asText(null);
			if ("subscribe".equals(op)) {
				handleSubscribe(session, instId);
			}
			else if ("unsubscribe".equals(op)) {
				handleUnsubscribe(session, instId);
			}
			else {
				sendError(session, "unknown op " + op);
			}
		}
		catch (Exception ex) {
			// A bad frame must never take the connection down.
			logger.warn("Failed to handle a client frame on socket {}: {}", session.getId(),
					ex.toString());
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		releaseSocket(session, "closed with " + status);
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		releaseSocket(session, "transport error: " + exception);
	}

	private void handleSubscribe(WebSocketSession session, String instId) {
		if (instId == null || instId.isBlank() || !this.marketService.isKnownInstrument(instId)) {
			sendError(session, "unknown instrument " + instId);
			return;
		}
		ClientSubscriptions subscriptions = subscriptionsOf(session);
		if (subscriptions == null) {
			return;
		}
		boolean added = subscriptions.add(instId, (update) -> sendJson(session, update));
		if (added) {
			logger.info("Socket {} subscribed to {}", session.getId(), instId);
		}
		else {
			logger.debug("Socket {} is already subscribed to {}", session.getId(), instId);
		}
	}

	private void handleUnsubscribe(WebSocketSession session, String instId) {
		if (instId == null || instId.isBlank()) {
			sendError(session, "unknown instrument " + instId);
			return;
		}
		ClientSubscriptions subscriptions = subscriptionsOf(session);
		if (subscriptions != null && subscriptions.remove(instId)) {
			logger.info("Socket {} unsubscribed from {}", session.getId(), instId);
		}
	}

	/**
	 * Tells the displaced client why it is going away and then closes it, whether it was displaced
	 * by a second connect or by a second login.
	 */
	@Override
	public void onSuperseded(String userId, WebSocketSession supersededSocket) {
		logger.info("Superseding session WebSocket for user {}: closing socket {}", userId,
				supersededSocket.getId());
		// Released here rather than left to the close callback, so the kick cannot strand upstream
		// subscriptions even if the close notification never arrives.
		releaseSubscriptions(supersededSocket, "superseded");
		try {
			if (supersededSocket.isOpen()) {
				sendRaw(supersededSocket, SESSION_TERMINATED);
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
	 * <p>
	 * Subscription release is deliberately not gated on the registry's identity check: a superseded
	 * socket no longer owns the session entry, but it still owns the upstream interest it opened.
	 */
	private void releaseSocket(WebSocketSession session, String cause) {
		releaseSubscriptions(session, cause);
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

	private void releaseSubscriptions(WebSocketSession session, String cause) {
		ClientSubscriptions subscriptions = subscriptionsOf(session);
		if (subscriptions == null) {
			return;
		}
		List<String> released = subscriptions.releaseAll();
		if (!released.isEmpty()) {
			logger.info("Socket {} released order book subscriptions {} ({})", session.getId(),
					released, cause);
		}
	}

	private static ClientSubscriptions subscriptionsOf(WebSocketSession session) {
		Object value = session.getAttributes().get(SUBSCRIPTIONS_ATTRIBUTE);
		return (value instanceof ClientSubscriptions subscriptions) ? subscriptions : null;
	}

	private void sendError(WebSocketSession session, String message) {
		logger.debug("Socket {} rejected: {}", session.getId(), message);
		sendJson(session, new ErrorMessage(message));
	}

	private void sendJson(WebSocketSession session, Object payload) {
		try {
			sendRaw(session, this.objectMapper.writeValueAsString(payload));
		}
		catch (Exception ex) {
			logger.debug("Could not serialise a frame for socket {}: {}", session.getId(),
					ex.toString());
		}
	}

	/**
	 * Sends are serialised per session: a WebSocket session rejects concurrent writes, and book
	 * pushes from the upstream reader thread can otherwise collide with a control frame sent from a
	 * container thread.
	 */
	private void sendRaw(WebSocketSession session, String json) {
		synchronized (session) {
			if (!session.isOpen()) {
				return;
			}
			try {
				session.sendMessage(new TextMessage(json));
			}
			catch (IOException | IllegalStateException ex) {
				logger.debug("Could not send to socket {}: {}", session.getId(), ex.toString());
			}
		}
	}

	private static String extractToken(URI uri) {
		if (uri == null) {
			return null;
		}
		return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("token");
	}
}
