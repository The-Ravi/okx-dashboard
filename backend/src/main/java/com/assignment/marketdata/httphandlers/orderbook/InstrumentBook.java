package com.assignment.marketdata.httphandlers.orderbook;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;

import com.assignment.marketdata.enums.ApplyResult;
import com.assignment.marketdata.utility.AppConstants;
import com.assignment.marketdata.utility.AppUtils;

/**
 * Full-depth book for one instrument. Prices key the maps as {@link BigDecimal} so ordering is
 * numeric, while the values keep OKX's original strings so nothing is ever reformatted on the way
 * out.
 * <p>
 * Sequence integrity lives here: a snapshot replaces the book, and an incremental frame is applied
 * only when it follows the last applied {@code seqId}.
 */
public final class InstrumentBook {

	private final int windowDepth;

	private final NavigableMap<BigDecimal, Level> bids = new TreeMap<>(Comparator.reverseOrder());

	private final NavigableMap<BigDecimal, Level> asks = new TreeMap<>();

	private long lastSeqId = AppConstants.Okx.UNKNOWN_SEQ_ID;

	public InstrumentBook() {
		this(AppConstants.Okx.ORDER_BOOK_WINDOW_DEPTH);
	}

	public InstrumentBook(int windowDepth) {
		this.windowDepth = windowDepth;
	}

	public void reset() {
		this.bids.clear();
		this.asks.clear();
		this.lastSeqId = AppConstants.Okx.UNKNOWN_SEQ_ID;
	}

	public void replace(JsonNode book, long seqId) {
		this.bids.clear();
		this.asks.clear();
		apply(book);
		this.lastSeqId = seqId;
	}

	public ApplyResult applyDelta(JsonNode book, long seqId, long prevSeqId) {
		if (this.lastSeqId < 0) {
			// No snapshot applied yet on this connection; wait for one.
			return ApplyResult.AWAITING_SNAPSHOT;
		}
		if (seqId == prevSeqId) {
			return ApplyResult.DUPLICATE;
		}
		if (prevSeqId != this.lastSeqId) {
			reset();
			return ApplyResult.SEQUENCE_GAP;
		}
		apply(book);
		this.lastSeqId = seqId;
		return ApplyResult.APPLIED;
	}

	public List<List<String>> bidWindow() {
		return window(this.bids);
	}

	public List<List<String>> askWindow() {
		return window(this.asks);
	}

	public long lastSeqId() {
		return this.lastSeqId;
	}

	private void apply(JsonNode book) {
		applySide(this.bids, book.path(AppConstants.Json.BIDS));
		applySide(this.asks, book.path(AppConstants.Json.ASKS));
	}

	private static void applySide(NavigableMap<BigDecimal, Level> side, JsonNode levels) {
		if (!levels.isArray()) {
			return;
		}
		for (JsonNode level : levels) {
			// OKX ships [price, size, deprecated, orderCount]; only the first two matter.
			if (!level.isArray() || level.size() < AppConstants.Okx.MIN_LEVEL_FIELDS) {
				continue;
			}
			String price = level.get(AppConstants.Okx.LEVEL_PRICE_INDEX).asText();
			String size = level.get(AppConstants.Okx.LEVEL_SIZE_INDEX).asText();
			BigDecimal key = AppUtils.parseBigDecimal(price);
			BigDecimal quantity = AppUtils.parseBigDecimal(size);
			if (key == null || quantity == null) {
				continue;
			}
			if (quantity.signum() == 0) {
				side.remove(key);
			}
			else {
				side.put(key, new Level(price, size));
			}
		}
	}

	private List<List<String>> window(NavigableMap<BigDecimal, Level> side) {
		List<List<String>> window = new ArrayList<>(this.windowDepth);
		for (Level level : side.values()) {
			if (window.size() == this.windowDepth) {
				break;
			}
			window.add(List.of(level.price(), level.size()));
		}
		return List.copyOf(window);
	}

	private record Level(String price, String size) {
	}

}
