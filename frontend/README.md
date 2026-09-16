# Market Data Service — Frontend

Vite + React (plain JavaScript with JSX) client for the OKX market data viewer.

## Scripts

```bash
npm install
npm run dev      # dev server on http://localhost:5317
npm run build    # production build into dist/
npm run preview  # serve the production build
```

## Backend

The dev server proxies `/auth`, `/market`, and `/ws` (WebSocket upgrade enabled) to the
backend at `http://localhost:8477`, so the browser only ever talks to one origin and the
backend needs no CORS configuration.

## Layout

```
src/
  api/    REST helpers for the auth and market-overview endpoints
  views/  Login, Market Overview, and Order Book screens
  ws/     the shared session WebSocket client and message handling
```
