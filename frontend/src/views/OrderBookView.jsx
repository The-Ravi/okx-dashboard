import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { isReconnecting, useSession } from '../state/sessionContext.js'
import './OrderBookView.css'

const DEPTH = 15
const SKELETON_ROWS = Array.from({ length: DEPTH }, (_, index) => index)

const timeFormatter = new Intl.DateTimeFormat('en-GB', {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
})

function formatVenueTime(ts) {
  const parsed = Number(ts)
  if (!Number.isFinite(parsed)) {
    return null
  }
  const millis = parsed < 1e12 ? parsed * 1000 : parsed
  return timeFormatter.format(new Date(millis))
}

function maxSize(levels) {
  return (levels ?? []).reduce((max, [, size]) => {
    const value = Number(size)
    return Number.isFinite(value) && value > max ? value : max
  }, 0)
}

function BookSide({ title, levels, tone }) {
  const peak = maxSize(levels)

  return (
    <table className={`book-side ${tone}`}>
      <thead>
        <tr>
          <th colSpan={2}>{title}</th>
        </tr>
        <tr>
          <th>Price</th>
          <th>Size</th>
        </tr>
      </thead>
      <tbody>
        {levels === null
          ? SKELETON_ROWS.map((index) => (
              <tr key={index} className="skeleton">
                <td>
                  <span />
                </td>
                <td>
                  <span />
                </td>
              </tr>
            ))
          : levels.map(([price, size]) => {
              const width =
                peak > 0 && Number.isFinite(Number(size)) ? (Number(size) / peak) * 100 : 0
              return (
                <tr key={price}>
                  <td className="price">{price}</td>
                  <td>
                    <span className="size-cell">
                      <span className="depth-bar" style={{ width: `${width}%` }} />
                      <span>{size}</span>
                    </span>
                  </td>
                </tr>
              )
            })}
      </tbody>
    </table>
  )
}

function formatSpread(bestBid, bestAsk) {
  const bid = Number(bestBid)
  const ask = Number(bestAsk)
  if (!Number.isFinite(bid) || !Number.isFinite(ask) || ask <= 0) {
    return null
  }
  const spread = ask - bid
  const mid = (ask + bid) / 2
  const bps = mid === 0 ? 0 : (spread / mid) * 10_000
  return { spread, bps, mid }
}

function OrderBookView() {
  const { pair } = useParams()
  const { sessionSocket, connectionStatus, reconnectAttempt, connectionGeneration } = useSession()
  const [snapshot, setSnapshot] = useState(null)
  const [streamError, setStreamError] = useState(null)

  useEffect(() => {
    if (!sessionSocket) {
      return undefined
    }

    const stopBook = sessionSocket.onBook(pair, (message) => {
      setSnapshot({
        pair,
        generation: sessionSocket.generation,
        bids: (message.bids ?? []).slice(0, DEPTH),
        asks: (message.asks ?? []).slice(0, DEPTH),
        ts: message.ts ?? null,
      })
    })
    const stopError = sessionSocket.onError((message) => setStreamError({ pair, message }))

    sessionSocket.subscribe(pair)

    return () => {
      sessionSocket.unsubscribe(pair)
      stopBook()
      stopError()
    }
  }, [sessionSocket, pair])

  const isLive = connectionStatus === 'open'
  const isCurrent = snapshot?.pair === pair && snapshot.generation === connectionGeneration
  const book = isLive && isCurrent ? snapshot : null
  const bookError = streamError?.pair === pair ? streamError.message : null
  const reconnecting = isReconnecting(connectionStatus, reconnectAttempt)

  const stats = useMemo(() => {
    if (!book) {
      return null
    }
    const bestBid = book.bids[0]?.[0]
    const bestAsk = book.asks[0]?.[0]
    return { bestBid, bestAsk, ...formatSpread(bestBid, bestAsk) }
  }, [book])

  return (
    <section className="order-book">
      <p className="breadcrumb">
        <Link to="/overview">Overview</Link>
        <span>/</span>
        <span>{pair}</span>
      </p>

      <header className="page-head">
        <div>
          <p className="kicker">Order book</p>
          <h1>{pair}</h1>
          <p className="subtitle">
            {formatVenueTime(book?.ts)
              ? `Top ${DEPTH} levels each side · updated ${formatVenueTime(book.ts)}`
              : `Top ${DEPTH} levels each side`}
          </p>
        </div>
      </header>

      {stats && (
        <dl className="book-stats">
          <div>
            <dt>Best bid</dt>
            <dd className="bid">{stats.bestBid ?? '—'}</dd>
          </div>
          <div>
            <dt>Best ask</dt>
            <dd className="ask">{stats.bestAsk ?? '—'}</dd>
          </div>
          <div>
            <dt>Spread</dt>
            <dd>
              {stats.spread != null
                ? stats.spread.toLocaleString('en-US', { maximumFractionDigits: 8 })
                : '—'}
              {stats.bps != null && <span className="faint"> · {stats.bps.toFixed(2)} bps</span>}
            </dd>
          </div>
        </dl>
      )}

      {!sessionSocket && (
        <p className="flash flash-error" role="alert">
          No session connection is open. Sign in again to stream this book.
        </p>
      )}

      {reconnecting && (
        <p className="flash flash-warn" role="status">
          Connection lost. Reconnecting (attempt {reconnectAttempt})… Depth below is not live.
        </p>
      )}

      {connectionStatus === 'failed' && (
        <p className="flash flash-error" role="alert">
          Connection lost. Live updates have stopped.
        </p>
      )}

      {bookError && (
        <p className="flash flash-error" role="alert">
          {bookError} <Link to="/overview">Pick another instrument</Link>
        </p>
      )}

      {!bookError && book === null && isLive && (
        <p className="status">Waiting for the first book message…</p>
      )}

      {!bookError && (
        <div className="book-grid">
          <BookSide title="Bids" levels={book ? book.bids : null} tone="bid" />
          <BookSide title="Asks" levels={book ? book.asks : null} tone="ask" />
        </div>
      )}
    </section>
  )
}

export default OrderBookView
