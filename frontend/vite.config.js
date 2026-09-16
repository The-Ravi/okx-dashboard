import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Pinned to IPv4 loopback: 'localhost' resolves to ::1 first on some hosts,
    // which leaves 127.0.0.1:5317 unreachable.
    host: '127.0.0.1',
    port: 5317,
    strictPort: true,
    // Everything backend-bound is proxied through this origin so the browser
    // only ever talks to one origin and the backend needs no CORS config.
    proxy: {
      '/auth': {
        target: 'http://localhost:8477',
        changeOrigin: true,
      },
      '/market': {
        target: 'http://localhost:8477',
        changeOrigin: true,
      },
      '/ws': {
        target: 'http://localhost:8477',
        changeOrigin: true,
        ws: true,
      },
    },
  },
})
