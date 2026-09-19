import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import AppHeader from './components/AppHeader.jsx'
import SessionProvider from './state/SessionProvider.jsx'
import { useSession } from './state/sessionContext.js'
import LoginView from './views/LoginView.jsx'
import MarketOverviewView from './views/MarketOverviewView.jsx'
import OrderBookView from './views/OrderBookView.jsx'
import './App.css'

function RequireAuth({ children }) {
  const { token } = useSession()
  return token ? children : <Navigate to="/login" replace />
}

function AppShell() {
  const { username } = useSession()

  return (
    <div className="app">
      {username && <AppHeader />}
      <main className={username ? 'app-main' : 'app-main app-main--auth'}>
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
    </div>
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
