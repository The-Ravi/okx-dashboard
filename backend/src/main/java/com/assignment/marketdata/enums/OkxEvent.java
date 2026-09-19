package com.assignment.marketdata.enums;

/**
 * OKX control-event names carried on inbound frames that have an {@code event} field.
 */
public enum OkxEvent {

	ERROR("error");

	private final String value;

	OkxEvent(String value) {
		this.value = value;
	}

	public String value() {
		return this.value;
	}

	public boolean matches(String raw) {
		return this.value.equals(raw);
	}

}
