import { distanceKm } from './haversine';

const KM_PER_DEG_LAT = 110.574;

/** 위경도를 km 평면으로 — 투영 계산을 위도 보정해서 한다 (서버 StationPlanner 와 같은 방식) */
function toKm([lng, lat], lat0) {
  return [lng * 111.320 * Math.cos((lat0 * Math.PI) / 180), lat * KM_PER_DEG_LAT];
}

/**
 * 점이 그린 선 위 어디쯤인지 — 시작점에서부터의 거리(km).
 *
 * 선분마다 수직으로 내려 가장 가까운 자리를 찾는다. 꼭짓점에만 붙이면 역이
 * 선분 한가운데 있을 때 순서가 뒤집힌다.
 */
export function alongKm(points, cum, point) {
  let best = { d: Infinity, at: 0 };
  for (let i = 1; i < points.length; i += 1) {
    const lat0 = (points[i - 1][1] + points[i][1]) / 2;
    const a = toKm(points[i - 1], lat0);
    const b = toKm(points[i], lat0);
    const p = toKm(point, lat0);
    const vx = b[0] - a[0];
    const vy = b[1] - a[1];
    const len2 = vx * vx + vy * vy;
    const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, ((p[0] - a[0]) * vx + (p[1] - a[1]) * vy) / len2));
    const dx = a[0] + t * vx - p[0];
    const dy = a[1] + t * vy - p[1];
    const d = Math.hypot(dx, dy);
    if (d < best.d) best = { d, at: cum[i - 1] + t * (cum[i] - cum[i - 1]) };
  }
  return best.at;
}

/**
 * 화면에 그릴 노선 — **그린 선을 따라가되 역이 있는 자리로 들렀다 온다.**
 *
 * 왜 필요한가: `StationPlanner` 가 역을 선에서 **옆으로 최대 0.5km** 비켜 인구가 많은
 * 쪽에 놓는다. 그런데 지도는 사용자가 찍은 점만 이어 그려서, 역이 선 밖에 떠 있고
 * **선은 가지도 않는 길**을 보여 줬다. 비용·편익은 이미 돌아간 길이로 계산한다
 * (`CostCalculator` 의 `routeKm = lengthKm + plan.detourKm()`) — 지도만 직선이면
 * 화면과 숫자가 서로 다른 노선을 말하게 된다.
 *
 * **역만 이으면 안 된다.** 경유점을 찍어 꺾어 그린 노선에서 역이 두 개뿐이면
 * (복선전철·급행처럼 역간격이 넓은 수단) 꺾임이 통째로 사라져 사용자가 그린 모양이
 * 없어진다. 그래서 그린 꼭짓점과 역을 **선 위 거리 순으로 엮는다.**
 *
 * 역이 2개 미만이면(비교표에서 아무것도 안 고른 상태) 그린 선을 그대로 돌려준다.
 */
export function routeThroughStations(points, stations) {
  if (!points || points.length < 2) return points || [];
  const placed = (stations || [])
    .map((s) => [Number(s.longitude), Number(s.latitude)])
    .filter((c) => Number.isFinite(c[0]) && Number.isFinite(c[1]));
  if (placed.length < 2) return points;

  const cum = [0];
  for (let i = 1; i < points.length; i += 1) {
    cum.push(cum[i - 1] + distanceKm(points[i - 1], points[i]));
  }

  const nodes = points.map((p, i) => ({ at: cum[i], p }));
  placed.forEach((c) => nodes.push({ at: alongKm(points, cum, c), p: c }));
  nodes.sort((a, b) => a.at - b.at);

  // 겹치는 점을 지운다 — **종점은 사용자가 찍은 점에 고정**이라 그린 점과 역이 같은 자리다.
  // 그대로 두면 길이 0 짜리 조각이 생겨 지도가 선 끝을 이상하게 그린다
  const out = [];
  nodes.forEach(({ p }) => {
    const last = out[out.length - 1];
    if (!last || distanceKm(last, p) > 1e-6) out.push(p);
  });
  return out;
}
