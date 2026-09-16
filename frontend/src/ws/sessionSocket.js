const INITIAL_RETRY_DELAY_MS = 1000
const MAX_RETRY_DELAY_MS = 20000
const JITTER_RATIO = 0.25

// 4000 is a superseded session and 4001 a revoked token. In both cases the
// server will keep rejecting this token, so retrying is futile.
const FATAL_CLOSE_CODES = new Set([4000, 4001])

export function sessionSocketUrl(token) {
  const { protocol, host } = window.location
  const scheme = protocol === 'https:' ? 'wss:' : 'ws:'
  return `${scheme}//${host}/ws/session?token=${encodeURIComponent(token)}`
}

export function backoffDelay(attempt, random = Math.random) {
  const base = Math.min(INITIAL_RETRY_DELAY_MS * 2 ** attempt, MAX_RETRY_DELAY_MS)
  const jitter = base * JITTER_RATIO * (random() * 2 - 1)
  return Math.max(Math.round(INITIAL_RETRY_DELAY_MS / 2), Math.round(base + jitter))
}

// One instance per authenticated session. Views borrow it through context and
// register per-instrument listeners; they never construct or close it. The
// instance survives reconnects, so views do not remount when the socket drops.
export class SessionSocket {
  constructor(token, { onTerminated, onRevoked, onStatusChange } = {}) {
    this.token = token
    this.handlers = { onTerminated, onRevoked, onStatusChange }

    this.bookListeners = new Map()
    this.errorListeners = new Set()
    this.pendingFrames = []
    this.subscribedInstIds = new Set()

    this.closedDeliberately = false
    this.givenUp = false
    this.retryAttempt = 0
    this.retryDelayMs = null
    this.retryTimer = null
    this.generation = 0
    this.status = 'connecting'

    this.connect()
  }

  // Deliberate closure and a fatal close code are the only two things that
  // stop reconnection. Everything else retries.
  get canReconnect() {
    return !this.closedDeliberately && !this.givenUp
  }

  get isOpen() {
    return this.socket?.readyState === WebSocket.OPEN
  }

  setStatus(status) {
    this.status = status
    this.handlers.onStatusChange?.({
      status,
      attempt: this.retryAttempt,
      retryDelayMs: this.retryDelayMs,
      generation: this.generation,
    })
  }

  connect() {
    this.setStatus('connecting')

    const socket = new WebSocket(sessionSocketUrl(this.token))
    this.socket = socket
    this.subscribedInstIds.clear()

    socket.onopen = () => {
      this.retryAttempt = 0
      this.retryDelayMs = null
      this.generation += 1

      for (const frame of this.pendingFrames) {
        socket.send(frame)
      }
      this.pendingFrames = []

      // The listener registry is the source of truth for what should be
      // streaming, so a reconnect restores exactly those subscriptions. The
      // dedupe set stops a first connection from subscribing twice when the
      // view already queued a frame while CONNECTING.
      for (const instId of this.bookListeners.keys()) {
        this.subscribe(instId)
      }

      this.setStatus('open')
    }

    socket.onmessage = (event) => {
      let message
      try {
        message = JSON.parse(event.data)
      } catch {
        return
      }

      if (message?.type === 'session_terminated') {
        // close() marks the closure deliberate before it runs, so the close
        // event that follows can neither raise "connection lost" nor reconnect.
        this.close()
        this.handlers.onTerminated?.()
        return
      }

      if (message?.type === 'book') {
        const listeners = this.bookListeners.get(message.instId)
        if (listeners) {
          for (const listener of listeners) {
            listener(message)
          }
        }
        return
      }

      if (message?.type === 'error') {
        for (const listener of this.errorListeners) {
          listener(message.message ?? 'unknown error')
        }
      }
    }

    socket.onclose = (event) => {
      if (this.closedDeliberately) {
        return
      }

      this.pendingFrames = []
      this.subscribedInstIds.clear()

      if (FATAL_CLOSE_CODES.has(event?.code)) {
        this.givenUp = true
        this.setStatus('failed')
        this.handlers.onRevoked?.(event.code)
        return
      }

      this.scheduleReconnect()
    }
  }

  scheduleReconnect() {
    this.retryDelayMs = backoffDelay(this.retryAttempt)
    this.retryAttempt += 1
    this.setStatus('reconnecting')

    this.retryTimer = setTimeout(() => {
      this.retryTimer = null
      if (!this.canReconnect) {
        return
      }
      this.connect()
    }, this.retryDelayMs)

    return this.retryDelayMs
  }

  clearRetryTimer() {
    if (this.retryTimer !== null) {
      clearTimeout(this.retryTimer)
      this.retryTimer = null
    }
  }

  send(payload) {
    const frame = JSON.stringify(payload)
    const readyState = this.socket?.readyState

    if (readyState === WebSocket.OPEN) {
      this.socket.send(frame)
    } else if (readyState === WebSocket.CONNECTING) {
      this.pendingFrames.push(frame)
    }
  }

  subscribe(instId) {
    if (this.subscribedInstIds.has(instId)) {
      return
    }
    this.subscribedInstIds.add(instId)
    this.send({ op: 'subscribe', instId })
  }

  unsubscribe(instId) {
    this.subscribedInstIds.delete(instId)
    this.send({ op: 'unsubscribe', instId })
  }

  onBook(instId, listener) {
    let listeners = this.bookListeners.get(instId)
    if (!listeners) {
      listeners = new Set()
      this.bookListeners.set(instId, listeners)
    }
    listeners.add(listener)

    return () => {
      listeners.delete(listener)
      if (listeners.size === 0) {
        this.bookListeners.delete(instId)
      }
    }
  }

  onError(listener) {
    this.errorListeners.add(listener)
    return () => {
      this.errorListeners.delete(listener)
    }
  }

  close() {
    this.closedDeliberately = true
    this.clearRetryTimer()
    this.bookListeners.clear()
    this.errorListeners.clear()
    this.pendingFrames = []
    this.subscribedInstIds.clear()
    this.setStatus('closed')

    const readyState = this.socket?.readyState
    if (readyState === WebSocket.CONNECTING || readyState === WebSocket.OPEN) {
      this.socket.close()
    }
  }
}
