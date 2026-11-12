// `npm test` (node --test). 도로를 따라가는 행의 물 안내 — 새로 놓는 구간만 본다(11-12). 기존 다리는 이미 있다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bridgeCostIncluded, mergeRoadWater } from './roadWater.js';

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
