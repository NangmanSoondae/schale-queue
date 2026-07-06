import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 개발 서버는 /api 요청을 백엔드로 프록시해 CORS 를 우회한다.
// API 포트가 8080 이 아니면(예: API_PORT=8081) VITE_API_PROXY_TARGET 로 재지정한다.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
