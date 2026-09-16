import { useCallback, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { login as postLogin } from '../api/auth.js'
import { SessionSocket } from '../ws/sessionSocket.js'
import { SessionContext } from './sessionContext.js'

const TERMINATED_NOTICE = 'You were logged in from another session, so this one was ended.'
const REVOKED_NOTICE = 'Your session is no longer valid. Please sign in again.'

const EMPTY_SESSION = {
  token: null,
  username: null,
  sessionSocket: null,
}

const IDLE_CONNECTION = {
  status: 'idle',
  attempt: 0,
  retryDelayMs: null,
  generation: 0,
}

// Credentials live in memory only. A page refresh drops them and logs the user
// out, which the contract calls expected behaviour.
function SessionProvider({ children }) {
  const navigate = useNavigate()
  const [session, setSession] = useState(EMPTY_SESSION)
  const [connection, setConnection] = useState(IDLE_CONNECTION)
  const [notice, setNotice] = useState(null)
  const socketRef = useRef(null)

  const endSession = useCallback(
    (message) => {
      socketRef.current?.close()
      socketRef.current = null
      setSession(EMPTY_SESSION)
      setConnection(IDLE_CONNECTION)
      setNotice(message ?? null)
      navigate('/login', { replace: true })
    },
    [navigate],
  )

  const signIn = useCallback(
    async (username, password) => {
      const credentials = await postLogin(username, password)

      setNotice(null)

      const socket = new SessionSocket(credentials.token, {
        onTerminated: () => endSession(TERMINATED_NOTICE),
        onRevoked: (code) => endSession(code === 4000 ? TERMINATED_NOTICE : REVOKED_NOTICE),
        onStatusChange: setConnection,
      })

      socketRef.current = socket
      setSession({
        token: credentials.token,
        username: credentials.username,
        sessionSocket: socket,
      })
    },
    [endSession],
  )

  const signOut = useCallback(() => endSession(null), [endSession])

  const value = useMemo(
    () => ({
      token: session.token,
      username: session.username,
      sessionSocket: session.sessionSocket,
      connectionStatus: connection.status,
      reconnectAttempt: connection.attempt,
      retryDelayMs: connection.retryDelayMs,
      connectionGeneration: connection.generation,
      notice,
      signIn,
      signOut,
    }),
    [session, connection, notice, signIn, signOut],
  )

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

export default SessionProvider
