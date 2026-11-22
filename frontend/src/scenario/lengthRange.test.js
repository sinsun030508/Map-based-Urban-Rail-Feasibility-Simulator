// `npm test` (node --test). 노선 길이가 수단이 쓰이는 범위 밖인가 — 위로는 권장 연장, 아래로는 역간격(11-22).
// 서버 `CostCalculator.withinLength` 와 **같은 경계**여야 지도·굵은 행·백엔드 추천이 같은 안을 가리킨다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { outsideLengthRange } from './lengthRange.js';

const EXPRESS = { maxLengthKm: null, spacingKm: 6.92 };
const BRT = { maxLengthKm: 30, spacingKm: 1.16 };

test('권장 연장을 넘으면 범위 밖이다 — 기존 규칙 그대로', () => {
  assert.equal(outsideLengthRange(BRT, 120), true);
  assert.equal(outsideLengthRange(BRT, 30), false);        // 정확히 상한은 안
  assert.equal(outsideLengthRange(BRT, 29.9), false);
});

test('역간격보다 짧으면 범위 밖이다 — 급행(6.92km)을 1.2km 노선에 내세우지 않는다', () => {
  assert.equal(outsideLengthRange(EXPRESS, 1.2), true);
  assert.equal(outsideLengthRange(EXPRESS, 6.9), true);
  assert.equal(outsideLengthRange(BRT, 0.5), true);
});

test('길이가 역간격과 정확히 같으면 통과한다 — 서버와 같은 경계', () => {
  assert.equal(outsideLengthRange(EXPRESS, 6.92), false);
  assert.equal(outsideLengthRange(EXPRESS, 12.3), false);
});

test('모르는 쪽은 경계가 없다 — 연장·역간격이 null 이면 그 방향으로는 거르지 않는다', () => {
  assert.equal(outsideLengthRange({ maxLengthKm: null, spacingKm: null }, 0.1), false);
  assert.equal(outsideLengthRange({ maxLengthKm: null, spacingKm: null }, 900), false);
  assert.equal(outsideLengthRange({ maxLengthKm: 30, spacingKm: null }, 0.1), false);
});

test('수단 정보나 길이를 모르면 판정하지 않는다 — 못 불러온 것을 범위 밖으로 읽지 않는다', () => {
  assert.equal(outsideLengthRange(undefined, 5), false);
  assert.equal(outsideLengthRange(EXPRESS, null), false);
  assert.equal(outsideLengthRange(EXPRESS, undefined), false);
});

test('App 의 overLength 가 이 판정을 쓴다 — 옛 「연장 초과」 식으로 되돌아가면 지도·굵은 행이 서버 추천과 어긋난다', () => {
  const app = readFileSync(new URL('../App.jsx', import.meta.url), 'utf8');
  assert.match(app, /import \{ outsideLengthRange \} from '\.\/scenario\/lengthRange'/);
  assert.match(app, /const overLength = \(r\) => outsideLengthRange\(/);
  assert.doesNotMatch(app, /savedLength > Number\(limit\)/, '옛 상한만 보는 식이 남아 있다');
});

test('API 가 문자열(BigDecimal)로 줘도 숫자로 견준다', () => {
  assert.equal(outsideLengthRange({ maxLengthKm: '30.0', spacingKm: '1.16' }, 40), true);
  assert.equal(outsideLengthRange({ maxLengthKm: '30.0', spacingKm: '1.16' }, 1.0), true);
  assert.equal(outsideLengthRange({ maxLengthKm: '30.0', spacingKm: '1.16' }, 10), false);
});
