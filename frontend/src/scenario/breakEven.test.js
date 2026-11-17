// `npm test` (node --test). 추천안이 B/C 1.0 미만인지 — 「1등」 이 「지을 만하다」 로 읽히지 않게 경고를 붙일 때의 판정(11-17).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { belowBreakEven, BREAK_EVEN } from './breakEven.js';

test('0 보다 크고 1.0 보다 작으면 경제성 부족 — 편익이 비용을 못 덮는다', () => {
  assert.equal(belowBreakEven({ bcRatio: 0.5 }), true);
  assert.equal(belowBreakEven({ bcRatio: 0.999 }), true);
  assert.equal(belowBreakEven({ bcRatio: 0.01 }), true);   // 광명~화성 추천안
});

test('정확히 1.0 은 본전이라 경고하지 않고, 그 위도 아니다', () => {
  assert.equal(BREAK_EVEN, 1);
  assert.equal(belowBreakEven({ bcRatio: 1 }), false);
  assert.equal(belowBreakEven({ bcRatio: 1.0001 }), false);
  assert.equal(belowBreakEven({ bcRatio: 6.73 }), false);
});

test('0 은 여기서 다루지 않는다 — 「절감 시간 없음」 은 gainsNothing 이 따로 말한다', () => {
  assert.equal(belowBreakEven({ bcRatio: 0 }), false);
  assert.equal(belowBreakEven({ bcRatio: '0.00' }), false);
});

test('모르는 값은 1 미만이 아니다 — null·없음·행 없음', () => {
  assert.equal(belowBreakEven({ bcRatio: null }), false);
  assert.equal(belowBreakEven({}), false);
  assert.equal(belowBreakEven(null), false);
  assert.equal(belowBreakEven(undefined), false);
});

test('API 가 문자열로 줘도 같다 — BigDecimal 은 문자열로 올 수 있다', () => {
  assert.equal(belowBreakEven({ bcRatio: '0.35' }), true);
  assert.equal(belowBreakEven({ bcRatio: '2.05' }), false);
});
