package com.assignment.marketdata.httphandlers.orderbook;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import com.assignment.marketdata.enums.OkxEvent;
import com.assignment.marketdata.enums.WireOp;
import com.assignment.marketdata.utility.AppConstants;

class OkxBooksCodecTest {

	private final OkxBooksCodec codec = new OkxBooksCodec(new ObjectMapper());

	@Test
	void subscribeFrameCarriesTheBooksChannelAndInstrument() throws Exception {
		ObjectNode frame = (ObjectNode) new ObjectMapper().readTree(this.codec.subscribe("BTC-USDT"));

		assertThat(frame.path(AppConstants.Json.OP).asText()).isEqualTo(WireOp.SUBSCRIBE.value());
		assertThat(frame.path(AppConstants.Json.ARGS).get(0).path(AppConstants.Json.CHANNEL).asText())
				.isEqualTo(AppConstants.Okx.BOOKS_CHANNEL);
		assertThat(frame.path(AppConstants.Json.ARGS).get(0).path(AppConstants.Json.INST_ID).asText())
				.isEqualTo("BTC-USDT");
	}

	@Test
	void parsesKeepalivePong() {
		assertThat(this.codec.parse(AppConstants.Okx.PONG)).isInstanceOf(OkxBooksCodec.Frame.KeepalivePong.class);
	}

	@Test
	void parsesSnapshotBookData() {
		OkxBooksCodec.Frame frame = this.codec.parse("""
				{"arg":{"channel":"books","instId":"BTC-USDT"},"action":"snapshot",\
				"data":[{"bids":[["1","2"]],"asks":[["3","4"]],"ts":"1","seqId":10,"prevSeqId":0}]}
				""");

		assertThat(frame).isInstanceOf(OkxBooksCodec.Frame.BookData.class);
		OkxBooksCodec.Frame.BookData book = (OkxBooksCodec.Frame.BookData) frame;
		assertThat(book.instId()).isEqualTo("BTC-USDT");
		assertThat(book.snapshot()).isTrue();
		assertThat(book.book().path("seqId").asLong()).isEqualTo(10);
	}

	@Test
	void parsesControlEvents() {
		OkxBooksCodec.Frame frame = this.codec.parse(
				"""
				{"event":"error","code":"60018","msg":"Invalid request","arg":{"instId":"BTC-USDT"}}
				""");

		assertThat(frame).isInstanceOf(OkxBooksCodec.Frame.ControlEvent.class);
		OkxBooksCodec.Frame.ControlEvent event = (OkxBooksCodec.Frame.ControlEvent) frame;
		assertThat(event.event()).isEqualTo(OkxEvent.ERROR.value());
		assertThat(event.code()).isEqualTo("60018");
		assertThat(event.instId()).isEqualTo("BTC-USDT");
	}

	@Test
	void unparseablePayloadIsTypedRatherThanThrown() {
		assertThat(this.codec.parse("{not-json")).isInstanceOf(OkxBooksCodec.Frame.Unparseable.class);
	}

}
