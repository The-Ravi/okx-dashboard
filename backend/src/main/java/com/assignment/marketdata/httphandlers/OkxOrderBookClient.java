package com.assignment.marketdata.httphandlers;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.assignment.marketdata.enums.ApplyResult;
import com.assignment.marketdata.enums.OkxEvent;
import com.assignment.marketdata.httphandlers.orderbook.InstrumentBook;
import com.assignment.marketdata.httphandlers.orderbook.OkxBooksCodec;
import com.assignment.marketdata.httphandlers.orderbook.OkxWebSocketTransport;
import com.assignment.marketdata.model.OrderBookUpdate;
import com.assignment.marketdata.services.OrderBookFeed;
import com.assignment.marketdata.utility.AppConstants;

/**
 * OKX adapter for {@link OrderBookFeed}: multiplexes every instrument onto one public WebSocket
 * and fans top-of-book windows out to listeners.
 * <p>
 * One physical connection serves the whole process. Interest is reference-counted per instrument:
 * OKX is sent a subscribe only when an instrument gains its first subscriber and an unsubscribe
 * only when it loses its last, which keeps well clear of OKX's cap of 480 subscribe operations per
 * hour per connection.
 * <p>
 * This adapter owns the authoritative book. OKX sends a 400-level snapshot followed by sparse
 * incremental updates, so full depth is maintained here and only the top window is handed to
 * subscribers. Connection lifecycle lives in {@link OkxWebSocketTransport}, wire format in
 * {@link OkxBooksCodec}, and per-instrument depth in {@link InstrumentBook}.
 */
@Component
public class OkxOrderBookClient implements OrderBookFeed {

	private static final Logger logger = LoggerFactory.getLogger(OkxOrderBookClient.class);

	private final OkxBooksCodec codec;

	private final OkxWebSocketTransport transport;

	/** Guards {@link #instruments} and {@link #stopped}. */
	private final Object lock = new Object();

	private final Map<String, SubscribedInstrument> instruments = new HashMap<>();

	private final AtomicLong okxSubscribesSent = new AtomicLong();

	private final AtomicLong okxUnsubscribesSent = new AtomicLong();

	private final AtomicLong pongsReceived = new AtomicLong();

	private final AtomicLong sequenceGaps = new AtomicLong();

	private boolean stopped;

	public OkxOrderBookClient(@Value("${okx.websocket-url}") String websocketUrl,
			ObjectMapper objectMapper) {
		this.codec = new OkxBooksCodec(objectMapper);
		this.transport = new OkxWebSocketTransport(URI.create(websocketUrl), new TransportListener());
	}

	@PostConstruct
	public void start() {
		this.transport.start();
	}

	@PreDestroy
	public void stop() {
		synchronized (this.lock) {
			this.stopped = true;
			this.instruments.clear();
		}
		this.transport.stop();
		logger.info("OKX order book client stopped");
	}

	@Override
	public void subscribe(String instId, Consumer<OrderBookUpdate> listener) {
		int subscribers;
		boolean firstSubscriber;
		synchronized (this.lock) {
			if (this.stopped) {
				return;
			}
			SubscribedInstrument state = this.instruments.computeIfAbsent(instId,
					SubscribedInstrument::new);
			firstSubscriber = state.listeners.isEmpty();
			state.listeners.add(listener);
			subscribers = state.listeners.size();
		}
		if (firstSubscriber) {
			logger.info("First subscriber for {}; sending OKX subscribe", instId);
			this.transport.send(this.codec.subscribe(instId));
			this.okxSubscribesSent.incrementAndGet();
		}
		else {
			logger.info("Subscriber added for {}; {} now interested, reusing the existing OKX "
					+ "subscription", instId, subscribers);
		}
	}

	@Override
	public void unsubscribe(String instId, Consumer<OrderBookUpdate> listener) {
		boolean lastSubscriber = false;
		int subscribers = 0;
		synchronized (this.lock) {
			SubscribedInstrument state = this.instruments.get(instId);
			if (state == null || !state.listeners.remove(listener)) {
				return;
			}
			subscribers = state.listeners.size();
			if (subscribers == 0) {
				// Drops the maintained book along with the entry.
				this.instruments.remove(instId);
				lastSubscriber = true;
			}
		}
		if (lastSubscriber) {
			logger.info("Last subscriber for {} left; sending OKX unsubscribe and dropping book state",
					instId);
			this.transport.send(this.codec.unsubscribe(instId));
			this.okxUnsubscribesSent.incrementAndGet();
		}
		else {
			logger.info("Subscriber removed for {}; {} still interested, keeping the OKX subscription",
					instId, subscribers);
		}
	}

	public int subscriberCount(String instId) {
		synchronized (this.lock) {
			SubscribedInstrument state = this.instruments.get(instId);
			return (state != null) ? state.listeners.size() : 0;
		}
	}

	public Set<String> subscribedInstruments() {
		synchronized (this.lock) {
			return Set.copyOf(this.instruments.keySet());
		}
	}

	public long okxSubscribesSent() {
		return this.okxSubscribesSent.get();
	}

	public long okxUnsubscribesSent() {
		return this.okxUnsubscribesSent.get();
	}

