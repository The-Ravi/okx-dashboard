package com.assignment.marketdata.services;

import java.util.List;

import com.assignment.marketdata.model.TickerDto;

/**
 * Application port for the ranked market overview. How the slice is fetched and ranked is an
 * adapter concern.
 */
public interface TickerSource {

	/**
	 * The cached ranking, empty until the first successful refresh.
	 */
	List<TickerDto> getTopTickers();

	/**
	 * Whether an instrument is part of the cached universe clients are allowed to stream.
	 */
	boolean isKnownInstrument(String instId);

}
