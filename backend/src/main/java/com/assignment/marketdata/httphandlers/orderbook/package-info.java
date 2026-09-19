/**
 * Internal pieces of the OKX order-book adapter: full-depth book state, wire codec, and the
 * reconnecting WebSocket transport. Application code talks to {@link
 * com.assignment.marketdata.services.OrderBookFeed}, not these types.
 */
package com.assignment.marketdata.httphandlers.orderbook;
