package com.assignment.marketdata.enums;

/**
 * {@code type} values on frames pushed to the browser over the session WebSocket.
 */
public enum ClientMessageType {

	BOOK("book"),

	ERROR("error"),

	SESSION_TERMINATED("session_terminated");

	private final String value;

	ClientMessageType(String value) {
		this.value = value;
	}

	public String value() {
		return this.value;
	}

}
