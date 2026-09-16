package com.assignment.marketdata.controllers;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.assignment.marketdata.exception.InvalidCredentialsException;
import com.assignment.marketdata.model.LoginRequest;
import com.assignment.marketdata.model.LoginResponse;
import com.assignment.marketdata.services.AuthService;

/**
 * Login endpoint. HTTP concerns only: credential checking and token issuance live in the services
 * package, and the 401 body is rendered by the exception package.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/login")
	public LoginResponse login(@RequestBody(required = false) LoginRequest request) {
		return this.authService.login(request).orElseThrow(InvalidCredentialsException::new);
	}

}
