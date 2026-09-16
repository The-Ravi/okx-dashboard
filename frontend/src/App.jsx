import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import SessionProvider from './state/SessionProvider.jsx'
import { isReconnecting, useSession } from './state/sessionContext.js'
import LoginView from './views/LoginView.jsx'
import MarketOverviewView from './views/MarketOverviewView.jsx'
import OrderBookView from './views/OrderBookView.jsx'
import './App.css'

function RequireAuth({ children }) {
  const { token } = useSession()
  return token ? children : <Navigate to="/login" replace />
}

function SessionBar() {
  const { username, connectionStatus, reconnectAttempt, signOut } = useSession()

  if (!username) {
    return null
  }

  return (
    <div className="session-bar">
      <span>
        Signed in as <strong>{username}</strong>
      </span>
      {isReconnecting(connectionStatus, reconnectAttempt) && (
        <span className="reconnecting" role="status">
          Connection lost. Reconnecting (attempt {reconnectAttempt})...
        </span>
      )}
      {connectionStatus === 'connecting' && reconnectAttempt === 0 && (
        <span className="connecting" role="status">
          Connecting...
        </span>
      )}
      {connectionStatus === 'failed' && (
        <span className="connection-lost" role="alert">
          Connection lost. Gave up reconnecting.
        </span>
      )}
      <button type="button" onClick={signOut}>
        Sign out
      </button>
    </div>
  )
}

function AppShell() {
  return (
    <main>
      <h1>Market Data Service</h1>
      <SessionBar />
      <Routes>
        <Route path="/login" element={<LoginView />} />
        <Route
          path="/overview"
          element={
            <RequireAuth>
              <MarketOverviewView />
            </RequireAuth>
          }
        />
        <Route
          path="/orderbook/:pair"
          element={
            <RequireAuth>
              <OrderBookView />
            </RequireAuth>
          }
        />
        <Route path="*" element={<Navigate to="/overview" replace />} />
      </Routes>
    </main>
  )
}

function App() {
  return (
    <BrowserRouter>
      <SessionProvider>
        <AppShell />
      </SessionProvider>
    </BrowserRouter>
  )
}

export default App
