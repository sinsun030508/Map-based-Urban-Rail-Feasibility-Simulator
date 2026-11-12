// `npm test` (node --test). 화면 노선 — 그린 꼭짓점과 역을 선 위 거리 순으로 엮고, 양 끝은 종점역이다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { roadSummary, routeFeatures, routeThroughStations } from './routeLine.js';

const st = ([lng, lat]) => ({ longitude: lng, latitude: lat });

test('종점역을 큰길로 옮겼으면 선이 그 역에서 시작해 그 역에서 끝난다 — 건물 위 찍은 점으로 꺾여 들어가지 않는다(11-12)', () => {
  // 남북으로 꺾어 그린 선 — 출발·도착은 단지 안, 종점역은 동쪽 큰길(약 100m) 위
  const points = [[126.94, 37.52], [126.945, 37.51], [126.94, 37.50]];
  const first = [126.9411, 37.52];
  const mid = [126.9455, 37.511];
  const last = [126.9411, 37.50];
  const line = routeThroughStations(points, [st(first), st(mid), st(last)]);

  assert.deepEqual(line[0], first);
  assert.deepEqual(line[line.length - 1], last);
  assert.ok(!line.some((p) => p[0] === 126.94), '찍은 양 끝은 선에서 빠진다');
  // 그린 꺾임(가운데 꼭짓점)은 남는다
  assert.ok(line.some((p) => p[0] === 126.945 && p[1] === 37.51));
});

test('종점역이 찍은 점 그대로면 예전과 같다 — 길이 0 조각 없이', () => {
  const points = [[126.94, 37.52], [126.94, 37.50]];
  const line = routeThroughStations(points, [st(points[0]), st([126.9405, 37.51]), st(points[1])]);
  assert.deepEqual(line, [[126.94, 37.52], [126.9405, 37.51], [126.94, 37.50]]);
});

test('역이 둘 미만이면 그린 선 그대로', () => {
  const points = [[126.94, 37.52], [126.94, 37.50]];
  assert.equal(routeThroughStations(points, []), points);
});

// 서버가 준 노선 — 트램·BRT·광역급행은 기존 도로를 따라간다(11-12). 구간은 {road, points: [[위도, 경도], …]}
const PIECES = [
  { road: true, points: [[37.50, 126.90], [37.53, 126.90], [37.53, 126.91]] },
  { road: false, points: [[37.53, 126.91], [37.50, 126.91]] },
];

test('서버가 노선 모양을 주면 그대로 그린다 — 기존 도로는 실선, 새 도로는 점선 표시, 좌표는 [경도, 위도]', () => {
  const f = routeFeatures([[126.90, 37.50], [126.91, 37.50]], [], PIECES);
  assert.equal(f.length, 2);
  assert.deepEqual(f.map((x) => x.properties.road), [true, false]);
  assert.deepEqual(f[0].geometry.coordinates[0], [126.90, 37.50]);
  assert.deepEqual(f[1].geometry.coordinates[1], [126.91, 37.50]);
});

test('노선 모양이 없으면(철도 행·계산 전) 예전처럼 그린 점과 역을 엮은 선 하나 — 실선', () => {
  const points = [[126.94, 37.52], [126.94, 37.50]];
  const f = routeFeatures(points, [], null);
  assert.equal(f.length, 1);
  assert.equal(f[0].properties.road, true);
  assert.deepEqual(f[0].geometry.coordinates, points);
  assert.equal(routeFeatures(points, [], []).length, 1);
});

test('기존 도로·새 도로 길이를 따로 잰다 — 노선 모양이 없으면 null', () => {
  const s = roadSummary(PIECES);
  assert.ok(Math.abs(s.roadKm - (3.336 + 0.880)) < 0.02, String(s.roadKm));
  assert.ok(Math.abs(s.newKm - 3.336) < 0.01, String(s.newKm));
  assert.equal(roadSummary(null), null);
  assert.equal(roadSummary([]), null);
});
