package com.assignment.marketdata.model;

/**
 * Successful {@code POST /auth/login} response, carrying the opaque session token.
 */
public record LoginResponse(String token, String username) {
}
