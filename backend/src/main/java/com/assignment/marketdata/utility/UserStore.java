package com.assignment.marketdata.utility;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Component;

/**
 * Hardcoded in-memory user directory. Passwords are BCrypt-hashed once at startup and only
 * the resulting hashes are retained; no plaintext is held in any field.
 */
@Component
public class UserStore {

	// ===== TEST-ONLY: local test credentials, not used by any code path =====
	// trader1 / changeme123
	// trader2 / orderbook456
	// analyst1 / depthchart789
	// ========================================================================

	private final Map<String, String> passwordHashes;

	public UserStore() {
		// The plaintext exists only as arguments to hashpw and is never assigned anywhere.
		Map<String, String> hashes = new LinkedHashMap<>();
		hashes.put(AppConstants.Users.TRADER1, BCrypt.hashpw(AppConstants.Users.TRADER1_PASSWORD, BCrypt.gensalt()));
		hashes.put(AppConstants.Users.TRADER2, BCrypt.hashpw(AppConstants.Users.TRADER2_PASSWORD, BCrypt.gensalt()));
		hashes.put(AppConstants.Users.ANALYST1, BCrypt.hashpw(AppConstants.Users.ANALYST1_PASSWORD, BCrypt.gensalt()));
		this.passwordHashes = Map.copyOf(hashes);
	}

	/**
	 * Returns the stored BCrypt hash for the given username, or empty if no such user exists.
	 */
	public Optional<String> findPasswordHash(String username) {
		if (AppUtils.isBlank(username)) {
			return Optional.empty();
		}
		return Optional.ofNullable(this.passwordHashes.get(username));
	}
}
