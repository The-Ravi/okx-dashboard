package com.assignment.marketdata.services;

import java.util.function.Consumer;

import com.assignment.marketdata.model.OrderBookUpdate;

/**
 * Application port for streaming order books. Downstream code depends on this contract, not on a
 * particular venue or transport.
 */
public interface OrderBookFeed {

	/**
	 * Registers interest in an instrument. The first subscriber is what opens the upstream
	 * subscription; later ones ride the existing one.
	 */
	void subscribe(String instId, Consumer<OrderBookUpdate> listener);

	/**
	 * Releases one subscriber's interest. The upstream subscription is dropped only when the last
	 * interested client goes away.
	 */
	void unsubscribe(String instId, Consumer<OrderBookUpdate> listener);

}
