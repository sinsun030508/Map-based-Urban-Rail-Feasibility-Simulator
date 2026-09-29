/**
 * 지도 배경.
 * VWorld 는 인증키와 도메인 등록이 필요해서, 키가 없으면 OpenFreeMap 으로 떨어뜨린다.
 * 키 발급: https://www.vworld.kr → 오픈API → 인증키 발급 (localhost 등록)
 * .env 에 VITE_VWORLD_KEY=... 를 넣으면 VWorld 를 쓴다.
 */
const VWORLD_KEY = import.meta.env.VITE_VWORLD_KEY;

export const usingVWorld = Boolean(VWORLD_KEY);

const vworldStyle = {
  version: 8,
  sources: {
    vworld: {
      type: 'raster',
      tiles: [
        `https://api.vworld.kr/req/wmts/1.0.0/${VWORLD_KEY}/Base/{z}/{y}/{x}.png`,
      ],
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
