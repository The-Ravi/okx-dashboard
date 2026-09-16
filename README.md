# Market Data Service

A live market data service backed by OKX's public API, with a browser client. The backend polls
OKX for spot tickers and holds a single upstream WebSocket connection for order book depth; the
frontend shows a market overview and a live order book, behind a login that permits one active
session per user.

```
browser ──┬── GET  /market/overview ──┐
          │                           │   Spring Boot backend      ┌── REST  /api/v5/market/tickers
          ├── POST /auth/login ───────┼──  (port 8477)  ───────────┤
          │                           │                            └── WS    /ws/v5/public (books)
          └── WS   /ws/session ───────┘
```

The backend is the only thing that talks to OKX. Every browser is served from the backend's own
cache and from one shared upstream order book connection, so a hundred open tabs still produce the
same OKX traffic as one.

## Prerequisites

| Requirement | Version used here | Notes |
| --- | --- | --- |
| JDK | 21 (project targets 17) | Anything from 17 up works. `JAVA_HOME` must be set. |
| Maven | none needed | The Maven wrapper (`./mvnw`) downloads its own. |
| Node.js | 22.14 | 20 or newer. |
| Network | — | Outbound HTTPS to `www.okx.com` and WSS to `ws.okx.com:8443`. |

No OKX account, API key, or secret is required: every endpoint used is public.

## Running locally

Start the backend first — the frontend proxies to it, and a frontend without a backend can only
render error states.

**Backend** (port 8477):

```bash
cd backend
./mvnw spring-boot:run
```

Wait for `Started MarketdataApplication`. Roughly five seconds later the first ticker poll logs
`OKX ticker fetch: N instruments received, cached top 20`, and the overview endpoint has data.

**Frontend** (port 5317):

```bash
cd frontend
npm install
npm run dev
```

Then open <http://127.0.0.1:5317>.

The Vite dev server proxies `/auth`, `/market` and `/ws` (with `ws: true`) to `localhost:8477`, so
the browser only ever sees one origin and the backend needs no CORS configuration. It is pinned to
`127.0.0.1` on purpose: `localhost` resolves to `::1` first on some hosts, which leaves the IPv4
address unreachable.

To run the backend on a different port, change `server.port` in
`backend/src/main/resources/application.yml` and the three proxy targets in
`frontend/vite.config.js` together.

## Test credentials

Three hardcoded users. Passwords are BCrypt-hashed once at startup, and only the hashes are kept
in memory.

| Username | Password |
| --- | --- |
| `trader1` | `changeme123` |
| `trader2` | `orderbook456` |
| `analyst1` | `depthchart789` |

