package com.assignment.marketdata.session;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Active sessions keyed by userId, with a reverse index from token to userId. Lives for the
 * lifetime of the process; sessions are not persisted across a restart.
 * <p>
 * Every mutation of either map happens while holding that user's lock, so the two cannot drift
 * apart. The lock is a {@link ReentrantLock} rather than {@code ConcurrentHashMap.compute} because
 * {@link #register} and {@link #attachWebSocket} both run socket I/O inside the critical section:
 * closing a superseded socket can dispatch its close callback on the calling thread, which would
 * re-enter the registry. That is safe under a reentrant lock, whereas a recursive update from
 * inside {@code compute} is forbidden.
 */
@Component
public class SessionRegistry {

	private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();

	private final Map<String, String> userIdsByToken = new ConcurrentHashMap<>();

	private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

	/**
	 * Registers a freshly issued token for a user, replacing any previous session entry, dropping
	 * the superseded token from the reverse index so it can no longer resolve, and handing any
	 * socket still open under that old token to {@code supersededHandler}.
	 * <p>
	 * Because the map is keyed by userId, a second successful login for the same user overwrites
	 * the entry, so the token issued earlier is orphaned and immediately unusable. Revoking the
	 * token is not enough on its own: an already-open socket was authenticated at connect time and
	 * is never re-checked against the reverse index, so it would keep streaming under a token that
	 * no longer exists. The old client is therefore closed out here, while the entry it belongs to
	 * is still the current one.
	 */
	public SessionInfo register(String userId, String token, SupersededSocketHandler supersededHandler) {
		ReentrantLock lock = lockFor(userId);
		lock.lock();
		try {
			SessionInfo previous = this.sessions.get(userId);
			if (previous != null) {
				this.userIdsByToken.remove(previous.getToken());
				WebSocketSession supersededSocket = previous.getWebSocketSession();
				if (supersededSocket != null) {
					// Before the new entry is installed, so the close callback this provokes still
					// finds the entry it is clearing and does not touch the incoming session.
					supersededHandler.onSuperseded(userId, supersededSocket);
				}
			}
			SessionInfo session = new SessionInfo(token, null);
			this.sessions.put(userId, session);
			this.userIdsByToken.put(token, userId);
			return session;
		}
		finally {
			lock.unlock();
		}
	}

	/**
	 * Attaches a newly connected socket to the session identified by {@code token}, handing any
	 * socket it displaces to {@code supersededHandler} first.
	 * <p>
	 * The whole check-kick-register sequence holds the user's lock, so two simultaneous connects
	 * for one user cannot both conclude they are the newcomer: the second to acquire the lock sees
	 * the first already attached and displaces it.
	 * @return the resolved userId, or empty if the token is unknown or has been superseded, in
	 * which case nothing was attached
	 */
	public Optional<String> attachWebSocket(String token, WebSocketSession socket,
			SupersededSocketHandler supersededHandler) {
		if (token == null) {
			return Optional.empty();
		}
		String userId = this.userIdsByToken.get(token);
		if (userId == null) {
			return Optional.empty();
		}
		ReentrantLock lock = lockFor(userId);
		lock.lock();
		try {
			SessionInfo session = this.sessions.get(userId);
			// Re-checked under the lock: a concurrent re-login may have superseded this token
			// between the reverse-index read above and here.
			if (session == null || !session.getToken().equals(token)) {
				return Optional.empty();
			}
			WebSocketSession previous = session.getWebSocketSession();
			if (previous != null) {
				supersededHandler.onSuperseded(userId, previous);
			}
			session.setWebSocketSession(socket);
			return Optional.of(userId);
		}
		finally {
			lock.unlock();
		}
	}

	/**
	 * Clears the socket reference for a user, keeping the session entry so the token stays valid
	 * for a future reconnect.
	 * <p>
	 * Only clears it when the stored socket is the one that is closing. A superseded socket's close
	 * callback arrives after its replacement has already attached, and must not wipe out the
	 * newcomer's reference.
	 * @return whether the reference was actually cleared
	 */
	public boolean detachWebSocket(String userId, WebSocketSession socket) {
		ReentrantLock lock = lockFor(userId);
		lock.lock();
		try {
			SessionInfo session = this.sessions.get(userId);
			if (session == null) {
				return false;
			}
			WebSocketSession current = session.getWebSocketSession();
			if (current == null || !isSameSocket(current, socket)) {
				return false;
			}
			session.setWebSocketSession(null);
			return true;
		}
		finally {
			lock.unlock();
		}
	}

	public Optional<SessionInfo> find(String userId) {
		return Optional.ofNullable(this.sessions.get(userId));
	}

	public Optional<String> findUserIdByToken(String token) {
		if (token == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(this.userIdsByToken.get(token));
	}

	/**
	 * Identity comparison, falling back to the session id in case the container hands the handler a
	 * decorated instance rather than the one it received on connect.
	 */
	private static boolean isSameSocket(WebSocketSession current, WebSocketSession candidate) {
		return current == candidate || current.getId().equals(candidate.getId());
	}

	private ReentrantLock lockFor(String userId) {
		return this.locks.computeIfAbsent(userId, (key) -> new ReentrantLock());
	}
}
