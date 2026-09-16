package com.assignment.marketdata.controllers;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.assignment.marketdata.model.TickerDto;
import com.assignment.marketdata.HttpHandler.OkxRestClient;

/**
 * Market overview endpoint. Deliberately unauthenticated, and served entirely from the cached
 * poll: it never triggers an upstream call. Before the first poll completes the cache is empty and
 * the response is an empty array.
 */
@RestController
public class MarketController {

	private final OkxRestClient okxRestClient;

	public MarketController(OkxRestClient okxRestClient) {
		this.okxRestClient = okxRestClient;
	}

	@GetMapping("/market/overview")
	public List<TickerDto> overview() {
		return this.okxRestClient.getTopTickers();
	}
}
