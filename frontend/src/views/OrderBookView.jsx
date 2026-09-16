import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { isReconnecting, useSession } from '../state/sessionContext.js'
import './OrderBookView.css'

const DEPTH = 15
const SKELETON_ROWS = Array.from({ length: DEPTH }, (_, index) => index)

function BookSide({ title, levels, tone }) {
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
          : levels.map(([price, size]) => (
              <tr key={price}>
                <td className="price">{price}</td>
                <td>{size}</td>
              </tr>
            ))}
      </tbody>
    </table>
  )
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

    // Each message carries the complete top-15 window, so state is replaced
    // wholesale rather than merged by price.
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
      // Runs before the next effect, so the old pair is always unsubscribed
      // before the new one is subscribed.
      sessionSocket.unsubscribe(pair)
      stopBook()
      stopError()
    }
  }, [sessionSocket, pair])

  // Depth is only shown while it is genuinely live: data from another pair, or
  // from a previous connection, is ignored during render rather than cleared
  // from inside the effect.
  const isLive = connectionStatus === 'open'
  const isCurrent = snapshot?.pair === pair && snapshot.generation === connectionGeneration
  const book = isLive && isCurrent ? snapshot : null
  const bookError = streamError?.pair === pair ? streamError.message : null
  const reconnecting = isReconnecting(connectionStatus, reconnectAttempt)

  return (
    <section className="order-book">
      <p className="breadcrumb">
        <Link to="/overview">&larr; Back to market overview</Link>
      </p>

      <h2>{pair}</h2>
      <p className="subtitle">
        {book?.ts ? `Top ${DEPTH} levels each side, last update ${book.ts}.` : `Top ${DEPTH} levels each side.`}
      </p>

      {!sessionSocket && (
        <p className="error" role="alert">
          No session connection is open. Sign in again to stream this book.
        </p>
      )}

      {reconnecting && (
        <p className="warning" role="status">
          Connection lost. Reconnecting (attempt {reconnectAttempt})... Depth below is
          not live.
        </p>
      )}

      {connectionStatus === 'failed' && (
        <p className="error" role="alert">
          Connection lost. Live updates have stopped.
        </p>
      )}

      {bookError && (
        <p className="error" role="alert">
          {bookError} <Link to="/overview">Pick another instrument</Link>
        </p>
      )}

      {!bookError && book === null && isLive && (
        <p className="status">Waiting for the first book message...</p>
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
