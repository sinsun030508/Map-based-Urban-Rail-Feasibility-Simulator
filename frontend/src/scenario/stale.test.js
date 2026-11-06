// `npm test` (node --test). 낡은 결과 판정 — 단가는 시각 칸이 없어 「쓴 단가가 내려갔나」 로 본다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { isStale } from './stale.js';

test('기준값이 계산 뒤에 바뀌면 낡았다', () => {
  assert.equal(isStale('2026-11-01T00:00:00', '2026-11-02T00:00:00'), true);
  assert.equal(isStale('2026-11-03T00:00:00', '2026-11-02T00:00:00'), false);
});

test('시각은 그대로여도 쓴 단가가 내려갔으면 낡았다', () => {
  assert.equal(isStale('2026-11-03T00:00:00', '2026-11-02T00:00:00', true), true);
});

test('계산한 적이 없으면 낡았다고 하지 않는다', () => {
  assert.equal(isStale(null, '2026-11-02T00:00:00', true), false);
});
