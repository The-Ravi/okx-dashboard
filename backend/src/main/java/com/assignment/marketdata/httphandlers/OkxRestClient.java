package com.assignment.marketdata.httphandlers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.assignment.marketdata.model.TickerDto;

/**
 * Polls OKX's public spot ticker endpoint on a schedule and keeps the ranked top slice in memory.
 * <p>
 * The upstream response covers every spot instrument OKX lists, which is on the order of 1400
 * entries and some 450 KB per call, so it is fetched on a timer and served from cache rather than
 * per incoming request.
 */
@Component
public class OkxRestClient {

	private static final Logger logger = LoggerFactory.getLogger(OkxRestClient.class);

	private static final String TICKERS_PATH = "/api/v5/market/tickers?instType=SPOT";

	private static final String SUCCESS_CODE = "0";

	private static final int TOP_N = 20;

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	/**
	 * Read from request threads, replaced wholesale by the scheduler thread. Always an immutable
	 * list, so readers can never observe a half-built or mutated snapshot.
	 */
	private volatile List<TickerDto> topTickers = List.of();

	private final RestClient restClient;

	public OkxRestClient(@Value("${okx.rest-base-url}") String restBaseUrl) {
		// Without explicit timeouts a stalled connection would occupy the single-threaded
		// scheduler indefinitely and no further poll would ever run.
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(3));
		requestFactory.setReadTimeout(Duration.ofSeconds(5));
		this.restClient = RestClient.builder().baseUrl(restBaseUrl).requestFactory(requestFactory).build();
	}

	/**
	 * The cached ranking, empty until the first poll succeeds.
	 */
	public List<TickerDto> getTopTickers() {
		return this.topTickers;
	}

	/**
	 * Whether an instrument is part of the cached universe clients are allowed to stream. Nothing
	 * is known until the first poll lands, a window of a few hundred milliseconds after startup.
	 */
	public boolean isKnownInstrument(String instId) {
		if (instId == null) {
			return false;
		}
		for (TickerDto ticker : this.topTickers) {
			if (instId.equals(ticker.instId())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * fixedDelay rather than fixedRate: a slow upstream call delays the next poll instead of
	 * letting invocations pile up behind it.
	 */
	@Scheduled(initialDelay = 0, fixedDelay = 5000)
	public void pollTickers() {
		try {
			JsonNode response = this.restClient.get().uri(TICKERS_PATH).retrieve().body(JsonNode.class);
			if (response == null) {
				logger.warn("OKX ticker poll returned an empty body; keeping the previous cache");
				return;
			}
			String code = response.path("code").asText();
			JsonNode data = response.path("data");
			if (!SUCCESS_CODE.equals(code) || !data.isArray()) {
				logger.warn("OKX ticker poll returned code '{}'; keeping the previous cache", code);
				return;
			}
			List<TickerDto> ranked = rankTopTickers(data);
			this.topTickers = ranked;
			logger.info("OKX ticker fetch: {} instruments received, cached top {}", data.size(),
					ranked.size());
		}
		catch (Exception ex) {
			// Serving slightly stale data beats blanking the client's table on a transient blip.
			logger.warn("OKX ticker poll failed, keeping the previous cache: {}", ex.toString());
		}
	}

	/**
	 * Ranks every spot instrument by quote-currency notional and keeps the leaders.
	 * <p>
	 * Ranking spans all quote currencies, as the contract specifies. Because {@code volCcy24h} is
	 * denominated in each pair's own quote currency, the result legitimately mixes them and pairs
	 * quoted in other currencies can outrank larger USDT pairs.
	 */
	private static List<TickerDto> rankTopTickers(JsonNode data) {
		List<RankedTicker> ranked = new ArrayList<>(data.size());
		for (JsonNode node : data) {
			String instId = textOrNull(node, "instId");
			if (instId == null) {
				continue;
			}
			String quoteVolume = textOrNull(node, "volCcy24h");
			String last = textOrNull(node, "last");
			TickerDto ticker = new TickerDto(instId, last, changePercent(last, textOrNull(node, "open24h")),
					quoteVolume);
			ranked.add(new RankedTicker(toBigDecimalOrZero(quoteVolume), ticker));
		}
		// Numeric comparison: volCcy24h arrives as a string, and sorting it lexicographically would
		// rank by leading digit rather than magnitude.
		ranked.sort(Comparator.comparing(RankedTicker::quoteVolume).reversed());
		List<TickerDto> top = new ArrayList<>(Math.min(TOP_N, ranked.size()));
		for (RankedTicker entry : ranked.subList(0, Math.min(TOP_N, ranked.size()))) {
			top.add(entry.ticker());
		}
		return List.copyOf(top);
	}

	/**
	 * {@code ((last - open24h) / open24h) * 100} to two decimal places, or null when any input is
	 * missing, unparseable, or would divide by zero. Null keeps the response valid JSON, which
	 * Infinity and NaN would not.
	 */
	private static BigDecimal changePercent(String last, String open24h) {
		if (last == null || open24h == null) {
			return null;
		}
		try {
			BigDecimal open = new BigDecimal(open24h);
			if (open.signum() == 0) {
				return null;
			}
			// Multiplying before dividing keeps this to a single rounding step.
			return new BigDecimal(last).subtract(open).multiply(HUNDRED).divide(open, 2, RoundingMode.HALF_UP);
		}
		catch (NumberFormatException | ArithmeticException ex) {
			return null;
		}
	}

	private static BigDecimal toBigDecimalOrZero(String value) {
		if (value == null) {
			return BigDecimal.ZERO;
		}
		try {
			return new BigDecimal(value);
		}
		catch (NumberFormatException ex) {
			// Rank it last rather than failing the whole poll.
			return BigDecimal.ZERO;
		}
	}

	private static String textOrNull(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || !value.isTextual() || value.asText().isEmpty()) {
			return null;
		}
		return value.asText();
	}

	private record RankedTicker(BigDecimal quoteVolume, TickerDto ticker) {
	}
}
