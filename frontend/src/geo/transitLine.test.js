import test from 'node:test';
import assert from 'node:assert/strict';
import { transitFeatures, transitBounds } from './transitLine.js';

const walk = { mode: 'WALK', color: null, path: [[126.9, 37.5], [126.91, 37.51]] };
const ride = { mode: 'SUBWAY', color: '009D3E', path: [[126.91, 37.51], [127.0, 37.55], [127.1, 37.49]] };

test('구간마다 선 하나 — 도보를 가르고 노선 색을 싣는다', () => {
  assert.deepEqual(transitFeatures([walk, ride]), [
    { type: 'Feature', properties: { walk: true, color: '#9ca3af' }, geometry: { type: 'LineString', coordinates: walk.path } },
    { type: 'Feature', properties: { walk: false, color: '#009D3E' }, geometry: { type: 'LineString', coordinates: ride.path } },
  ]);
});

test('좌표가 없거나 한 점뿐인 구간은 그리지 않는다 — 없는 길을 직선으로 지어내지 않는다', () => {
  assert.deepEqual(transitFeatures([{ mode: 'BUS' }, { mode: 'BUS', path: [] }, { mode: 'BUS', path: [[127, 37]] }]), []);
  assert.deepEqual(transitFeatures(undefined), []);
});

test('모든 선을 담는 범위 — 선이 없으면 없다', () => {
  assert.deepEqual(transitBounds(transitFeatures([walk, ride])), [[126.9, 37.49], [127.1, 37.55]]);
  assert.equal(transitBounds([]), null);
});
