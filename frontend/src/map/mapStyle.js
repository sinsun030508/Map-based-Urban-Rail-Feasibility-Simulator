/**
 * 지도 배경.
 * VWorld 는 인증키와 도메인 등록이 필요해서, 키가 없으면 OpenFreeMap 으로 떨어뜨린다.
 * 키 발급: https://www.vworld.kr → 오픈API → 인증키 발급
 * .env 에 VITE_VWORLD_KEY=... 를 넣고 Vite 를 재시작하면 VWorld 를 쓴다.
 *
 * 타일은 브라우저가 직접 부르지 않고 `/vworld` 로 요청해 Vite 프록시가 대신 받아온다.
 * 직접 부르면 인증키에 등록한 도메인과 맞지 않아 403 이 난다 (vite.config.js 참고).
 * 배포 시에는 같은 프록시를 Nginx 등에 두어야 한다.
 */
export const usingVWorld = Boolean(import.meta.env.VITE_VWORLD_KEY);

const vworldStyle = {
  version: 8,
  sources: {
    vworld: {
      type: 'raster',
      tiles: [`${window.location.origin}/vworld/{z}/{y}/{x}.png`],
      tileSize: 256,
      attribution: '© VWorld (국토교통부)',
    },
  },
  layers: [{ id: 'vworld', type: 'raster', source: 'vworld' }],
};

const openFreeMapStyle = 'https://tiles.openfreemap.org/styles/liberty';

export const mapStyle = usingVWorld ? vworldStyle : openFreeMapStyle;

// 서울시청 — 첫 화면 중심
export const INITIAL_VIEW = { center: [126.978, 37.5665], zoom: 11 };
