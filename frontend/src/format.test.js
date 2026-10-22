// `npm test` (node --test, 의존성 없음). 화면 숫자 표기 중 **뜻이 갈리는 것**만 고정한다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bc } from './format.js';

test('B/C 0 은 0.00 — 아끼는 시간이 없다는 뜻이다', () => {
  assert.equal(bc(0), '0.00');
});

test('작지만 양수인 B/C 는 0.00 으로 찍지 않는다', () => {
  // 광명-화성 복선전철 지하 0.002 가 0.00 으로 찍혀 "절감 없음" 과 구별이 안 됐다
  assert.equal(bc(0.002), '<0.01');
  assert.equal(bc(0.0049), '<0.01');
  assert.equal(bc('0.004'), '<0.01');     // API 는 BigDecimal 을 문자열로 줄 때가 있다
});

test('나머지는 소수 둘째 자리', () => {
  assert.equal(bc(0.005), '0.01');
  assert.equal(bc(2.047), '2.05');
});

test('모르면 대시', () => {
  assert.equal(bc(null), '—');
  assert.equal(bc(undefined), '—');
});
