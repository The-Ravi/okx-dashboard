package com.assignment.marketdata.httphandlers;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import com.assignment.marketdata.model.TickerDto;

/**
 * Strategy for turning an OKX ticker payload into the ranked slice we cache. The poller depends on
 * this, not on a particular ranking formula.
 */
public interface TickerRanking {

	List<TickerDto> rank(JsonNode data);

}
