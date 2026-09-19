package com.assignment.marketdata.httphandlers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import com.assignment.marketdata.model.TickerDto;
import com.assignment.marketdata.utility.AppConstants;
import com.assignment.marketdata.utility.AppUtils;

/**
 * Turns an OKX ticker payload into the ranked top slice. Isolated from HTTP so ranking can change
 * without touching the poller, and so it can be tested with fixtures rather than a live call.
 */
@Component
public class OkxTickerRanking implements TickerRanking {

	/**
	 * Ranks every spot instrument by quote-currency notional and keeps the leaders.
	 * <p>
	 * Ranking spans all quote currencies, as the contract specifies. Because {@code volCcy24h} is
	 * denominated in each pair's own quote currency, the result legitimately mixes them and pairs
	 * quoted in other currencies can outrank larger USDT pairs.
	 */
	@Override
	public List<TickerDto> rank(JsonNode data) {
		List<RankedTicker> ranked = new ArrayList<>(data.size());
		for (JsonNode node : data) {
			String instId = AppUtils.textOrNull(node, AppConstants.Json.INST_ID);
			if (instId == null) {
				continue;
			}
			String quoteVolume = AppUtils.textOrNull(node, AppConstants.Json.VOL_CCY_24H);
			String last = AppUtils.textOrNull(node, AppConstants.Json.LAST);
			TickerDto ticker = new TickerDto(instId, last,
					AppUtils.changePercent(last, AppUtils.textOrNull(node, AppConstants.Json.OPEN_24H)),
					quoteVolume);
			ranked.add(new RankedTicker(AppUtils.toBigDecimalOrZero(quoteVolume), ticker));
		}
		// Numeric comparison: volCcy24h arrives as a string, and sorting it lexicographically would
		// rank by leading digit rather than magnitude.
		ranked.sort(Comparator.comparing(RankedTicker::quoteVolume).reversed());
		int topN = AppConstants.Okx.TOP_TICKER_COUNT;
		List<TickerDto> top = new ArrayList<>(Math.min(topN, ranked.size()));
		for (RankedTicker entry : ranked.subList(0, Math.min(topN, ranked.size()))) {
			top.add(entry.ticker());
		}
		return List.copyOf(top);
	}

	private record RankedTicker(BigDecimal quoteVolume, TickerDto ticker) {
	}

}
