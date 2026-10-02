import { useEffect, useRef } from 'react';
import { MapboxOverlay } from '@deck.gl/mapbox';
import { HexagonLayer } from '@deck.gl/aggregation-layers';
import { api } from '../api/client';

/**
 * 인구밀도 3D — 집계구 인구를 육각형으로 묶어 높이로 세운다.
 *
 * 왜 육각형인가
 *   집계구는 크기가 제각각이라(도심 수십 m ~ 농촌 수 km) 그대로 그리면 면적이
 *   큰 구역이 커 보인다. 고정 크기 육각형에 모아 세면 **밀도** 비교가 된다.
 *   정차역 배치가 고정 반경으로 인구를 세는 것과 같은 이유다.
 *
 * 왜 화면 범위만 받나
 *   집계구가 5만 칸이라 한 번에 받으면 응답이 수 MB 다. 지도를 멈출 때마다
 *   보이는 범위만 다시 받는다. 수집 범위(수도권 위주 77개 시군구) 밖은 비어 있다.
 */
const RADIUS_M = 300;          // 육각형 한 칸 반경
// 1명당 높이(m). 반경 300m 칸에 서울 기준 3,000~10,000명이 모이므로
// 0.25 면 95분위 기둥이 약 2km — 도시 축척에서 밀도 차이가 눈에 들어오는 높이다.
// 처음 쓴 12 는 기둥이 98km 로 솟아 지도와 노선이 완전히 묻혔다.
const ELEVATION_SCALE = 0.25;
const COVERAGE = 0.82;         // 칸 사이를 띄워 지도와 노선이 보이게 한다
const PITCH_3D = 50;

// 낮음(옅은 파랑) → 높음(붉은색). 색은 인구 수가 아니라 순위로 나뉜다
const COLOR_RANGE = [
  [222, 235, 247], [198, 219, 239], [158, 202, 225],
  [107, 174, 214], [66, 146, 198], [226, 72, 58],
];

export default function usePopulation3D(mapRef, enabled) {
  const overlayRef = useRef(null);
  const dataRef = useRef([]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return undefined;

    if (!enabled) {
      if (overlayRef.current) {
        map.removeControl(overlayRef.current);
        overlayRef.current = null;
      }
      dataRef.current = [];
      if (map.getPitch() !== 0) map.easeTo({ pitch: 0, duration: 400 });
      return undefined;
    }

    let alive = true;
    // interleaved 로 넣어야 지도 레이어 사이에 끼워진다.
    // 기본(overlay) 모드는 모든 지도 레이어 위에 그려서 노선이 기둥에 묻힌다
    const overlay = new MapboxOverlay({ interleaved: true, layers: [] });
    map.addControl(overlay);
    overlayRef.current = overlay;
    map.easeTo({ pitch: PITCH_3D, duration: 600 });

    const render = () => {
      if (!alive) return;
      overlay.setProps({
        layers: [
          new HexagonLayer({
            id: 'population-hex',
            // 노선선 아래에 깔아 노선과 정차역이 항상 보이게 한다
            beforeId: map.getLayer('route-line') ? 'route-line' : undefined,
            data: dataRef.current,
            // 서버가 [위도, 경도, 인구] 배열로 보낸다 — 키 이름이 응답의 절반을 차지해서다
            getPosition: (d) => [d[1], d[0]],
            getElevationWeight: (d) => d[2],
            getColorWeight: (d) => d[2],
            elevationAggregation: 'SUM',
            colorAggregation: 'SUM',
            radius: RADIUS_M,
            elevationScale: ELEVATION_SCALE,
            coverage: COVERAGE,
            extruded: true,
            opacity: 0.45,
            colorRange: COLOR_RANGE,
            // 한 칸이 유난히 높으면 나머지가 다 눌려 보인다 — 상위 1% 는 잘라 낸다
            upperPercentile: 99,
            pickable: true,
          }),
        ],
      });
    };

    const load = async () => {
      const b = map.getBounds();
      try {
        const rows = await api.population({
          minLat: b.getSouth(), minLng: b.getWest(),
          maxLat: b.getNorth(), maxLng: b.getEast(),
        });
        if (!alive) return;
        dataRef.current = rows;
        render();
      } catch {
        // 인구 자료가 없어도 지도는 계속 쓸 수 있어야 한다 — 조용히 비워 둔다
        dataRef.current = [];
        render();
      }
    };

    load();
    map.on('moveend', load);
    return () => {
      alive = false;
      map.off('moveend', load);
      if (overlayRef.current) {
        map.removeControl(overlayRef.current);
        overlayRef.current = null;
      }
    };
  }, [mapRef, enabled]);
}
