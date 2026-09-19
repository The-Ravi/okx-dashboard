package com.assignment.marketdata.model;

import com.assignment.marketdata.enums.ClientMessageType;

/**
 * Error frame sent over the session WebSocket. Distinct from {@link ErrorResponse}, which is the
 * HTTP error body.
 */
public record ErrorMessage(String type, String message) {

	public ErrorMessage(String message) {
		this(ClientMessageType.ERROR.value(), message);
	}
}
