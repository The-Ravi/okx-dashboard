package com.assignment.marketdata.model;

/**
 * Error frame sent over the session WebSocket. Distinct from {@link ErrorResponse}, which is the
 * HTTP error body.
 */
public record ErrorMessage(String type, String message) {

	public ErrorMessage(String message) {
		this("error", message);
	}
}
