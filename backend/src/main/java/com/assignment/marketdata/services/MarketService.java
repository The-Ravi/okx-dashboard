package com.assignment.marketdata.services;

import java.util.List;

import org.springframework.stereotype.Service;

import com.assignment.marketdata.httphandlers.OkxRestClient;
import com.assignment.marketdata.model.TickerDto;

/**
 * Serves the cached market overview. Does not fetch OKX itself: that lives on a schedule in the
 * HTTP adapter, and this class only reads what it has already stored.
 */
@Service
public class MarketService {

	private final OkxRestClient okxRestClient;

	public MarketService(OkxRestClient okxRestClient) {
		this.okxRestClient = okxRestClient;
	}

	public List<TickerDto> getOverview() {
		return this.okxRestClient.getTopTickers();
	}

	public boolean isKnownInstrument(String instId) {
		return this.okxRestClient.isKnownInstrument(instId);
	}

}
