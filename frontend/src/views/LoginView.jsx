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
    <section className="login">
      <h2>Sign in</h2>

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
          {isSubmitting ? 'Signing in...' : 'Sign in'}
        </button>
      </form>
    </section>
  )
}

export default LoginView
