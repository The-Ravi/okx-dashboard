package com.assignment.marketdata.model;

/**
 * Body of {@code POST /auth/login}.
 */
public record LoginRequest(String username, String password) {
}
