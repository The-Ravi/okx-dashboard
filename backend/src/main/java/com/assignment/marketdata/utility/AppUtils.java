package com.assignment.marketdata.utility;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.web.util.UriComponentsBuilder;

import com.assignment.marketdata.model.TickerDto;

/**
 * Stateless helpers shared by adapters and services. Parsing, blank checks, URI query reads, and
 * reconnect backoff live here rather than being reimplemented in each caller.
 */
public final class AppUtils {

	private AppUtils() {
	}

	public static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	public static String textOrNull(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || !value.isTextual() || value.asText().isEmpty()) {
			return null;
		}
		return value.asText();
	}

	public static BigDecimal parseBigDecimal(String value) {
		if (value == null) {
			return null;
		}
		try {
			return new BigDecimal(value);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	public static BigDecimal toBigDecimalOrZero(String value) {
		BigDecimal parsed = parseBigDecimal(value);
		return parsed != null ? parsed : BigDecimal.ZERO;
	}

	/**
	 * {@code ((last - open24h) / open24h) * 100} to two decimal places, or null when any input is
	 * missing, unparseable, or would divide by zero. Null keeps the response valid JSON, which
	 * Infinity and NaN would not.
	 */
	public static BigDecimal changePercent(String last, String open24h) {
		if (last == null || open24h == null) {
			return null;
		}
		try {
			BigDecimal open = new BigDecimal(open24h);
			if (open.signum() == 0) {
				return null;
			}
			// Multiplying before dividing keeps this to a single rounding step.
			return new BigDecimal(last).subtract(open)
					.multiply(BigDecimal.valueOf(AppConstants.Okx.PERCENT_MULTIPLIER))
					.divide(open, AppConstants.Okx.CHANGE_PERCENT_SCALE, RoundingMode.HALF_UP);
		}
		catch (NumberFormatException | ArithmeticException ex) {
			return null;
		}
	}

	public static String queryParam(URI uri, String name) {
		if (uri == null) {
			return null;
		}
		return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(name);
	}

	public static String preview(String text) {
		int max = AppConstants.Okx.SEND_LOG_PREVIEW_LENGTH;
		return text.length() > max ? text.substring(0, max) : text;
	}

	/**
	 * Exponential backoff with jitter so repeated failures do not retry in lockstep.
	 */
	public static long reconnectDelayMillis(int failedAttempts) {
		long min = AppConstants.Okx.MIN_BACKOFF_MILLIS;
		long max = AppConstants.Okx.MAX_BACKOFF_MILLIS;
		long exponential = Math.min(max,
				min * (1L << Math.min(failedAttempts - 1, AppConstants.Okx.BACKOFF_SHIFT_CAP)));
		long delay = min / 2 + ThreadLocalRandom.current().nextLong(exponential);
		return Math.min(delay, max);
	}

	public static boolean isKnownInstrument(List<TickerDto> tickers, String instId) {
		if (instId == null) {
			return false;
		}
		for (TickerDto ticker : tickers) {
			if (instId.equals(ticker.instId())) {
				return true;
			}
		}
		return false;
	}

	public static Thread newDaemonThread(Runnable runnable, String name) {
		Thread thread = new Thread(runnable, name);
		thread.setDaemon(true);
		return thread;
	}

}
