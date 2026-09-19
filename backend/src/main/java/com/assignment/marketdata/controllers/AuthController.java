package com.assignment.marketdata.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.assignment.marketdata.exception.InvalidCredentialsException;
import com.assignment.marketdata.model.LoginRequest;
import com.assignment.marketdata.model.LoginResponse;
import com.assignment.marketdata.services.AuthService;
import com.assignment.marketdata.utility.AppConstants;

/**
 * Login endpoint. HTTP concerns only: credential checking and token issuance live in the services
 * package, and the 401 body is rendered by the exception package.
 */
@RestController
@RequestMapping(AppConstants.Http.AUTH_BASE)
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping(AppConstants.Http.LOGIN_PATH)
	public ResponseEntity<LoginResponse> login(@RequestBody(required = false) LoginRequest request) {
		LoginResponse response = this.authService.login(request)
				.orElseThrow(InvalidCredentialsException::new);
		return ResponseEntity.ok(response);
	}

}
