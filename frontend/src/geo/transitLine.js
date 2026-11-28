/**
 * 실제 대중교통 경로를 지도에 그릴 선으로 바꾼다(11-28 사용자 요청) — 좌표는 TMAP 이 준 것만 쓴다.
 * 좌표가 없는 구간은 비운다: 두 정류장을 직선으로 이으면 가지도 않는 길을 그리는 셈이다.
 */
import { legColor } from '../scenario/transit.js';

export function transitFeatures(legs) {
  return (legs || [])
    .filter((leg) => leg.path?.length > 1)
    .map((leg) => ({
      type: 'Feature',
      properties: { walk: leg.mode === 'WALK', color: legColor(leg) },
      geometry: { type: 'LineString', coordinates: leg.path },
    }));
}

/** 모든 선을 담는 [[서, 남], [동, 북]]. 선이 없으면 null */
export function transitBounds(features) {
  const all = features.flatMap((f) => f.geometry.coordinates);
  if (!all.length) return null;
  const lng = all.map((p) => p[0]);
  const lat = all.map((p) => p[1]);
  return [[Math.min(...lng), Math.min(...lat)], [Math.max(...lng), Math.max(...lat)]];
}
