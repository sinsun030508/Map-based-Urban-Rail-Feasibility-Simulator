// `npm test` (node --test). 도로를 따라가는 행의 물 안내 — 새로 놓는 구간만 본다(11-12). 기존 다리는 이미 있다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bridgeCostIncluded, mergeRoadWater, preCalcWaterHint } from './roadWater.js';

const R1 = { waterKm: [{ name: '한강', kind: 'river', km: 0.6, pieces: [[[37.5, 126.9], [37.5, 126.91]]] }], crossings: [{ name: '호수', detoured: true }] };
const R2 = { waterKm: [{ name: '한강', kind: 'river', km: 0.4, pieces: [[[37.6, 126.9], [37.6, 126.91]]] }, { name: '창릉천', kind: 'river', km: 0.1, pieces: [] }], crossings: [] };

test('새 구간마다 받은 물 구간을 이름별로 더한다 — 토막은 이어 붙이고, 호수 우회 제안은 버린다(직선 기준이라 도로 노선과 안 맞는다)', () => {
  const m = mergeRoadWater([R1, R2]);
  assert.deepEqual(m.waterKm.map((w) => [w.name, Number(w.km.toFixed(1))]), [['한강', 1.0], ['창릉천', 0.1]]);
  assert.equal(m.waterKm[0].pieces.length, 2);
  assert.deepEqual(m.crossings, []);
});

test('받은 것이 없으면 null — 새 구간이 없는 행은 물 안내가 없다', () => {
  assert.equal(mergeRoadWater([]), null);
  assert.equal(mergeRoadWater(null), null);
});

test('새 도로의 다리값이 건설비에 드는 수단은 트램·BRT 뿐이다 — 광역급행은 철도라 교량 할증이 없다', () => {
  assert.equal(bridgeCostIncluded({ modeType: 'TRAM' }), true);
  assert.equal(bridgeCostIncluded({ modeType: 'BRT_HIGH' }), true);
  assert.equal(bridgeCostIncluded({ modeType: 'BRT_LOW' }), true);
  assert.equal(bridgeCostIncluded({ modeType: 'EXPRESS_RAIL' }), false);
  assert.equal(bridgeCostIncluded(null), false);
});

// ── 계산 전 물 안내의 덧말(11-22) — 계산 전엔 어떤 수단인지 몰라 그린 직선 전체의 물 위를 알린다. 계산하면 도로를 따라가는 수단은 새로 놓는 구간의 물만 남아 파란 토막이 사라지거나 옮겨간다
test('계산 전이고 물 위 구간이 있으면 그린 직선 기준이라는 덧말을 낸다 — 계산하면 도로 수단은 달라진다고 미리 알린다', () => {
  const hint = preCalcWaterHint(false, 1);
  assert.match(hint, /그린 직선 기준/);
  assert.match(hint, /도로를 따라가는 수단/);
});

test('계산한 뒤에는 덧말이 없다 — 그 줄은 이미 고른 행 기준이다', () => {
  assert.equal(preCalcWaterHint(true, 1), null);
});

test('물 위 구간이 없으면 덧말이 없다 — 줄 자체가 없는데 설명만 남지 않게', () => {
  assert.equal(preCalcWaterHint(false, 0), null);
});
