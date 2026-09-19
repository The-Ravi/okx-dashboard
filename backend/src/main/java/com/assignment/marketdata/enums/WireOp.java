package com.assignment.marketdata.enums;

/**
 * Subscribe and unsubscribe as they appear on both the client session socket and the OKX books
 * channel.
 */
public enum WireOp {

	SUBSCRIBE("subscribe"),

	UNSUBSCRIBE("unsubscribe");

	private final String value;

	WireOp(String value) {
		this.value = value;
	}

	public String value() {
		return this.value;
	}

	public boolean matches(String raw) {
		return this.value.equals(raw);
	}

	public static WireOp fromValue(String raw) {
		if (raw == null) {
			return null;
		}
		for (WireOp op : values()) {
			if (op.value.equals(raw)) {
				return op;
			}
		}
		return null;
	}

}
