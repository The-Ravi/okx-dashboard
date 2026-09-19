import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { fetchMarketOverview } from '../api/market.js'
import './MarketOverviewView.css'

const POLL_INTERVAL_MS = 5000

const volumeFormatter = new Intl.NumberFormat('en-US', {
  maximumFractionDigits: 2,
})

const timeFormatter = new Intl.DateTimeFormat('en-GB', {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
})

function formatPrice(last) {
  if (last == null || last === '') {
    return '—'
  }
  const parsed = Number(last)
  if (!Number.isFinite(parsed)) {
    return String(last)
  }
  const fraction = String(last).includes('.') ? String(last).split('.')[1].length : 0
  return parsed.toLocaleString('en-US', {
    minimumFractionDigits: fraction,
    maximumFractionDigits: fraction,
  })
}

function formatVolume(vol24h) {
  const parsed = Number(vol24h)
  if (vol24h == null || vol24h === '' || !Number.isFinite(parsed)) {
    return '—'
  }
  return volumeFormatter.format(parsed)
}

function formatChange(change24hPct) {
  if (typeof change24hPct !== 'number' || !Number.isFinite(change24hPct)) {
    return '—'
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

function splitInstId(instId) {
  const [base, quote] = String(instId).split('-')
  return { base: base ?? instId, quote: quote ?? '' }
}

function MarketOverviewView() {
  const navigate = useNavigate()
  const [tickers, setTickers] = useState([])
  const [query, setQuery] = useState('')
  const [updatedAt, setUpdatedAt] = useState(null)
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
        setUpdatedAt(new Date())
        setError(null)
      } catch (cause) {
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

  const maxVolume = useMemo(() => {
    return tickers.reduce((max, ticker) => {
      const value = Number(ticker.vol24h)
      return Number.isFinite(value) && value > max ? value : max
    }, 0)
  }, [tickers])

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase()
    if (!needle) {
      return tickers
    }
    return tickers.filter((ticker) => ticker.instId.toLowerCase().includes(needle))
  }, [tickers, query])

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
      <header className="page-head">
        <div>
          <p className="kicker">Watchlist</p>
          <h1>Market overview</h1>
          <p className="subtitle">Top instruments by 24h quote volume. Select a pair to stream its book.</p>
        </div>
        <div className="page-meta">
          {updatedAt && (
            <span>
              Updated <time dateTime={updatedAt.toISOString()}>{timeFormatter.format(updatedAt)}</time>
            </span>
          )}
          <label className="search">
            <span className="visually-hidden">Filter instruments</span>
            <input
              type="search"
              placeholder="Filter symbol"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
          </label>
        </div>
      </header>

      {error && (
        <p className="flash flash-error" role="alert">
          Could not refresh market data: {error}
        </p>
      )}

      {isInitialLoad && <p className="status">Loading market data…</p>}

      {!isInitialLoad && tickers.length === 0 && (
        <p className="status">No market data available yet.</p>
      )}

      {tickers.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th className="num">#</th>
                <th>Symbol</th>
                <th>Last</th>
                <th>24h</th>
                <th>Volume</th>
              </tr>
            </thead>
            <tbody>
              {visible.map((ticker) => {
                const { base, quote } = splitInstId(ticker.instId)
                const rank = tickers.findIndex((row) => row.instId === ticker.instId) + 1
                const volumeShare =
                  maxVolume > 0 && Number.isFinite(Number(ticker.vol24h))
                    ? Math.max(8, Math.sqrt(Number(ticker.vol24h) / maxVolume) * 100)
                    : 0
                return (
                  <tr
                    key={ticker.instId}
                    tabIndex={0}
                    onClick={() => openOrderBook(ticker.instId)}
                    onKeyDown={(event) => handleRowKeyDown(event, ticker.instId)}
                  >
                    <td className="num faint">{rank}</td>
                    <td>
                      <span className="symbol">
                        <strong>{base}</strong>
                        {quote && <span>/{quote}</span>}
                      </span>
                    </td>
                    <td className="mono">{formatPrice(ticker.last)}</td>
                    <td className={`mono ${changeClass(ticker.change24hPct) ?? ''}`}>
                      {formatChange(ticker.change24hPct)}
                    </td>
                    <td>
                      <span className="volume-cell">
                        <span className="volume-bar" style={{ width: `${volumeShare}%` }} />
                        <span className="mono">{formatVolume(ticker.vol24h)}</span>
                      </span>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
          {visible.length === 0 && <p className="status inset">No symbols match “{query}”.</p>}
        </div>
      )}
    </section>
  )
}

export default MarketOverviewView
