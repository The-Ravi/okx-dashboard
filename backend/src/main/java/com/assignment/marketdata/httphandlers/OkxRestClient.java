package com.assignment.marketdata.httphandlers;

import java.time.Duration;
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
import com.assignment.marketdata.services.TickerSource;
import com.assignment.marketdata.utility.AppConstants;
import com.assignment.marketdata.utility.AppUtils;

/**
 * Polls OKX's public spot ticker endpoint on a schedule and keeps the ranked top slice in memory.
 * <p>
 * The upstream response covers every spot instrument OKX lists, which is on the order of 1400
 * entries and some 450 KB per call, so it is fetched on a timer and served from cache rather than
 * per incoming request. Ranking is delegated to {@link TickerRanking}.
 */
@Component
public class OkxRestClient implements TickerSource {

	private static final Logger logger = LoggerFactory.getLogger(OkxRestClient.class);

	/**
	 * Read from request threads, replaced wholesale by the scheduler thread. Always an immutable
	 * list, so readers can never observe a half-built or mutated snapshot.
	 */
	private volatile List<TickerDto> topTickers = List.of();

	private final RestClient restClient;

	private final TickerRanking tickerRanking;

	public OkxRestClient(@Value("${okx.rest-base-url}") String restBaseUrl,
			TickerRanking tickerRanking) {
		// Without explicit timeouts a stalled connection would occupy the single-threaded
		// scheduler indefinitely and no further poll would ever run.
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(AppConstants.Okx.HTTP_CONNECT_TIMEOUT_SECONDS));
		requestFactory.setReadTimeout(Duration.ofSeconds(AppConstants.Okx.HTTP_READ_TIMEOUT_SECONDS));
		this.restClient = RestClient.builder().baseUrl(restBaseUrl).requestFactory(requestFactory).build();
		this.tickerRanking = tickerRanking;
	}

	@Override
	public List<TickerDto> getTopTickers() {
		return this.topTickers;
	}

	/**
	 * Whether an instrument is part of the cached universe clients are allowed to stream. Nothing
	 * is known until the first poll lands, a window of a few hundred milliseconds after startup.
	 */
	@Override
	public boolean isKnownInstrument(String instId) {
		return AppUtils.isKnownInstrument(this.topTickers, instId);
	}

	/**
	 * fixedDelay rather than fixedRate: a slow upstream call delays the next poll instead of
	 * letting invocations pile up behind it.
	 */
	@Scheduled(initialDelay = AppConstants.Okx.TICKER_POLL_INITIAL_DELAY_MS,
			fixedDelay = AppConstants.Okx.TICKER_POLL_DELAY_MS)
	public void pollTickers() {
		try {
			JsonNode response = this.restClient.get().uri(AppConstants.Okx.TICKERS_PATH).retrieve()
					.body(JsonNode.class);
			if (response == null) {
				logger.warn("OKX ticker poll returned an empty body; keeping the previous cache");
				return;
			}
			String code = response.path(AppConstants.Json.CODE).asText();
			JsonNode data = response.path(AppConstants.Json.DATA);
			if (!AppConstants.Okx.SUCCESS_CODE.equals(code) || !data.isArray()) {
				logger.warn("OKX ticker poll returned code '{}'; keeping the previous cache", code);
				return;
			}
			List<TickerDto> ranked = this.tickerRanking.rank(data);
			this.topTickers = ranked;
			logger.info("OKX ticker fetch: {} instruments received, cached top {}", data.size(),
					ranked.size());
		}
		catch (Exception ex) {
			// Serving slightly stale data beats blanking the client's table on a transient blip.
			logger.warn("OKX ticker poll failed, keeping the previous cache: {}", ex.toString());
		}
	}

}
