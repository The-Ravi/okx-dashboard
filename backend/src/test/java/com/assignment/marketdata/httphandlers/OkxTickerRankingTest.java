package com.assignment.marketdata.httphandlers;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import com.assignment.marketdata.model.TickerDto;
import com.assignment.marketdata.utility.AppConstants;

class OkxTickerRankingTest {

	private final OkxTickerRanking ranking = new OkxTickerRanking();

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void ranksByNumericQuoteVolumeNotLexicographicOrder() {
		ArrayNode data = this.objectMapper.createArrayNode();
		data.add(ticker("AAA-USDT", "1", "1", "900"));
		data.add(ticker("BBB-USDT", "110", "100", "1000"));
		data.add(ticker("CCC-USDT", "2", "1", "90"));

		List<TickerDto> ranked = this.ranking.rank(data);

		assertThat(ranked).extracting(TickerDto::instId)
				.containsExactly("BBB-USDT", "AAA-USDT", "CCC-USDT");
		assertThat(ranked.get(0).change24hPct()).isEqualByComparingTo(new BigDecimal("10.00"));
	}

	@Test
	void changePercentIsNullWhenOpenIsZero() {
		ArrayNode data = this.objectMapper.createArrayNode();
		data.add(ticker("ZERO-USDT", "1", "0", "1"));

		assertThat(this.ranking.rank(data).get(0).change24hPct()).isNull();
	}

	@Test
	void capsTheResultAtTwenty() {
		ArrayNode data = this.objectMapper.createArrayNode();
		for (int i = 0; i < 25; i++) {
			data.add(ticker("I" + i + "-USDT", "1", "1", Integer.toString(i)));
		}

		assertThat(this.ranking.rank(data)).hasSize(AppConstants.Okx.TOP_TICKER_COUNT);
		assertThat(this.ranking.rank(data).get(0).instId()).isEqualTo("I24-USDT");
	}

	private ObjectNode ticker(String instId, String last, String open24h, String volCcy24h) {
		ObjectNode node = this.objectMapper.createObjectNode();
		node.put(AppConstants.Json.INST_ID, instId);
		node.put(AppConstants.Json.LAST, last);
		node.put(AppConstants.Json.OPEN_24H, open24h);
		node.put(AppConstants.Json.VOL_CCY_24H, volCcy24h);
		return node;
	}

}
