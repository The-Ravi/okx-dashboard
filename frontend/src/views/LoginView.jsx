import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useSession } from '../state/sessionContext.js'
import './LoginView.css'

function LoginView() {
  const navigate = useNavigate()
  const { signIn, notice } = useSession()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [isSubmitting, setIsSubmitting] = useState(false)

  async function handleSubmit(event) {
    event.preventDefault()
    if (isSubmitting) {
      return
    }

    setIsSubmitting(true)
    setError(null)

    try {
      await signIn(username, password)
      navigate('/overview', { replace: true })
    } catch (cause) {
      setError(cause.message)
      setIsSubmitting(false)
    }
  }

  return (
    <div className="login-layout">
      <section className="login-brand" aria-hidden="true">
        <span className="brand-mark" />
        <h1>Market Desk</h1>
        <p>Live public-market data from OKX. One session, streamed order books, no polling on depth.</p>
      </section>

      <section className="login">
        <p className="login-kicker">Secure session</p>
        <h2>Sign in</h2>
        <p className="login-lead">Authenticate to open a live market session.</p>

        {notice && (
          <p className="notice" role="status">
            {notice}
          </p>
        )}

        <form onSubmit={handleSubmit}>
          <label htmlFor="username">Username</label>
          <input
            id="username"
            name="username"
            autoComplete="username"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            disabled={isSubmitting}
            required
          />

          <label htmlFor="password">Password</label>
          <input
            id="password"
            name="password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            disabled={isSubmitting}
            required
          />

          {error && (
            <p className="error" role="alert">
              {error}
            </p>
          )}

          <button type="submit" disabled={isSubmitting}>
            {isSubmitting ? 'Signing in...' : 'Continue'}
          </button>
        </form>
      </section>
    </div>
  )
}

export default LoginView
