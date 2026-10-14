import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: {
    // Vite 5's default, kept when Vite 7 raised it to `baseline-widely-available` (Chrome 111,
    // Safari 16.4). Nothing states which browsers the SPA supports, so an upgrade should not decide
    // it: ADR-036, and #250 for the decision itself.
    target: ['es2020', 'edge88', 'firefox78', 'chrome87', 'safari14'],
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // WebSocket endpoint for real-time notifications.
      '/ws': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: true,
      },
    },
  },
})
