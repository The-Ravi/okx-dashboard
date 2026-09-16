package com.assignment.marketdata.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.assignment.marketdata.model.ErrorResponse;
import com.assignment.marketdata.model.LoginRequest;
import com.assignment.marketdata.session.AuthService;

/**
 * Login endpoint. HTTP concerns only; credential checking and token issuance live in the
 * session package.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

	private static final ErrorResponse INVALID_CREDENTIALS = new ErrorResponse("invalid credentials");

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/login")
	public ResponseEntity<Object> login(@RequestBody(required = false) LoginRequest request) {
		return this.authService.login(request)
				.<ResponseEntity<Object>>map(ResponseEntity::ok)
				.orElseGet(AuthController::unauthorized);
	}

	/**
	 * An unparseable body is a failed credential submission like any other, so it gets the same
	 * 401 rather than a status the contract does not define.
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Object> handleUnreadableBody() {
		return unauthorized();
	}

	private static ResponseEntity<Object> unauthorized() {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(INVALID_CREDENTIALS);
	}
}