	public long pongsReceived() {
		return this.pongsReceived.get();
	}

	public long connectionsOpened() {
		return this.transport.connectionsOpened();
	}

	public long sequenceGaps() {
		return this.sequenceGaps.get();
	}

	public long staleReconnects() {
		return this.transport.staleReconnects();
	}

	private void onConnected() {
		List<String> wanted;
		synchronized (this.lock) {
			wanted = new ArrayList<>();
			for (SubscribedInstrument state : this.instruments.values()) {
				// The fresh connection will deliver a new snapshot, so the old sequence position
				// must not be carried over.
				state.book.reset();
				if (!state.listeners.isEmpty()) {
					wanted.add(state.instId);
				}
			}
		}
		logger.info("Resubscribing {}", wanted);
		for (String instId : wanted) {
			this.transport.send(this.codec.subscribe(instId));
			this.okxSubscribesSent.incrementAndGet();
		}
	}

	private void onText(String raw) {
		OkxBooksCodec.Frame frame = this.codec.parse(raw);
		if (frame instanceof OkxBooksCodec.Frame.KeepalivePong) {
			this.pongsReceived.incrementAndGet();
			logger.debug("OKX keepalive pong received (total {})", this.pongsReceived.get());
			return;
		}
		if (frame instanceof OkxBooksCodec.Frame.Unparseable unparseable) {
			logger.warn("Unparseable frame from OKX: {}", unparseable.detail());
			return;
		}
		if (frame instanceof OkxBooksCodec.Frame.ControlEvent event) {
			handleEvent(event);
			return;
		}
		if (frame instanceof OkxBooksCodec.Frame.BookData book) {
			handleBookFrame(book.instId(), book.snapshot(), book.book());
		}
	}

	private void handleEvent(OkxBooksCodec.Frame.ControlEvent event) {
		if (OkxEvent.ERROR.matches(event.event())) {
			logger.warn("OKX returned an error event: code={} msg={}", event.code(), event.msg());
		}
		else {
			logger.info("OKX {} acknowledged for {}", event.event(), event.instId());
		}
	}

	private void handleBookFrame(String instId, boolean snapshot, JsonNode book) {
		long seqId = book.path(AppConstants.Json.SEQ_ID).asLong(AppConstants.Okx.UNKNOWN_SEQ_ID);
		long prevSeqId = book.path(AppConstants.Json.PREV_SEQ_ID).asLong(AppConstants.Okx.UNKNOWN_SEQ_ID);
		String ts = book.path(AppConstants.Json.TS).asText(null);
		OrderBookUpdate update = null;
		List<Consumer<OrderBookUpdate>> listeners = List.of();
		boolean resubscribe = false;
		synchronized (this.lock) {
			SubscribedInstrument state = this.instruments.get(instId);
			if (state == null) {
				return;
			}
			ApplyResult result;
			long lastApplied = state.book.lastSeqId();
			if (snapshot) {
				state.book.replace(book, seqId);
				result = ApplyResult.APPLIED;
			}
			else {
				result = state.book.applyDelta(book, seqId, prevSeqId);
			}
			switch (result) {
				case DUPLICATE -> {
					logger.debug("Ignoring duplicate frame for {} at seqId {}", instId, seqId);
					return;
				}
				case AWAITING_SNAPSHOT -> {
					return;
				}
				case SEQUENCE_GAP -> {
					this.sequenceGaps.incrementAndGet();
					logger.warn("Sequence gap for {}: prevSeqId {} does not follow last applied seqId {}; "
							+ "dropping book state and resubscribing", instId, prevSeqId, lastApplied);
					resubscribe = true;
				}
				case APPLIED -> {
					update = new OrderBookUpdate(instId, ts, state.book.bidWindow(),
							state.book.askWindow());
					listeners = List.copyOf(state.listeners);
				}
			}
		}
		if (resubscribe) {
			// Round-trip the subscription to force OKX to send a fresh snapshot.
			this.transport.send(this.codec.unsubscribe(instId));
			this.okxUnsubscribesSent.incrementAndGet();
			this.transport.send(this.codec.subscribe(instId));
			this.okxSubscribesSent.incrementAndGet();
			return;
		}
		// Emitted outside the lock: listeners write to client sockets.
		for (Consumer<OrderBookUpdate> listener : listeners) {
			try {
				listener.accept(update);
			}
			catch (Exception ex) {
				logger.debug("Order book listener for {} failed: {}", instId, ex.toString());
			}
		}
	}

	private final class TransportListener implements OkxWebSocketTransport.Listener {

		@Override
		public void onConnected() {
			OkxOrderBookClient.this.onConnected();
		}

		@Override
		public void onText(String message) {
			OkxOrderBookClient.this.onText(message);
		}

	}

	/**
	 * One instrument's subscribers plus the book maintained for them.
	 */
	private static final class SubscribedInstrument {

		private final String instId;

		private final Set<Consumer<OrderBookUpdate>> listeners = new CopyOnWriteArraySet<>();

		private final InstrumentBook book = new InstrumentBook();

		private SubscribedInstrument(String instId) {
			this.instId = instId;
		}

	}

}
