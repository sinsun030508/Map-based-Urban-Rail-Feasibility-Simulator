import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // /api 를 백엔드로 넘긴다. 프론트에서 보면 같은 출처라 CORS 설정이 필요 없다.
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
});
