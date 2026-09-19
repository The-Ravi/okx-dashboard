package com.assignment.marketdata.services;

import java.util.List;

import org.springframework.stereotype.Service;

import com.assignment.marketdata.model.TickerDto;

/**
 * Serves the cached market overview. Does not fetch OKX itself: that lives on a schedule in the
 * HTTP adapter, and this class only reads what {@link TickerSource} has already stored.
 */
@Service
public class MarketService {

	private final TickerSource tickerSource;

	public MarketService(TickerSource tickerSource) {
		this.tickerSource = tickerSource;
	}

	public List<TickerDto> getOverview() {
		return this.tickerSource.getTopTickers();
	}

	public boolean isKnownInstrument(String instId) {
		return this.tickerSource.isKnownInstrument(instId);
	}

}
