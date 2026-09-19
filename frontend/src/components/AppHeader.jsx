import { NavLink } from 'react-router-dom'
import { isReconnecting, useSession } from '../state/sessionContext.js'

function statusMeta(connectionStatus, reconnectAttempt) {
  if (connectionStatus === 'open') {
    return { label: 'Live', tone: 'live' }
  }
  if (isReconnecting(connectionStatus, reconnectAttempt)) {
    return { label: `Reconnecting · ${reconnectAttempt}`, tone: 'warn' }
  }
  if (connectionStatus === 'connecting') {
    return { label: 'Connecting', tone: 'warn' }
  }
  if (connectionStatus === 'failed') {
    return { label: 'Offline', tone: 'down' }
  }
  return { label: 'Idle', tone: '' }
}

function AppHeader() {
  const { username, connectionStatus, reconnectAttempt, signOut } = useSession()
  const status = statusMeta(connectionStatus, reconnectAttempt)

  return (
    <header className="app-header">
      <NavLink to="/overview" className="brand">
        <span className="brand-mark" aria-hidden="true" />
        <span className="brand-copy">
          <strong>Market Desk</strong>
          <span>Public OKX feed</span>
        </span>
      </NavLink>

      <nav className="header-nav" aria-label="Primary">
        <NavLink to="/overview">Overview</NavLink>
      </nav>

      <span className="header-spacer" />

      <span className={`status-pill ${status.tone}`} role="status">
        {status.label}
      </span>
      <span className="user-chip">
        Signed in as <strong>{username}</strong>
      </span>
      <button type="button" className="sign-out" onClick={signOut}>
        Sign out
      </button>
    </header>
  )
}

export default AppHeader
