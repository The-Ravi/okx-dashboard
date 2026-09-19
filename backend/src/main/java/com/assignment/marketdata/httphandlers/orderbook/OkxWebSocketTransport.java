package com.assignment.marketdata.httphandlers.orderbook;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.assignment.marketdata.utility.AppConstants;
import com.assignment.marketdata.utility.AppUtils;

/**
 * One reconnecting WebSocket to OKX. Owns connect, backoff, ping/stale detection, send
 * serialisation, and generation so a superseded connection cannot leak. Delivers complete text
 * frames to {@link Listener}; it does not interpret the books protocol.
 */
public final class OkxWebSocketTransport {

	public interface Listener {

		void onConnected();

		void onText(String message);

	}

	private static final Logger logger = LoggerFactory.getLogger(OkxWebSocketTransport.class);

	private final URI websocketUri;

	private final Listener listener;

	private final HttpClient httpClient;

	private final ScheduledExecutorService scheduler;

	private final Object lock = new Object();

	/**
	 * The JDK WebSocket rejects a send issued while a previous one is still in flight, so sends are
	 * chained rather than issued concurrently. Guarded by its own lock so a send never waits on
	 * connection state.
	 */
	private final Object sendLock = new Object();

	private CompletableFuture<Void> sendChain = CompletableFuture.completedFuture(null);

	private final AtomicLong connectionsOpened = new AtomicLong();

	private final AtomicLong staleReconnects = new AtomicLong();

	private WebSocket webSocket;

	private long generation;

	private int failedAttempts;

	private boolean stopped;

	private ScheduledFuture<?> pingTask;

	private volatile long lastFrameReceivedAt;

	public OkxWebSocketTransport(URI websocketUri, Listener listener) {
		this.websocketUri = websocketUri;
		this.listener = listener;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(AppConstants.Okx.WS_CONNECT_TIMEOUT_SECONDS))
				.build();
		this.scheduler = Executors.newSingleThreadScheduledExecutor(
				(runnable) -> AppUtils.newDaemonThread(runnable, AppConstants.Okx.ORDERBOOK_THREAD_NAME));
	}

	public void start() {
		connect();
	}

	public void stop() {
		WebSocket current;
		synchronized (this.lock) {
			this.stopped = true;
			cancelPingTask();
			current = this.webSocket;
			this.webSocket = null;
		}
		if (current != null) {
			current.abort();
		}
		this.scheduler.shutdownNow();
	}

	public void send(String text) {
		WebSocket session;
		synchronized (this.lock) {
			session = this.webSocket;
		}
		sendRaw(session, text);
	}

	public long connectionsOpened() {
		return this.connectionsOpened.get();
	}

	public long staleReconnects() {
		return this.staleReconnects.get();
	}

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
				.connectTimeout(Duration.ofSeconds(AppConstants.Okx.WS_CONNECT_TIMEOUT_SECONDS))
				.buildAsync(this.websocketUri, new FrameAssembler(attemptGeneration))
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
			schedulePing(attemptGeneration, session);
		}
		this.connectionsOpened.incrementAndGet();
		logger.info("OKX order book WebSocket connected (generation {})", attemptGeneration);
		this.listener.onConnected();
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
			delay = AppUtils.reconnectDelayMillis(this.failedAttempts);
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
		long millis = AppConstants.Okx.PING_INTERVAL.toMillis();
		this.pingTask = this.scheduler.scheduleWithFixedDelay(() -> {
			try {
				synchronized (this.lock) {
					if (this.stopped || attemptGeneration != this.generation) {
						return;
					}
				}
				long silentFor = System.currentTimeMillis() - this.lastFrameReceivedAt;
				if (silentFor > AppConstants.Okx.STALE_AFTER_MILLIS) {
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
				sendRaw(session, AppConstants.Okx.PING);
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

	private void sendRaw(WebSocket session, String text) {
		if (session == null) {
			logger.debug("No OKX connection yet; '{}' will be issued once connected", AppUtils.preview(text));
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

	/**
	 * Text frames can arrive in pieces, and a 400-level snapshot reliably does, so parts are
	 * accumulated until the final one. Each assembler is bound to the connection generation that
	 * created it, so callbacks from a replaced connection are ignored rather than acted on.
	 */
	private final class FrameAssembler implements WebSocket.Listener {

		private final long attemptGeneration;

		private final StringBuilder parts = new StringBuilder();

		private FrameAssembler(long attemptGeneration) {
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
				if (isCurrentGeneration()) {
					try {
						OkxWebSocketTransport.this.listener.onText(message);
					}
					catch (Exception ex) {
						logger.warn("Failed to handle an OKX frame: {}", ex.toString());
					}
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

		private boolean isCurrentGeneration() {
			synchronized (OkxWebSocketTransport.this.lock) {
				return !OkxWebSocketTransport.this.stopped
						&& this.attemptGeneration == OkxWebSocketTransport.this.generation;
			}
		}

	}

}
