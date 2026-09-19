package com.assignment.marketdata.enums;

/**
 * OKX books {@code action} values. A snapshot replaces depth; anything else is treated as a delta.
 */
public enum BookAction {

	SNAPSHOT("snapshot");

	private final String value;

	BookAction(String value) {
		this.value = value;
	}

	public String value() {
		return this.value;
	}

	public boolean matches(String raw) {
		return this.value.equals(raw);
	}

}
