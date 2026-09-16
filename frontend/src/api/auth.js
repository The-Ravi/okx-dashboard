const LOGIN_PATH = '/auth/login'

export async function login(username, password) {
  const response = await fetch(LOGIN_PATH, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify({ username, password }),
  })

  const payload = await response.json().catch(() => null)

  if (!response.ok) {
    throw new Error(payload?.error ?? `HTTP ${response.status} ${response.statusText}`.trim())
  }

  if (!payload?.token) {
    throw new Error('login response did not contain a token')
  }

  return { token: payload.token, username: payload.username ?? username }
}
