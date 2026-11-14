// `npm test` (node --test). 도로를 따라가는 행의 확인 필요 구역 — 새로 놓는 구간만 본다(11-14). 기존 도로는 이미 있다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mergeRoadZones } from './roadZone.js';
import { zoneNote } from './zoneNote.js';

const GB = { name: '경복궁 일대', severity: 'REVIEW', note: '역사문화환경 보존지역', radiusKm: 1 };
const CD = { name: '창덕궁·종묘 일대', severity: 'REVIEW', note: null, radiusKm: 1 };

test('새 구간마다 받은 구역을 이름으로 합친다 — 같은 구역은 한 번, 순서는 처음 닿는 순, 피해 가는 제안은 버린다', () => {
  const m = mergeRoadZones([
    { zones: [GB], crossings: [{ name: '경복궁 일대', detoured: true, addedKm: 0.5 }] },
    { zones: [GB, CD], crossings: [] },
  ]);
  assert.deepEqual(m.zones.map((z) => z.name), ['경복궁 일대', '창덕궁·종묘 일대']);
  // 직선 구간을 도는 제안이라 도로 노선과 안 맞는다 — 「바꾸기」 도 없다
  assert.deepEqual(m.crossings, []);
  assert.equal(zoneNote(m).canApply, false);
  assert.deepEqual(zoneNote(m).detours, []);
});

test('받은 것이 없으면 null — 새 구간이 없거나(기존 도로만) 응답이 없는 행은 구역 안내가 없다', () => {
  assert.equal(mergeRoadZones([]), null);
  assert.equal(mergeRoadZones(null), null);
  assert.equal(zoneNote(mergeRoadZones([{ zones: [], crossings: [] }])), null);
});
