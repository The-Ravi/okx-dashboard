package com.assignment.marketdata.gateway;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.assignment.marketdata.model.OrderBookUpdate;
import com.assignment.marketdata.HttpHandler.OkxOrderBookClient;

/**
 * One client connection's order book interests, and the thing that guarantees they are given back.
 * <p>
 * Whatever ends a connection - a clean close, a transport error, or the single-session kick -
 * {@link #releaseAll()} runs and decrements every upstream reference this client held. Release is
 * idempotent because more than one of those paths can fire for the same connection: the supersede
 * kick kicks off a close callback for the very socket it just closed.
 */
final class ClientSubscriptions {

	private final OkxOrderBookClient orderBookClient;

	private final Map<String, Consumer<OrderBookUpdate>> listenersByInstId = new ConcurrentHashMap<>();

	private final AtomicBoolean released = new AtomicBoolean();

	ClientSubscriptions(OkxOrderBookClient orderBookClient) {
		this.orderBookClient = orderBookClient;
	}

	/**
	 * Adds interest in an instrument, ignoring a repeat subscribe for one already held so a client
	 * cannot inflate the upstream reference count.
	 * @return whether this call created new interest
	 */
	boolean add(String instId, Consumer<OrderBookUpdate> listener) {
		if (this.released.get()) {
			return false;
		}
		if (this.listenersByInstId.putIfAbsent(instId, listener) != null) {
			return false;
		}
		this.orderBookClient.subscribe(instId, listener);
		return true;
	}

	/**
	 * @return whether interest was held and has now been dropped
	 */
	boolean remove(String instId) {
		Consumer<OrderBookUpdate> listener = this.listenersByInstId.remove(instId);
		if (listener == null) {
			return false;
		}
		this.orderBookClient.unsubscribe(instId, listener);
		return true;
	}

	/**
	 * Drops every interest this client holds.
	 * @return the instruments that were released
	 */
	List<String> releaseAll() {
		// Flagged before draining so a subscribe racing with the close cannot slip in behind it.
		this.released.set(true);
		List<String> released = List.copyOf(this.listenersByInstId.keySet());
		for (String instId : released) {
			remove(instId);
		}
		return released;
	}

	List<String> current() {
		return List.copyOf(this.listenersByInstId.keySet());
	}
}
