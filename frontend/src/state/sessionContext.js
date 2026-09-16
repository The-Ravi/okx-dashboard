import { createContext, useContext } from 'react'

export const SessionContext = createContext(null)

// A retry that is already in flight reports status 'connecting' with a
// non-zero attempt, which is still part of the same reconnect sequence.
export function isReconnecting(status, attempt) {
  return status === 'reconnecting' || (status === 'connecting' && attempt > 0)
}

export function useSession() {
  const session = useContext(SessionContext)
  if (!session) {
    throw new Error('useSession must be used within a SessionProvider')
  }
  return session
}
