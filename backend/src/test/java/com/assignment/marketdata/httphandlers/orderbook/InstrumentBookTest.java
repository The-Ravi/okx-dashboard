package com.assignment.marketdata.httphandlers.orderbook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import com.assignment.marketdata.enums.ApplyResult;
import com.assignment.marketdata.utility.AppConstants;

class InstrumentBookTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void snapshotReplacesDepthAndExposesNumericWindow() {
		InstrumentBook book = new InstrumentBook();
		book.replace(levels(List.of(List.of("9", "1"), List.of("10", "2")), List.of(List.of("11", "3"))), 1);

		assertThat(book.bidWindow()).containsExactly(List.of("10", "2"), List.of("9", "1"));
		assertThat(book.askWindow()).containsExactly(List.of("11", "3"));
		assertThat(book.lastSeqId()).isEqualTo(1);
	}

	@Test
	void deltaIsIgnoredUntilASnapshotHasBeenApplied() {
		InstrumentBook book = new InstrumentBook();

		assertThat(book.applyDelta(levels(List.of(List.of("1", "1")), List.of()), 2, 1))
				.isEqualTo(ApplyResult.AWAITING_SNAPSHOT);
		assertThat(book.bidWindow()).isEmpty();
	}

	@Test
	void duplicateSeqIsIgnored() {
		InstrumentBook book = new InstrumentBook();
		book.replace(levels(List.of(List.of("1", "1")), List.of()), 5);

		assertThat(book.applyDelta(levels(List.of(List.of("1", "9")), List.of()), 5, 5))
				.isEqualTo(ApplyResult.DUPLICATE);
		assertThat(book.bidWindow()).containsExactly(List.of("1", "1"));
	}

	@Test
	void sequenceGapResetsTheBook() {
		InstrumentBook book = new InstrumentBook();
		book.replace(levels(List.of(List.of("1", "1")), List.of()), 5);

		assertThat(book.applyDelta(levels(List.of(List.of("2", "1")), List.of()), 9, 8))
				.isEqualTo(ApplyResult.SEQUENCE_GAP);
		assertThat(book.lastSeqId()).isEqualTo(AppConstants.Okx.UNKNOWN_SEQ_ID);
		assertThat(book.bidWindow()).isEmpty();
	}

	@Test
	void followingDeltaAppliesAndZeroSizeDeletesTheLevel() {
		InstrumentBook book = new InstrumentBook();
		book.replace(levels(List.of(List.of("1", "1"), List.of("2", "2")), List.of()), 1);

		assertThat(book.applyDelta(levels(List.of(List.of("1", "0"), List.of("3", "3")), List.of()), 2, 1))
				.isEqualTo(ApplyResult.APPLIED);
		assertThat(book.bidWindow()).containsExactly(List.of("3", "3"), List.of("2", "2"));
		assertThat(book.lastSeqId()).isEqualTo(2);
	}

	@Test
	void windowIsCappedAtConfiguredDepth() {
		InstrumentBook book = new InstrumentBook(2);
		book.replace(levels(List.of(List.of("1", "1"), List.of("2", "2"), List.of("3", "3")), List.of()), 1);

		assertThat(book.bidWindow()).containsExactly(List.of("3", "3"), List.of("2", "2"));
	}

	private ObjectNode levels(List<List<String>> bids, List<List<String>> asks) {
		ObjectNode book = this.objectMapper.createObjectNode();
		putSide(book.putArray("bids"), bids);
		putSide(book.putArray("asks"), asks);
		return book;
	}

	private void putSide(ArrayNode side, List<List<String>> levels) {
		for (List<String> level : levels) {
			ArrayNode row = side.addArray();
			row.add(level.get(0));
			row.add(level.get(1));
		}
	}

}
