package com.assignment.marketdata.session;

import java.util.Optional;
import java.util.UUID;

import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Service;

import com.assignment.marketdata.model.LoginRequest;
import com.assignment.marketdata.model.LoginResponse;

/**
 * Verifies submitted credentials and issues opaque session tokens.
 */
@Service
public class AuthService {

	/**
	 * Hash of a random throwaway value, used to spend the same BCrypt work on an unknown username
	 * as on a real one so response timing does not reveal whether the account exists.
	 */
	private final String dummyHash = BCrypt.hashpw(UUID.randomUUID().toString(), BCrypt.gensalt());

	private final UserStore userStore;

	private final SessionRegistry sessionRegistry;

	public AuthService(UserStore userStore, SessionRegistry sessionRegistry) {
		this.userStore = userStore;
		this.sessionRegistry = sessionRegistry;
	}

	/**
	 * Authenticates a login request, returning the issued token on success or empty on any
	 * failure. Callers must not distinguish an unknown username from a wrong password.
	 */
	public Optional<LoginResponse> login(LoginRequest request) {
		if (request == null || isBlank(request.username()) || isBlank(request.password())) {
			return Optional.empty();
		}
		String username = request.username();
		Optional<String> storedHash = this.userStore.findPasswordHash(username);
		boolean passwordMatches = BCrypt.checkpw(request.password(), storedHash.orElse(this.dummyHash));
		if (storedHash.isEmpty() || !passwordMatches) {
			return Optional.empty();
		}
		String token = UUID.randomUUID().toString();
		this.sessionRegistry.register(username, token);
		return Optional.of(new LoginResponse(token, username));
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
