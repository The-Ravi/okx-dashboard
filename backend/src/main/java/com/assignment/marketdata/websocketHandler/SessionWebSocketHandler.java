package com.assignment.marketdata.websocketHandler;

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

import com.assignment.marketdata.enums.WireOp;
import com.assignment.marketdata.model.ErrorMessage;
import com.assignment.marketdata.services.MarketService;
import com.assignment.marketdata.services.OrderBookFeed;
import com.assignment.marketdata.session.SessionRegistry;
import com.assignment.marketdata.session.SupersededSocketHandler;
import com.assignment.marketdata.utility.AppConstants;
import com.assignment.marketdata.utility.AppUtils;

/**
 * The {@code /ws/session} endpoint: authenticates the connection from its {@code token} query
 * parameter, enforces one live socket per user, carries order book subscribe and unsubscribe
 * traffic, and gives back every resource the connection held when it ends.
 */
@Component
public class SessionWebSocketHandler extends TextWebSocketHandler implements SupersededSocketHandler {

	private static final Logger logger = LoggerFactory.getLogger(SessionWebSocketHandler.class);

	private static final CloseStatus INVALID_TOKEN = new CloseStatus(
			AppConstants.WebSocket.CLOSE_CODE_INVALID_TOKEN,
			AppConstants.WebSocket.CLOSE_REASON_INVALID_TOKEN);

	private static final CloseStatus SUPERSEDED = new CloseStatus(
			AppConstants.WebSocket.CLOSE_CODE_SUPERSEDED, AppConstants.WebSocket.CLOSE_REASON_SUPERSEDED);

	private final SessionRegistry sessionRegistry;

	private final OrderBookFeed orderBookFeed;

	private final MarketService marketService;

	private final ObjectMapper objectMapper;

	public SessionWebSocketHandler(SessionRegistry sessionRegistry, OrderBookFeed orderBookFeed,
			MarketService marketService, ObjectMapper objectMapper) {
		this.sessionRegistry = sessionRegistry;
		this.orderBookFeed = orderBookFeed;
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
			logger.warn("Rejected WebSocket with an invalid or superseded token (socket {})",
					session.getId());
			session.close(INVALID_TOKEN);
			return;
		}
		session.getAttributes().put(AppConstants.WebSocket.USER_ID_ATTRIBUTE, userId.get());
		session.getAttributes().put(AppConstants.WebSocket.SUBSCRIPTIONS_ATTRIBUTE,
				new ClientSubscriptions(this.orderBookFeed));
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
				sendError(session, AppConstants.Errors.MALFORMED_MESSAGE);
				return;
			}
			if (root == null || !root.isObject()) {
				sendError(session, AppConstants.Errors.MALFORMED_MESSAGE);
				return;
			}
			WireOp op = WireOp.fromValue(root.path(AppConstants.Json.OP).asText(null));
			String instId = root.path(AppConstants.Json.INST_ID).asText(null);
			if (op == WireOp.SUBSCRIBE) {
				handleSubscribe(session, instId);
			}
			else if (op == WireOp.UNSUBSCRIBE) {
				handleUnsubscribe(session, instId);
			}
			else {
				sendError(session, AppConstants.Errors.UNKNOWN_OP_PREFIX + root.path(AppConstants.Json.OP).asText());
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
		if (AppUtils.isBlank(instId) || !this.marketService.isKnownInstrument(instId)) {
			sendError(session, AppConstants.Errors.UNKNOWN_INSTRUMENT_PREFIX + instId);
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
		if (AppUtils.isBlank(instId)) {
			sendError(session, AppConstants.Errors.UNKNOWN_INSTRUMENT_PREFIX + instId);
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
		releaseSubscriptions(supersededSocket, AppConstants.WebSocket.CLOSE_REASON_SUPERSEDED);
		try {
			if (supersededSocket.isOpen()) {
				sendRaw(supersededSocket, AppConstants.WebSocket.SESSION_TERMINATED_FRAME);
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
		Object userId = session.getAttributes().get(AppConstants.WebSocket.USER_ID_ATTRIBUTE);
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
		Object value = session.getAttributes().get(AppConstants.WebSocket.SUBSCRIPTIONS_ATTRIBUTE);
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
		return AppUtils.queryParam(uri, AppConstants.WebSocket.TOKEN_QUERY_PARAM);
	}

}
