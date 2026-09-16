package com.assignment.marketdata.session;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Active sessions keyed by userId. Lives for the lifetime of the process; sessions are not
 * persisted across a restart.
 */
@Component
public class SessionRegistry {

	private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();

	/**
	 * Registers a freshly issued token for a user, replacing any previous session entry.
	 * <p>
	 * Because the map is keyed by userId, a second successful login for the same user overwrites
	 * the entry, so the token issued earlier is orphaned and immediately unusable. That is
	 * intentional: it is the "newest authentication wins" half of the single-session rule.
	 * Terminating the superseded WebSocket is the other half and is handled where that socket is
	 * managed, not here.
	 */
	public SessionInfo register(String userId, String token) {
		SessionInfo session = new SessionInfo(token, null);
		this.sessions.put(userId, session);
		return session;
	}

	public Optional<SessionInfo> find(String userId) {
		return Optional.ofNullable(this.sessions.get(userId));
	}
}
