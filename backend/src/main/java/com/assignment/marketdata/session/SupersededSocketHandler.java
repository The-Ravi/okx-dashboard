package com.assignment.marketdata.session;

import org.springframework.web.socket.WebSocketSession;

/**
 * Disposes of a socket that has lost ownership of its user's session.
 * <p>
 * Both halves of the single-session rule displace a socket, and each is detected in a place that
 * has no business writing WebSocket frames: a second login is handled by the HTTP layer, and a
 * second connect by the registry itself. Both hand the loser here, and the gateway that owns the
 * socket decides what the departing client is told.
 */
public interface SupersededSocketHandler {

	/**
	 * Notifies and closes {@code supersededSocket}, releasing anything it held. Implementations
	 * must not throw: the newcomer is admitted whether or not the old client can still be reached.
	 */
	void onSuperseded(String userId, WebSocketSession supersededSocket);

}
