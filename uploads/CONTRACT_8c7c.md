# CONTRACT.md — Market Data Service

This is the source of truth for every request/response and WebSocket message shape in this
project. Backend and frontend code should match this exactly — if either side needs to
diverge, update this file first, then the code.

---

## 1. Auth

### `POST /auth/login`

Request:
```json
{ "username": "trader1", "password": "changeme123" }
```

Response `200 OK`:
```json
{ "token": "a1b2c3d4-...-uuid", "username": "trader1" }
```

Response `401 Unauthorized` (bad username or bad password — same response either way, don't
leak which one was wrong):
```json
{ "error": "invalid credentials" }
```

Notes:
- Users are a hardcoded in-memory store, 2–3 entries, passwords bcrypt-hashed at startup.
- `token` is an opaque random UUID, not a JWT. It's the key used for both the session map
  and the WebSocket connection below.
- Token has no expiry for this assignment (out of scope) — it stays valid until the process
  restarts or the user logs in again (which issues a new token, invalidating the concept of
  the old one implicitly, though the old token string itself isn't explicitly revoked).

---

## 2. Market Overview

### `GET /market/overview`

No auth header required (keep this one open for simplicity — it's the same data KV cached
server-side regardless of who's asking; the WebSocket is where session identity matters).

Response `200 OK`:
```json
[
  {
    "instId": "BTC-USDT",
    "last": "61234.5",
    "change24hPct": 2.31,
    "vol24h": "812345678.12"
  }
]
```
Array has exactly 20 entries, sorted by `vol24h` descending (this is the server's cached
top-20, refreshed every 5s from OKX — see §4). `change24hPct` is computed server-side as
`((last - open24h) / open24h) * 100`, rounded to 2 decimal places.

Frontend polls this every 5s. This is the one place in the app that's REST-polled by design
— it's explicitly allowed to be (only the order book has the "must stream, not poll"
constraint).

---

## 3. Session WebSocket

### `WS /ws/session?token={token}`

Opened by the client **once, immediately after login succeeds** — not tied to visiting the
Order Book view. This connection *is* "the session" for single-session enforcement purposes,
and it's reused later to carry order-book subscribe/unsubscribe traffic once the user picks
a pair.

**On connect:**
- Server validates `token` against the session map from §1.
- Invalid/unknown token → server closes with code `4001` and reason `"invalid token"`.
- Valid token, but that userId already has an active connection registered → server sends
  that **old** connection `{"type":"session_terminated"}` then closes it (code `4000`,
  reason `"superseded"`), and registers the **new** connection.
- On this connection's close (for any reason), the server clears the socket reference on
  that user's session entry but does **not** delete the token/session map entry — the token
  remains valid for a future reconnect.

**Client → Server messages:**

Subscribe to a pair's order book:
```json
{ "op": "subscribe", "instId": "BTC-USDT" }
```

Unsubscribe:
```json
{ "op": "unsubscribe", "instId": "BTC-USDT" }
```

A client may have multiple pairs subscribed at once in principle, though the UI only ever
subscribes to one (the currently-viewed pair) and unsubscribes before subscribing to the
next.

**Server → Client messages:**

Order book snapshot (sent immediately after a subscribe, and again if OKX resends a full
snapshot e.g. after a reconnect):
```json
{
  "type": "snapshot",
  "instId": "BTC-USDT",
  "bids": [["61200.10", "0.5"], ["61199.80", "1.2"]],
  "asks": [["61201.00", "0.3"], ["61201.50", "0.9"]]
}
```

Order book incremental update (merge into existing state; a size of `"0"` means remove that
price level):
```json
{
  "type": "update",
  "instId": "BTC-USDT",
  "bids": [["61200.10", "0"], ["61198.00", "2.0"]],
  "asks": []
}
```
`bids` sorted descending by price, `asks` ascending, each side capped to the top 15 levels
server-side before sending (no need to ship the full OKX depth to the client).

Session superseded (see "On connect" above):
```json
{ "type": "session_terminated" }
```

Generic error (e.g. subscribe to an invalid/unknown instId):
```json
{ "type": "error", "message": "unknown instrument BTC-FOO" }
```

---

## 4. OKX Integration (backend-only, clients never call these)

### REST — top 20 tickers

```
GET https://www.okx.com/api/v5/market/tickers?instType=SPOT
```
No API key required (public endpoint). Response shape (relevant fields only):
```json
{
  "code": "0",
  "data": [
    {
      "instId": "BTC-USDT",
      "last": "61234.5",
      "open24h": "59870.2",
      "volCcy24h": "812345678.12"
    }
  ]
}
```
- Rate limit: ~3 requests/sec per IP. Fetch on a server-side `@Scheduled` 5-second interval,
  cache the top-20 in memory, and serve `/market/overview` (§2) from that cache — never call
  this per incoming client request.
- Sort by `volCcy24h` descending, take top 20, map into the shape in §2.

### WebSocket — order book

```
wss://ws.okx.com:8443/ws/v5/public
```
Subscribe:
```json
{ "op": "subscribe", "args": [{ "channel": "books", "instId": "BTC-USDT" }] }
```
Unsubscribe:
```json
{ "op": "unsubscribe", "args": [{ "channel": "books", "instId": "BTC-USDT" }] }
```
OKX sends a full snapshot on subscribe (`"action": "snapshot"` in their payload), then
incremental pushes (`"action": "update"`) as the book changes. Keepalive: OKX expects a
`ping` text frame periodically (every ~20–25s) and responds `pong`; reconnect if no `pong`
is received.

One physical connection to OKX for the whole backend process — multiplex all client pair
subscriptions over it (track subscriber count per `instId`; only send OKX an `unsubscribe`
when the last interested client goes away).

---

## 5. Data models (backend)


```
TickerDto        { instId, last, change24hPct, vol24h }
PriceLevel        [ price: String, size: String ]   // kept as OKX's raw string pair, not parsed to BigDecimal for wire format
OrderBookUpdate   { instId, isSnapshot: boolean, bids: List<PriceLevel>, asks: List<PriceLevel> }
SessionInfo        { token, webSocketSession: nullable }
```

---

## 6. Frontend — routes, state, and view behavior

### Routes

```
/login                  — LoginView
/overview                — MarketOverviewView   (requires auth)
/orderbook/:pair          — OrderBookView         (requires auth)
```

Any navigation to `/overview` or `/orderbook/*` without a valid token in app state redirects
to `/login`. No route guard needed on `/login` itself.

### Global app state (not per-view)

Held at the top level (React context, or lifted state in `App.jsx` — implementation's choice,
but it must be reachable from all three views, not re-created per view):

```
{
  token: string | null,
  username: string | null,
  sessionSocket: WebSocket | null   // the §3 connection, one instance for the whole app
}
```

- `token`/`username` live in memory only — no `localStorage`/`sessionStorage`. A page refresh
  logs the user out; that's expected and fine for this assignment.
- `sessionSocket` is opened **once**, immediately after `POST /auth/login` returns `200`, and
  stays open for the lifetime of the authenticated session — it is not opened/closed per view.
  Navigating between Overview and Order Book reuses the same socket.

### View-by-view contract

**LoginView**
- Username + password fields, submit calls `POST /auth/login` (§1).
- On `200`: store `token`/`username` in global state, open `sessionSocket` to
  `/ws/session?token={token}` (§3), navigate to `/overview`.
- On `401`: show the server's error message inline, don't navigate.

**MarketOverviewView**
- On mount: fetch `GET /market/overview` (§2), then re-fetch every 5s via `setInterval`,
  cleared on unmount.
- Renders a table: Symbol | Last Price | 24h Change % | 24h Volume, change % colored
  green/red by sign.
- Clicking a row navigates to `/orderbook/:pair` using that row's `instId`.
- Does **not** touch `sessionSocket` directly — this view is REST-only by design (§2).

**OrderBookView**
- Reads `:pair` from the route.
- On mount: sends `{"op":"subscribe","instId":pair}` over the existing `sessionSocket`
  (does not open a new connection).
- Listens for `snapshot`/`update` messages scoped to this `instId`, updates local bid/ask
  state continuously as they arrive — no throttling, no "show once and stop."
- Renders top 15 bid levels (descending) and top 15 ask levels (ascending).
- On unmount, or when `:pair` changes: sends `{"op":"unsubscribe","instId":pair}` for the
  **old** pair before subscribing to the new one.

### Global `sessionSocket` message handling (not view-specific)

Regardless of which view is currently mounted, the app must react to these `sessionSocket`
messages at the top level:
- `session_terminated` (§3): close the socket, clear `token`/`username`/`sessionSocket` from
  state, show a brief "logged in from another session" message, redirect to `/login`.
- Unexpected socket close (not via `session_terminated`): this is the Step 6 / bonus scope —
  minimally, don't crash; at minimum show a "connection lost" indicator on whichever view is
  mounted.

---

## 7. Out of scope (explicitly, for the README's "assumptions" section)

- Token expiry / refresh
- User registration
- Persisting sessions across a server restart
- Rate limiting our own `/auth/login` or `/market/overview` endpoints
- More than one browser tab per user being a *supported* concurrent use case (it's actively
  prevented by design — see §3)
