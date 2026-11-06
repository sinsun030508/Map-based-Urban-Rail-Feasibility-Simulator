// `npm test` (node --test). 산 구간 안내 문구 — 「산 없음」 과 「지형 자료 없음」, 「0원」 과 「모름」 을 섞지 않는다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mountainNote } from './mountainNote.js';

const RES = {
  available: true,
  mountains: [
    { index: 1, tunnelKm: 2.0, detoured: true, addedKm: 5.1, reason: null, pieces: [] },
    { index: 2, tunnelKm: 1.2, detoured: false, addedKm: 0, reason: '35‰ 를 넘지 않고 1.5배 안에서 돌아갈 길이 없습니다', pieces: [] },
  ],
  deltas: [
    { mode: 'HEAVY_METRO', structure: 'ELEVATED', tunnelExtraCost: 1040, detourExtraCost: 3195, detourExtraMin: 9.4 },
    { mode: 'TRAM', structure: 'AT_GRADE', tunnelExtraCost: null, detourExtraCost: null, detourExtraMin: 13.8 },
  ],
};

test('계산 전에는 수단과 무관한 길이만 — 비용 칸이 없다', () => {
  const n = mountainNote(RES, null);
  assert.equal(n.cost, null);
  assert.match(n.lengths, /터널 3\.2km/);
  assert.match(n.lengths, /돌아가면 \+5\.1km/);
  assert.equal(n.canApply, true);
});

test('고른 행의 단가로 두 안의 건설비·소요시간을 적는다', () => {
  const n = mountainNote(RES, { modeType: 'HEAVY_METRO', structureType: 'ELEVATED' });
  assert.match(n.cost, /뚫기 \+1,040억/);
  assert.match(n.cost, /돌아가기 \+3,195억 · \+9\.4분/);
});

test('트램은 지하 단가가 없어 터널비를 모른다 — 0원이 아니다', () => {
  const n = mountainNote(RES, { modeType: 'TRAM', structureType: 'AT_GRADE' });
  assert.match(n.cost, /지하 단가가 없어 터널비를 모릅니다/);
  assert.doesNotMatch(n.cost, /\+0억/);
});

test('BRT 는 도로 기준이라 판정하지 않는다', () => {
  const n = mountainNote(RES, { modeType: 'BRT_HIGH', structureType: 'AT_GRADE' });
  assert.match(n.cost, /BRT 는 도로 기준이라 산 판정을 하지 않습니다/);
});

test('지형 자료가 없으면 「산 없음」 이 아니라 「안 봤다」 고 적는다', () => {
  const n = mountainNote({ available: false, mountains: [], deltas: [] }, null);
  assert.match(n.lengths, /지형 자료가 없어 산 구간을 보지 않았습니다/);
  assert.equal(n.canApply, false);
});

test('산이 없으면 안내하지 않는다', () => {
  assert.equal(mountainNote({ available: true, mountains: [], deltas: [] }, null), null);
  assert.equal(mountainNote(null, null), null);
});

test('돌아갈 구간이 하나도 없으면 「1.5배」 로 못박지 않는다 — 경유점 50개로 빠진 구간도 있다', () => {
  const n = mountainNote({ available: true, deltas: [], mountains: [
    { index: 1, tunnelKm: 1, detoured: false, addedKm: 0, reason: '돌아가면 경유점이 50개를 넘어 저장할 수 없습니다', pieces: [] },
  ] }, null);
  assert.match(n.lengths, /돌아갈 수 있는 구간이 없습니다/);
  assert.doesNotMatch(n.lengths, /1\.5배/);
  assert.equal(n.canApply, false);
});

test('돌면 손해인 구간은 뚫는다고 적는다 — 「돌아갈 길이 없다」 와 다른 말이다', () => {
  const costlier = { index: 2, tunnelKm: 0.4, detoured: false, costlier: true, addedKm: 0, reason: '손해', pieces: [] };
  const some = mountainNote({ available: true, deltas: [], mountains: [RES.mountains[0], costlier] }, null);
  assert.match(some.lengths, /1곳은 돌아가면 \+5\.1km/);
  assert.match(some.lengths, /1곳은 돌아가는 편이 비싸 뚫습니다/);
  assert.equal(some.canApply, true);

  const all = mountainNote({ available: true, deltas: [], mountains: [costlier] }, null);
  assert.match(all.lengths, /돌아가는 편이 비싸 뚫습니다/);
  assert.doesNotMatch(all.lengths, /돌아갈 수 있는 구간이 없습니다/);
  assert.equal(all.canApply, false);
});
