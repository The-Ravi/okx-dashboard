package com.assignment.marketdata.HttpHandler;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.assignment.marketdata.model.OrderBookUpdate;

/**
 * Single multiplexed connection to OKX's public {@code books} channel.
 * <p>
 * One physical WebSocket serves the whole process. Interest is reference-counted per instrument:
 * OKX is sent a subscribe only when an instrument gains its first subscriber and an unsubscribe
 * only when it loses its last, which keeps well clear of OKX's cap of 480 subscribe operations per
 * hour per connection.
 * <p>
 * This client owns the authoritative book. OKX sends a 400-level snapshot followed by sparse
 * incremental updates, so full depth is maintained here and only the top window is handed to
 * subscribers.
 */
@Component
public class OkxOrderBookClient {

	private static final Logger logger = LoggerFactory.getLogger(OkxOrderBookClient.class);

	private static final int WINDOW_DEPTH = 15;

	private static final String BOOKS_CHANNEL = "books";

	/** OKX drops a connection idle for 30 seconds, and never pings us first. */
	private static final Duration PING_INTERVAL = Duration.ofSeconds(20);

	private static final String PING = "ping";

	private static final String PONG = "pong";

	private static final long MIN_BACKOFF_MILLIS = 1000L;

	private static final long MAX_BACKOFF_MILLIS = 30_000L;

	/**
	 * A connection that has produced nothing for this long is treated as dead. Pings go out every
	 * 20 seconds and OKX answers promptly, so silence for more than two intervals means the socket
	 * is black-holing rather than idle. Without this, a connection that stops delivering without
	 * ever being reset would never be noticed, since nothing else reads from it.
	 */
	private static final long STALE_AFTER_MILLIS = 45_000L;

	private final URI websocketUri;

	private final ObjectMapper objectMapper;

	private final HttpClient httpClient;

	private final ScheduledExecutorService scheduler;

	/** Guards {@link #instruments}, the book state inside it, and the connection fields. */
	private final Object lock = new Object();

	private final Map<String, InstrumentState> instruments = new HashMap<>();

	private WebSocket webSocket;

	private long generation;

	private int failedAttempts;

	private boolean stopped;

	private ScheduledFuture<?> pingTask;

	/**
	 * The JDK WebSocket rejects a send issued while a previous one is still in flight, so sends are
	 * chained rather than issued concurrently. Guarded by its own lock so a send never waits on
	 * book state.
	 */
	private final Object sendLock = new Object();

	private CompletableFuture<Void> sendChain = CompletableFuture.completedFuture(null);

	private final AtomicLong okxSubscribesSent = new AtomicLong();

	private final AtomicLong okxUnsubscribesSent = new AtomicLong();

	private final AtomicLong pongsReceived = new AtomicLong();

	private final AtomicLong connectionsOpened = new AtomicLong();

	private final AtomicLong sequenceGaps = new AtomicLong();

	private final AtomicLong staleReconnects = new AtomicLong();

	private volatile long lastFrameReceivedAt;

	public OkxOrderBookClient(@Value("${okx.websocket-url}") String websocketUrl,
			ObjectMapper objectMapper) {
		this.websocketUri = URI.create(websocketUrl);
		this.objectMapper = objectMapper;
		this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		this.scheduler = Executors.newSingleThreadScheduledExecutor((runnable) -> {
			Thread thread = new Thread(runnable, "okx-orderbook");
			thread.setDaemon(true);
			return thread;
		});
	}

	@PostConstruct
	public void start() {
		connect();
	}

	@PreDestroy
	public void stop() {
		WebSocket current;
		synchronized (this.lock) {
			this.stopped = true;
			cancelPingTask();
			current = this.webSocket;
			this.webSocket = null;
			this.instruments.clear();
		}
		if (current != null) {
			current.abort();
		}
		this.scheduler.shutdownNow();
		logger.info("OKX order book client stopped");
	}

	/**
	 * Registers interest in an instrument. The first subscriber triggers the upstream subscribe;
	 * later ones ride the existing one.
	 */
	public void subscribe(String instId, Consumer<OrderBookUpdate> listener) {
		int subscribers;
		boolean firstSubscriber;
		WebSocket target;
		synchronized (this.lock) {
			if (this.stopped) {
				return;
			}
			InstrumentState state = this.instruments.computeIfAbsent(instId, InstrumentState::new);
			firstSubscriber = state.listeners.isEmpty();
			state.listeners.add(listener);
			subscribers = state.listeners.size();
			target = this.webSocket;
		}
		if (firstSubscriber) {
			logger.info("First subscriber for {}; sending OKX subscribe", instId);
			sendChannelOp(target, "subscribe", instId);
			this.okxSubscribesSent.incrementAndGet();
		}
		else {
			logger.info("Subscriber added for {}; {} now interested, reusing the existing OKX "
					+ "subscription", instId, subscribers);
		}
	}

