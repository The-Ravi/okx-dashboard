const OVERVIEW_PATH = '/market/overview'

export async function fetchMarketOverview(signal) {
  const response = await fetch(OVERVIEW_PATH, {
    signal,
    headers: { Accept: 'application/json' },
  })

  if (!response.ok) {
    throw new Error(`HTTP ${response.status} ${response.statusText}`.trim())
  }

  const data = await response.json()

  if (!Array.isArray(data)) {
    throw new Error('response was not a list of tickers')
  }

  return data
}
