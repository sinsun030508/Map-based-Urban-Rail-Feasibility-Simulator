import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const vworldKey = env.VITE_VWORLD_KEY;

  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        // 백엔드. 같은 출처로 보이게 해서 CORS 설정을 피한다
        '/api': { target: 'http://localhost:8080', changeOrigin: true },

        // VWorld 타일. 브라우저가 직접 부르면 인증키에 등록한 도메인과 맞지 않아 403 이 난다.
        // Vite 가 대신 받아오면서 Referer 를 등록 도메인으로 맞춘다.
        // 키가 URL 에 노출되지 않는 부수 효과도 있다.
        ...(vworldKey && {
          '/vworld': {
            target: 'https://api.vworld.kr',
            changeOrigin: true,
            headers: { Referer: 'http://localhost:5173/' },
            rewrite: (path) =>
              path.replace(/^\/vworld/, `/req/wmts/1.0.0/${vworldKey}/Base`),
          },
        }),
      },
    },
  };
});
