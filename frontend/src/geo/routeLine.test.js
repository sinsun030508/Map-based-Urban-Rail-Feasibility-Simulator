// `npm test` (node --test). 화면 노선 — 그린 꼭짓점과 역을 선 위 거리 순으로 엮고, 양 끝은 종점역이다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { routeThroughStations } from './routeLine.js';

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
