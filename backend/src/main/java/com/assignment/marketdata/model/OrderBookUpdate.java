package com.assignment.marketdata.model;

import java.util.List;

/**
 * A complete top-of-book window pushed to a client.
 * <p>
 * Every message carries the full window rather than a delta, so there is no snapshot/update
 * distinction on the wire and no client-side merge. A client holding only the top levels cannot
 * correctly merge a delta taken from the full-depth book, because it cannot know which hidden level
 * should promote into view when a visible one is removed.
 * <p>
 * Levels are {@code [price, size]} string pairs carrying OKX's original text, bids descending and
 * asks ascending. {@code ts} is OKX's book generation timestamp, passed through as a string.
 */
public record OrderBookUpdate(String type, String instId, String ts, List<List<String>> bids,
		List<List<String>> asks) {

	public OrderBookUpdate(String instId, String ts, List<List<String>> bids, List<List<String>> asks) {
		this("book", instId, ts, bids, asks);
	}
}