Any of them reaches the same data. Use two different users if you want two browser tabs open at
once — see [single-session enforcement](#single-session-enforcement) for why two tabs as the *same*
user will not stay open.

## What you can do

- **Login** — credentials are checked server-side; a failure reports only that the credentials were
  invalid, without saying which field was wrong.
- **Market overview** — the top 20 spot pairs by 24h volume, refreshed every 5 seconds. The 24h
  change is coloured, and clicking a row opens that pair's order book.
- **Order book** — the top 15 bid and ask levels for the selected pair, streamed live. Every
  message carries the complete 15-level window, so the client renders what it is given and never
  merges deltas.

### API surface

| Endpoint | Purpose |
| --- | --- |
| `POST /auth/login` | `{username, password}` in, `{token, username}` out, or 401. |
| `GET /market/overview` | The cached top 20 tickers. |
| `WS /ws/session?token=…` | Session channel. Client sends `{op: "subscribe"\|"unsubscribe", instId}`; server sends `book`, `error`, and `session_terminated` messages. |

## Assumptions

Deliberately out of scope, per the contract's out-of-scope section:

- **No token expiry or refresh.** A token stays valid until a newer login for the same user
  replaces it, or until the process restarts.
- **No user registration.** The three accounts above are compiled in.
- **No persistence.** Sessions and cached market data live in memory only; a restart invalidates
  every token and empties the cache.
- **No rate limiting on our own endpoints.** `/auth/login` and `/market/overview` are unthrottled.
  Only OKX-facing traffic is budgeted (one ticker poll per 5s against a 20-per-2s limit, one
  upstream WebSocket connection).
- **More than one tab per user is not a supported use case.** It is actively prevented rather than
  merely unhandled.

Two further decisions worth naming:

- **`/market/overview` requires no authentication**, matching the contract. It exposes only public
  OKX data, but it does mean the overview is readable without logging in.
- **Order book depth is pushed whole, not as deltas.** The backend keeps the authoritative
  full-depth book per instrument and sends the entire top 15 on every change. This costs a little
  bandwidth and removes client-side book drift as a failure mode.

## Known limitations

Honest list of what is not done or not ideal:

- **Top-20 ranking mixes quote currencies.** The contract says to rank on `volCcy24h` across all
  spot pairs, and that is what the code does — but `volCcy24h` is denominated in each pair's own
  quote currency, so a BTC-quoted pair's volume is compared directly against a USDT-quoted pair's.
  A pair with modest real turnover quoted in an expensive currency can outrank a larger USDT pair.
  Filtering to `-USDT` before ranking would make the comparison meaningful.
- **No global exception handler.** `POST /auth/login` handles malformed JSON locally, but an
  unexpected server error elsewhere returns Spring's default error body rather than the contract's
  `{"error": "…"}` shape. A `@RestControllerAdvice` would close this.
- **No automated test suite.** Behaviour was verified by hand and with throwaway scripts against
  the live service; none of that is committed as repeatable tests.
- **Session tokens travel in the WebSocket URL query string.** Acceptable over `wss://` to a
  trusted host, but query strings are the kind of thing that ends up in access logs. A
  connect-then-authenticate handshake would be better.
- **A server restart silently logs everyone out.** The next frontend action fails with an invalid
  token and returns the user to the login screen, which is correct but abrupt.
- **The contract document is not yet updated** to match the final order book protocol; it still
  describes the earlier snapshot-plus-delta design that was replaced.

What *is* implemented, since it is the usual gap in a project this size: the upstream OKX order
book connection reconnects on its own with jittered exponential backoff, resubscribes everything
that still has listeners, and resets each book's sequence position so a stale position is never
carried across a reconnect. It sends OKX's literal `ping` every 20 seconds (OKX drops connections
idle for 30 and never pings first) and force-reconnects if nothing comes back at all. Sequence
continuity is checked with `seqId`/`prevSeqId` — the deprecated `checksum` field is ignored — and a
gap causes a resubscribe rather than a quietly corrupted book. Subscriptions are reference-counted
per instrument: the first interested client triggers the upstream subscribe, the last one leaving
triggers the unsubscribe and drops the book state, and a client that disconnects for any reason,
including being kicked, has its references released.

## Single-session enforcement

One user, one live session. The backend keeps a `Map<String, SessionInfo>` in `SessionRegistry`
keyed by **userId**, where each `SessionInfo` holds the currently valid token and a nullable
reference to that user's open `WebSocketSession`, plus a reverse index from token to userId so an
incoming connection can be resolved. Because the map is keyed by user rather than by token, a
second session for the same user has nowhere to live: it overwrites the first.

Displacement happens at both points where a newcomer can appear. When a **second login** succeeds,
the old token is dropped from the reverse index and stops resolving; revoking it is not enough on
its own, because a socket that is already open was authenticated at connect time and is never
re-checked, so any socket still attached to the old entry is handed to the gateway and closed too.
When a **second connect** arrives on a token that already has a socket attached, the socket it
displaces is closed the same way. In both cases the departing client is first sent
`{"type":"session_terminated"}` and then closed with code **4000 superseded**, so it can tell being
kicked apart from a network drop — it returns to the login screen with an explanation instead of
trying to reconnect. A connection presenting a token that no longer resolves is closed with **4001
invalid token**.

The whole check-kick-register sequence runs under a per-user `ReentrantLock`, so two simultaneous
logins or connects for one user cannot both conclude they are the newcomer. The lock is reentrant
rather than a `ConcurrentHashMap.compute` block because closing the displaced socket can dispatch
its own close callback on the calling thread, which re-enters the registry — safe under a reentrant
lock, forbidden inside `compute`. The close callback also verifies it still owns the entry before
clearing it, so a slow goodbye from a kicked socket cannot wipe out its replacement's reference.
