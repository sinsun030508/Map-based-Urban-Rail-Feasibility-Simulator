// `npm test` (node --test). 산·물·구역을 물을 선 — 지도에 지금 그려진 선(11-26 사용자 요청).
// 철도 행은 정차역이 큰길로 옮겨져 지도 선이 그 역들을 지나는데, 찍은 직선으로 물어 갈색·파란 토막이 선 밖 허공에 떴다
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { drawnLine } from './drawnLine.js';

const points = [[127.0, 37.5], [127.0, 37.6]];
const station = (lng, lat) => ({ longitude: lng, latitude: lat });

test('철도 행은 역을 잇는 선 — 지도에 그린 선과 같다(종점역에서 시작해 종점역에서 끝난다)', () => {
  const row = { modeType: 'HEAVY_METRO', stations: [station(127.003, 37.5), station(127.004, 37.55), station(127.003, 37.6)] };
  assert.deepEqual(drawnLine(points, row), [[127.003, 37.5], [127.004, 37.55], [127.003, 37.6]]);
});

test('계산 전(행 없음)·역이 둘 미만이면 찍은 선 그대로', () => {
  assert.equal(drawnLine(points, null), points);
  assert.equal(drawnLine(points, { modeType: 'HEAVY_METRO', stations: [station(127.0, 37.55)] }), points);
});

test('도로를 따라가는 행은 이 선을 쓰지 않는다 — 새로 놓는 구간만 따로 묻는다(null)', () => {
  const row = { modeType: 'TRAM', routeLine: [{ road: true, points: [[37.5, 127.0], [37.6, 127.0]] }], stations: [station(127.0, 37.5), station(127.0, 37.6)] };
  assert.equal(drawnLine(points, row), null);
});
