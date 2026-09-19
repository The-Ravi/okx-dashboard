package com.assignment.marketdata.controllers;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.assignment.marketdata.model.TickerDto;
import com.assignment.marketdata.services.MarketService;
import com.assignment.marketdata.utility.AppConstants;

/**
 * Market overview endpoint. Deliberately unauthenticated, and served entirely from the cached
 * poll: it never triggers an upstream call. Before the first poll completes the cache is empty and
 * the response is an empty array.
 */
@RestController
public class MarketController {

	private static final Logger logger = LoggerFactory.getLogger(MarketController.class);

	private final MarketService marketService;

	public MarketController(MarketService marketService) {
		this.marketService = marketService;
	}

	@GetMapping(AppConstants.Http.MARKET_OVERVIEW_PATH)
	public ResponseEntity<List<TickerDto>> overview() {
		List<TickerDto> tickers = this.marketService.getOverview();
		logger.debug("Serving market overview with {} tickers", tickers.size());
		return ResponseEntity.ok(tickers);
	}

}