	/**
	 * Releases one subscriber's interest. The upstream subscription is dropped, along with the book
	 * state, only when the last interested client goes away.
	 */
	public void unsubscribe(String instId, Consumer<OrderBookUpdate> listener) {
		boolean lastSubscriber = false;
		int subscribers = 0;
		WebSocket target;
		synchronized (this.lock) {
			InstrumentState state = this.instruments.get(instId);
			if (state == null || !state.listeners.remove(listener)) {
				return;
			}
			subscribers = state.listeners.size();
			if (subscribers == 0) {
				// Drops the maintained book along with the entry.
				this.instruments.remove(instId);
				lastSubscriber = true;
			}
			target = this.webSocket;
		}
		if (lastSubscriber) {
			logger.info("Last subscriber for {} left; sending OKX unsubscribe and dropping book state",
					instId);
			sendChannelOp(target, "unsubscribe", instId);
			this.okxUnsubscribesSent.incrementAndGet();
		}
		else {
			logger.info("Subscriber removed for {}; {} still interested, keeping the OKX subscription",
					instId, subscribers);
		}
	}

	public int subscriberCount(String instId) {
		synchronized (this.lock) {
			InstrumentState state = this.instruments.get(instId);
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
		return this.connectionsOpened.get();
	}

	public long sequenceGaps() {
		return this.sequenceGaps.get();
	}

	public long staleReconnects() {
		return this.staleReconnects.get();
	}

	// --- connection lifecycle ---

	private void connect() {
		long attemptGeneration;
		synchronized (this.lock) {
			if (this.stopped) {
				return;
			}
			attemptGeneration = ++this.generation;
		}
		logger.info("Connecting to OKX order book WebSocket {} (generation {})", this.websocketUri,
				attemptGeneration);
		this.httpClient.newWebSocketBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.buildAsync(this.websocketUri, new OkxListener(attemptGeneration))
				.whenComplete((session, error) -> {
					if (error != null) {
						scheduleReconnect(attemptGeneration, "connect failed: " + error);
					}
					else {
						onConnected(attemptGeneration, session);
					}
				});
	}

	private void onConnected(long attemptGeneration, WebSocket session) {
		List<String> wanted;
		synchronized (this.lock) {
			// A connection from a superseded attempt must not be adopted, or it would leak and
			// double-subscribe alongside the current one.
			if (this.stopped || attemptGeneration != this.generation) {
				session.abort();
				logger.debug("Discarding stale OKX connection from generation {}", attemptGeneration);
				return;
			}
			this.webSocket = session;
			this.failedAttempts = 0;
			// Counts as activity, so a fresh connection is not judged stale before its first frame.
			this.lastFrameReceivedAt = System.currentTimeMillis();
			wanted = new ArrayList<>();
			for (InstrumentState state : this.instruments.values()) {
				// The fresh connection will deliver a new snapshot, so the old sequence position
				// must not be carried over.
				state.resetBook();
				if (!state.listeners.isEmpty()) {
					wanted.add(state.instId);
				}
			}
			schedulePing(attemptGeneration, session);
		}
		this.connectionsOpened.incrementAndGet();
		logger.info("OKX order book WebSocket connected (generation {}); resubscribing {}",
				attemptGeneration, wanted);
		for (String instId : wanted) {
			sendChannelOp(session, "subscribe", instId);
			this.okxSubscribesSent.incrementAndGet();
		}
	}

	private void scheduleReconnect(long attemptGeneration, String reason) {
		long delay;
		synchronized (this.lock) {
			if (this.stopped || attemptGeneration != this.generation) {
				// A newer attempt already superseded this one; its own callbacks own the retry.
				return;
			}
			cancelPingTask();
			this.webSocket = null;
			this.failedAttempts++;
			long exponential = Math.min(MAX_BACKOFF_MILLIS,
					MIN_BACKOFF_MILLIS * (1L << Math.min(this.failedAttempts - 1, 5)));
			// Jitter so repeated failures do not retry in lockstep.
			delay = MIN_BACKOFF_MILLIS / 2 + ThreadLocalRandom.current().nextLong(exponential);
			delay = Math.min(delay, MAX_BACKOFF_MILLIS);
		}
		logger.warn("OKX order book WebSocket unavailable ({}); reconnecting in {} ms", reason, delay);
		try {
			this.scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
		}
		catch (Exception ex) {
			logger.warn("Could not schedule an OKX reconnect: {}", ex.toString());
		}
	}

	private void schedulePing(long attemptGeneration, WebSocket session) {
		cancelPingTask();
		long millis = PING_INTERVAL.toMillis();
		this.pingTask = this.scheduler.scheduleWithFixedDelay(() -> {
			try {
				synchronized (this.lock) {
					if (this.stopped || attemptGeneration != this.generation) {
						return;
					}
				}
				long silentFor = System.currentTimeMillis() - this.lastFrameReceivedAt;
				if (silentFor > STALE_AFTER_MILLIS) {
					// Nothing has come back, not even a pong, so the connection is dead even
					// though the socket still looks open. Tear it down and start over.
					this.staleReconnects.incrementAndGet();
					logger.warn("No frames from OKX for {} ms; treating the connection as dead",
							silentFor);
					session.abort();
					scheduleReconnect(attemptGeneration, "no traffic for " + silentFor + " ms");
					return;
				}
				// OKX expects the literal text "ping", not a WebSocket control frame.
				sendRaw(session, PING);
			}
			catch (Exception ex) {
				logger.debug("Keepalive ping failed: {}", ex.toString());
			}
		}, millis, millis, TimeUnit.MILLISECONDS);
	}

	private void cancelPingTask() {
		if (this.pingTask != null) {
			this.pingTask.cancel(false);
			this.pingTask = null;
		}
	}

	// --- sending ---

	private void sendChannelOp(WebSocket session, String op, String instId) {
		ObjectNode arg = this.objectMapper.createObjectNode();
		arg.put("channel", BOOKS_CHANNEL);
		arg.put("instId", instId);
		ArrayNode args = this.objectMapper.createArrayNode();
		args.add(arg);
		ObjectNode frame = this.objectMapper.createObjectNode();
		frame.put("op", op);
		frame.set("args", args);
		sendRaw(session, frame.toString());
	}

	private void sendRaw(WebSocket session, String text) {
		if (session == null) {
			logger.debug("No OKX connection yet; '{}' will be issued once connected",
					text.length() > 40 ? text.substring(0, 40) : text);
			return;
		}
		synchronized (this.sendLock) {
			this.sendChain = this.sendChain
					.thenCompose((ignored) -> session.sendText(text, true).thenAccept((ws) -> {
					}))
					.exceptionally((ex) -> {
						logger.debug("OKX send failed: {}", ex.toString());
						return null;
					});
		}
	}

	// --- inbound frames ---

	private void handleMessage(long attemptGeneration, String raw) {
		if (PONG.equals(raw)) {
			this.pongsReceived.incrementAndGet();
			logger.debug("OKX keepalive pong received (total {})", this.pongsReceived.get());
			return;
		}
		JsonNode root;
		try {
			root = this.objectMapper.readTree(raw);
		}
		catch (Exception ex) {
			logger.warn("Unparseable frame from OKX: {}", ex.toString());
			return;
		}
		if (root.hasNonNull("event")) {
			handleEvent(root);
			return;
		}
		JsonNode arg = root.path("arg");
		if (!BOOKS_CHANNEL.equals(arg.path("channel").asText())) {
			return;
		}
		String instId = arg.path("instId").asText(null);
		JsonNode data = root.path("data");
		if (instId == null || !data.isArray() || data.isEmpty()) {
			return;
		}
		handleBookFrame(attemptGeneration, instId, "snapshot".equals(root.path("action").asText()),
				data.get(0));
	}

	private void handleEvent(JsonNode root) {
		String event = root.path("event").asText();
		if ("error".equals(event)) {
			logger.warn("OKX returned an error event: code={} msg={}", root.path("code").asText(),
					root.path("msg").asText());
		}
		else {
			logger.info("OKX {} acknowledged for {}", event, root.path("arg").path("instId").asText());
		}
	}

	private void handleBookFrame(long attemptGeneration, String instId, boolean snapshot, JsonNode book) {
		long seqId = book.path("seqId").asLong(-1);
		long prevSeqId = book.path("prevSeqId").asLong(-1);
		String ts = book.path("ts").asText(null);
		OrderBookUpdate update = null;
		List<Consumer<OrderBookUpdate>> listeners = List.of();
		boolean resubscribe = false;
		synchronized (this.lock) {
			InstrumentState state = this.instruments.get(instId);
			if (state == null || attemptGeneration != this.generation) {
				return;
			}
			if (snapshot) {
				state.replace(book);
				state.lastSeqId = seqId;
			}
			else if (state.lastSeqId < 0) {
				// No snapshot applied yet on this connection; wait for one.
				return;
			}
			else if (seqId == prevSeqId) {
				logger.debug("Ignoring duplicate frame for {} at seqId {}", instId, seqId);
				return;
			}
			else if (prevSeqId != state.lastSeqId) {
				this.sequenceGaps.incrementAndGet();
				logger.warn("Sequence gap for {}: prevSeqId {} does not follow last applied seqId {}; "
						+ "dropping book state and resubscribing", instId, prevSeqId, state.lastSeqId);
				state.resetBook();
				resubscribe = true;
			}
			else {
				state.apply(book);
				state.lastSeqId = seqId;
			}
			if (!resubscribe) {
				update = new OrderBookUpdate(instId, ts, state.window(state.bids),
						state.window(state.asks));
				listeners = List.copyOf(state.listeners);
			}
		}
		if (resubscribe) {
			// Round-trip the subscription to force OKX to send a fresh snapshot.
			WebSocket session = currentWebSocket();
			sendChannelOp(session, "unsubscribe", instId);
			this.okxUnsubscribesSent.incrementAndGet();
			sendChannelOp(session, "subscribe", instId);
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

	private WebSocket currentWebSocket() {
		synchronized (this.lock) {
			return this.webSocket;
		}
	}

	/**
	 * Full-depth book for one instrument plus its subscribers. Prices key the maps as
	 * {@link BigDecimal} so ordering is numeric, while the values keep OKX's original strings so
	 * nothing is ever reformatted on the way out.
	 */
	private static final class InstrumentState {

		private final String instId;

		private final Set<Consumer<OrderBookUpdate>> listeners = new CopyOnWriteArraySet<>();

		private final NavigableMap<BigDecimal, Level> bids = new TreeMap<>(Comparator.reverseOrder());

		private final NavigableMap<BigDecimal, Level> asks = new TreeMap<>();

		private long lastSeqId = -1;

		private InstrumentState(String instId) {
			this.instId = instId;
		}

		private void resetBook() {
			this.bids.clear();
			this.asks.clear();
			this.lastSeqId = -1;
		}

		private void replace(JsonNode book) {
			this.bids.clear();
			this.asks.clear();
			apply(book);
		}

		private void apply(JsonNode book) {
			applySide(this.bids, book.path("bids"));
			applySide(this.asks, book.path("asks"));
		}

		private static void applySide(NavigableMap<BigDecimal, Level> side, JsonNode levels) {
			if (!levels.isArray()) {
				return;
			}
			for (JsonNode level : levels) {
				// OKX ships [price, size, deprecated, orderCount]; only the first two matter.
				if (!level.isArray() || level.size() < 2) {
					continue;
				}
				String price = level.get(0).asText();
				String size = level.get(1).asText();
				BigDecimal key;
				BigDecimal quantity;
				try {
					key = new BigDecimal(price);
					quantity = new BigDecimal(size);
				}
				catch (NumberFormatException ex) {
					continue;
				}
				if (quantity.signum() == 0) {
					side.remove(key);
				}
				else {
					side.put(key, new Level(price, size));
				}
			}
		}

		private List<List<String>> window(NavigableMap<BigDecimal, Level> side) {
			List<List<String>> window = new ArrayList<>(WINDOW_DEPTH);
			for (Level level : side.values()) {
				if (window.size() == WINDOW_DEPTH) {
					break;
				}
				window.add(List.of(level.price(), level.size()));
			}
			return List.copyOf(window);
		}
	}

	private record Level(String price, String size) {
	}

	/**
	 * Text frames can arrive in pieces, and a 400-level snapshot reliably does, so parts are
	 * accumulated until the final one. Each listener is bound to the connection generation that
	 * created it, so callbacks from a replaced connection are ignored rather than acted on.
	 */
	private final class OkxListener implements WebSocket.Listener {

		private final long attemptGeneration;

		private final StringBuilder parts = new StringBuilder();

		private OkxListener(long attemptGeneration) {
			this.attemptGeneration = attemptGeneration;
		}

		@Override
		public void onOpen(WebSocket session) {
			session.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket session, CharSequence data, boolean last) {
			lastFrameReceivedAt = System.currentTimeMillis();
			this.parts.append(data);
			if (last) {
				String message = this.parts.toString();
				this.parts.setLength(0);
				try {
					handleMessage(this.attemptGeneration, message);
				}
				catch (Exception ex) {
					logger.warn("Failed to handle an OKX frame: {}", ex.toString());
				}
			}
			session.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket session, int statusCode, String reason) {
			scheduleReconnect(this.attemptGeneration, "closed by peer: " + statusCode + " " + reason);
			return null;
		}

		@Override
		public void onError(WebSocket session, Throwable error) {
			scheduleReconnect(this.attemptGeneration, "transport error: " + error);
		}
	}
}
