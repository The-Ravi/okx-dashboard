package com.assignment.marketdata.utility;

import java.time.Duration;

/**
 * Shared literals used across adapters, services, and the session WebSocket. Values that belong on
 * the wire or in HTTP mappings live here so they are defined once.
 */
public final class AppConstants {

	private AppConstants() {
	}

	public static final class Http {

		public static final String AUTH_BASE = "/auth";

		public static final String LOGIN_PATH = "/login";

		public static final String MARKET_OVERVIEW_PATH = "/market/overview";

		public static final String ALLOWED_ORIGIN_PATTERN = "*";

		private Http() {
		}

	}

	public static final class WebSocket {

		public static final String SESSION_PATH = "/ws/session";

		public static final String TOKEN_QUERY_PARAM = "token";

		public static final String USER_ID_ATTRIBUTE = "marketdata.userId";

		public static final String SUBSCRIPTIONS_ATTRIBUTE = "marketdata.subscriptions";

		public static final String SESSION_TERMINATED_FRAME = "{\"type\":\"session_terminated\"}";

		public static final int CLOSE_CODE_INVALID_TOKEN = 4001;

		public static final String CLOSE_REASON_INVALID_TOKEN = "invalid token";

		public static final int CLOSE_CODE_SUPERSEDED = 4000;

		public static final String CLOSE_REASON_SUPERSEDED = "superseded";

		private WebSocket() {
		}

	}

	public static final class Json {

		public static final String OP = "op";

		public static final String INST_ID = "instId";

		public static final String CHANNEL = "channel";

		public static final String ARGS = "args";

		public static final String EVENT = "event";

		public static final String CODE = "code";

		public static final String MSG = "msg";

		public static final String ARG = "arg";

		public static final String DATA = "data";

		public static final String ACTION = "action";

		public static final String BIDS = "bids";

		public static final String ASKS = "asks";

		public static final String SEQ_ID = "seqId";

		public static final String PREV_SEQ_ID = "prevSeqId";

		public static final String TS = "ts";

		public static final String LAST = "last";

		public static final String OPEN_24H = "open24h";

		public static final String VOL_CCY_24H = "volCcy24h";

		private Json() {
		}

	}

	public static final class Okx {

		public static final String BOOKS_CHANNEL = "books";

		public static final String PING = "ping";

		public static final String PONG = "pong";

		public static final String TICKERS_PATH = "/api/v5/market/tickers?instType=SPOT";

		public static final String SUCCESS_CODE = "0";

		public static final int TOP_TICKER_COUNT = 20;

		public static final int ORDER_BOOK_WINDOW_DEPTH = 15;

		public static final long UNKNOWN_SEQ_ID = -1L;

		public static final int LEVEL_PRICE_INDEX = 0;

		public static final int LEVEL_SIZE_INDEX = 1;

		public static final int MIN_LEVEL_FIELDS = 2;

		public static final int CHANGE_PERCENT_SCALE = 2;

		public static final int PERCENT_MULTIPLIER = 100;

		public static final long TICKER_POLL_INITIAL_DELAY_MS = 0L;

		public static final long TICKER_POLL_DELAY_MS = 5000L;

		public static final int HTTP_CONNECT_TIMEOUT_SECONDS = 3;

		public static final int HTTP_READ_TIMEOUT_SECONDS = 5;

		public static final int WS_CONNECT_TIMEOUT_SECONDS = 10;

		public static final Duration PING_INTERVAL = Duration.ofSeconds(20);

		public static final long MIN_BACKOFF_MILLIS = 1000L;

		public static final long MAX_BACKOFF_MILLIS = 30_000L;

		public static final int BACKOFF_SHIFT_CAP = 5;

		public static final long STALE_AFTER_MILLIS = 45_000L;

		public static final int SEND_LOG_PREVIEW_LENGTH = 40;

		public static final String ORDERBOOK_THREAD_NAME = "okx-orderbook";

		private Okx() {
		}

	}

	public static final class Errors {

		public static final String MALFORMED_MESSAGE = "malformed message";

		public static final String UNKNOWN_OP_PREFIX = "unknown op ";

		public static final String UNKNOWN_INSTRUMENT_PREFIX = "unknown instrument ";

		public static final String INVALID_CREDENTIALS = "invalid credentials";

		public static final String MALFORMED_REQUEST_BODY = "malformed request body";

		public static final String INVALID_REQUEST_PARAMETERS = "invalid request parameters";

		public static final String METHOD_NOT_ALLOWED_PREFIX = "method ";

		public static final String METHOD_NOT_ALLOWED_SUFFIX = " not allowed";

		public static final String NOT_FOUND = "not found";

		public static final String INTERNAL_SERVER_ERROR = "internal server error";

		private Errors() {
		}

	}

	public static final class Users {

		public static final String TRADER1 = "trader1";

		public static final String TRADER1_PASSWORD = "changeme123";

		public static final String TRADER2 = "trader2";

		public static final String TRADER2_PASSWORD = "orderbook456";

		public static final String ANALYST1 = "analyst1";

		public static final String ANALYST1_PASSWORD = "depthchart789";

		private Users() {
		}

	}

}
