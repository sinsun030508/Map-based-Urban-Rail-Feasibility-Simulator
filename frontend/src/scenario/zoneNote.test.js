// `npm test` (node --test). 확인 필요 구역 안내 — 선이 지나는 구역과 피해 가는 안(11-12).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { zoneNote } from './zoneNote.js';

const GB = { name: '경복궁 일대', severity: 'REVIEW', note: '역사문화환경 보존지역', radiusKm: 1 };
const PAJU = { name: '파주 민통선 이북', severity: 'BLOCKED', note: '군사기지법 통제보호구역', radiusKm: 5 };

test('지나는 구역마다 한 줄 — 심의·협의와 사실상 불가를 가른다, 이름 뒤에 조사를 붙이지 않는다', () => {
  const n = zoneNote({ zones: [GB, PAJU], crossings: [] });
  assert.deepEqual(n.zones, ['경복궁 일대 — 심의·협의 필요(역사문화환경 보존지역)', '파주 민통선 이북 — 사실상 불가(군사기지법 통제보호구역)']);
  assert.equal(n.canApply, false);
});

test('피할 수 있으면 늘어나는 길이와 바꾸기, 못 피하면 서버가 준 이유', () => {
  const n = zoneNote({
    zones: [GB, PAJU],
    crossings: [
      { name: '경복궁 일대', detoured: true, addedKm: 0.84, reason: null },
      { name: '파주 민통선 이북', detoured: false, addedKm: 0, reason: '구역을 피해 갈 길을 찾지 못했습니다' },
    ],
  });
  assert.deepEqual(n.detours, ['경복궁 일대 — 피해 가면 +0.8km', '파주 민통선 이북 — 구역을 피해 갈 길을 찾지 못했습니다']);
  assert.equal(n.canApply, true);
});

test('구역을 안 지나거나 응답이 없으면 칸이 없다', () => {
  assert.equal(zoneNote({ zones: [], crossings: [] }), null);
  assert.equal(zoneNote(null), null);
});
