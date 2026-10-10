import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const vworldKey = env.VITE_VWORLD_KEY;

  // 백엔드 주소. 기본은 8080 이고 `VITE_API_TARGET` 으로 덮어쓴다.
  // **포트를 박아 두면 안 되는 이유**: Hyper-V 가 부팅할 때 포트 범위를 동적으로
  // 예약해 8080 이 묶이는 일이 있다(실제로 7734~8433 이 전부 묶인 PC 가 있었다).
  // 그러면 백엔드가 아예 바인딩을 못 하므로 서버·프록시 양쪽을 옮겨야 한다.
  // 예약 범위 확인: netsh int ipv4 show excludedportrange protocol=tcp
  const apiTarget = env.VITE_API_TARGET || 'http://localhost:8080';

  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        // 백엔드. 같은 출처로 보이게 해서 CORS 설정을 피한다
        '/api': { target: apiTarget, changeOrigin: true },

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
