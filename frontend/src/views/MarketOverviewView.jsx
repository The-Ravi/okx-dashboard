import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { fetchMarketOverview } from '../api/market.js'
import './MarketOverviewView.css'

const POLL_INTERVAL_MS = 5000

const volumeFormatter = new Intl.NumberFormat('en-US', {
  maximumFractionDigits: 2,
})

// Prices span many orders of magnitude (BTC-USDT ~76236.8, PEPE-USDT ~0.000003361),
// so the raw string from OKX is passed through rather than rounded to a fixed scale.
function formatPrice(last) {
  return last == null || last === '' ? '-' : String(last)
}

function formatVolume(vol24h) {
  const parsed = Number(vol24h)
  if (vol24h == null || vol24h === '' || !Number.isFinite(parsed)) {
    return '-'
  }
  return volumeFormatter.format(parsed)
}

function formatChange(change24hPct) {
  if (typeof change24hPct !== 'number' || !Number.isFinite(change24hPct)) {
    return '-'
  }
  const sign = change24hPct > 0 ? '+' : ''
  return `${sign}${change24hPct.toFixed(2)}%`
}

function changeClass(change24hPct) {
  if (typeof change24hPct !== 'number' || !Number.isFinite(change24hPct)) {
    return undefined
  }
  if (change24hPct > 0) {
    return 'up'
  }
  return change24hPct < 0 ? 'down' : undefined
}

function MarketOverviewView() {
  const navigate = useNavigate()
  const [tickers, setTickers] = useState([])
  const [isInitialLoad, setIsInitialLoad] = useState(true)
  const [error, setError] = useState(null)

  useEffect(() => {
    const controller = new AbortController()

    async function load() {
      try {
        const data = await fetchMarketOverview(controller.signal)
        if (controller.signal.aborted) {
          return
        }
        setTickers(data)
        setError(null)
      } catch (cause) {
        // A failed poll keeps the last good rows on screen and leaves the
        // interval running so the next tick can recover.
        if (controller.signal.aborted) {
          return
        }
        setError(cause.message)
      } finally {
        if (!controller.signal.aborted) {
          setIsInitialLoad(false)
        }
      }
    }

    load()
    const intervalId = setInterval(load, POLL_INTERVAL_MS)

    return () => {
      clearInterval(intervalId)
      controller.abort()
    }
  }, [])

  function openOrderBook(instId) {
    navigate(`/orderbook/${encodeURIComponent(instId)}`)
  }

  function handleRowKeyDown(event, instId) {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      openOrderBook(instId)
    }
  }

  return (
    <section className="market-overview">
      <h2>Market Overview</h2>
      <p className="subtitle">
        Top instruments by 24h volume, refreshed every 5 seconds. Select a row to
        view its order book.
      </p>

      {error && (
        <p className="error" role="alert">
          Could not load market data: {error}
        </p>
      )}

      {isInitialLoad && <p className="status">Loading market data...</p>}

      {!isInitialLoad && tickers.length === 0 && (
        <p className="status">No market data available yet.</p>
      )}

      {tickers.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>Symbol</th>
              <th>Last Price</th>
              <th>24h Change %</th>
              <th>24h Volume</th>
            </tr>
          </thead>
          <tbody>
            {tickers.map((ticker) => (
              <tr
                key={ticker.instId}
                tabIndex={0}
                onClick={() => openOrderBook(ticker.instId)}
                onKeyDown={(event) => handleRowKeyDown(event, ticker.instId)}
              >
                <td>{ticker.instId}</td>
                <td>{formatPrice(ticker.last)}</td>
                <td className={changeClass(ticker.change24hPct)}>
                  {formatChange(ticker.change24hPct)}
                </td>
                <td>{formatVolume(ticker.vol24h)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}

export default MarketOverviewView
