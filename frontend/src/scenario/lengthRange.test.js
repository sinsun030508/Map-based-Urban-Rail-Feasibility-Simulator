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
  assert.match(app, /import \{[^}]*\boutsideLengthRange\b[^}]*\} from '\.\/scenario\/lengthRange'/);
  assert.match(app, /const overLength = \(r\) => outsideLengthRange\(/);
  assert.doesNotMatch(app, /savedLength > Number\(limit\)/, '옛 상한만 보는 식이 남아 있다');
});

test('API 가 문자열(BigDecimal)로 줘도 숫자로 견준다', () => {
  assert.equal(outsideLengthRange({ maxLengthKm: '30.0', spacingKm: '1.16' }, 40), true);
  assert.equal(outsideLengthRange({ maxLengthKm: '30.0', spacingKm: '1.16' }, 1.0), true);
  assert.equal(outsideLengthRange({ maxLengthKm: '30.0', spacingKm: '1.16' }, 10), false);
});

// ── 기본으로 내세울 행 — 서버 `CostCalculator.recommended` 와 같은 규칙(범위 안이 하나도 없으면 범위를 무시한다)
import { defaultRow } from './lengthRange.js';

const row = (modeType, bcRatio) => ({ modeType, bcRatio });
const noGain = (r) => r.bcRatio != null && Number(r.bcRatio) <= 0;

test('범위 안에 맞는 안이 있으면 그중 B/C 최고안이다 — 범위 밖의 더 높은 안은 내세우지 않는다', () => {
  const rows = [row('BRT', 7.8), row('DOUBLE', 1.7), row('HEAVY', 1.2)];
  const outside = (r) => r.modeType === 'BRT';
  assert.equal(defaultRow(rows, outside, noGain).modeType, 'DOUBLE');
});

test('전부 범위 밖이면 범위를 무시하고 B/C 최고안을 쓴다 — 0.5km 노선에서 지도가 비지 않게(서버 추천과 같은 안)', () => {
  const rows = [row('EXPRESS', 0.11), row('HEAVY', 0.05), row('TRAM', 0.01)];
  assert.equal(defaultRow(rows, () => true, noGain).modeType, 'EXPRESS');
});

test('이득이 없는 안(B/C 0)은 범위와 상관없이 내세우지 않는다 — 전부 0 이면 없다', () => {
  assert.equal(defaultRow([row('A', 0), row('B', 0)], () => false, noGain), undefined);
  assert.equal(defaultRow([row('A', 0), row('B', 0.2)], () => true, noGain).modeType, 'B');
});

test('B/C 를 모르면(null) 정렬 맨 뒤다 — 아는 안이 있으면 그것이 먼저', () => {
  const rows = [row('A', null), row('B', 0.3)];
  assert.equal(defaultRow(rows, () => false, noGain).modeType, 'B');
  assert.equal(defaultRow([row('A', null)], () => false, noGain).modeType, 'A');
});

test('빈 결과는 undefined', () => {
  assert.equal(defaultRow([], () => false, noGain), undefined);
});

test('App 의 shown 이 defaultRow 를 쓴다 — 옛 「범위 필터만」 식으로 되돌아가면 짧은 노선에서 지도가 빈다', () => {
  const app = readFileSync(new URL('../App.jsx', import.meta.url), 'utf8');
  assert.match(app, /defaultRow\(results, overLength, gainsNothing\)/);
  assert.doesNotMatch(app, /filter\(\(r\) => !overLength\(r\) && !gainsNothing\(r\)\)/);
});

// ── 수송능력 초과(feasible=false)는 내세우지 않는다(11-25 코드 리뷰) — 서버 `recommended` 는 빼는데 화면은 안 빼, B/C 1위가 수송능력 초과인 노선에서
//    지도·패널·굵은 행은 그 안을, 서버 추천(시나리오 비교·관리자 통계)은 다음 안을 가리켰다
import { isFeasible } from './lengthRange.js';

const fr = (modeType, bcRatio, feasible) => ({ modeType, bcRatio, feasible });

test('수송능력 초과인 안은 B/C 가 가장 높아도 기본으로 그리지 않는다 — 서버 추천과 같은 안', () => {
  const rows = [fr('BRT_HIGH', 9.1, false), fr('EXPRESS_RAIL', 6.8, true), fr('TRAM', 2.0, true)];
  assert.equal(defaultRow(rows, () => false, noGain).modeType, 'EXPRESS_RAIL');
});

test('전부 수송능력 초과면 기본 행이 없다 — 서버도 그때 추천을 비운다', () => {
  assert.equal(defaultRow([fr('BRT_HIGH', 9.1, false), fr('BRT_LOW', 3.0, false)], () => false, noGain), undefined);
});

test('수송능력 판정이 없는 행(옛 결과·null)은 안으로 본다 — 모르는 것을 초과로 읽지 않는다', () => {
  assert.equal(isFeasible({ feasible: true }), true);
  assert.equal(isFeasible({ feasible: false }), false);
  assert.equal(isFeasible({}), true);
  assert.equal(isFeasible({ feasible: null }), true);
});

test('비교표의 굵은 행도 같은 판정을 쓴다 — 굵은 행만 초과 안을 강조하면 지도와 어긋난다', () => {
  const table = readFileSync(new URL('./CostTable.jsx', import.meta.url), 'utf8');
  assert.match(table, /import \{[^}]*\bisFeasible\b[^}]*\} from '\.\/lengthRange'/);
  assert.match(table, /const pickable = candidates\.filter\(isFeasible\);/);
  assert.match(table, /const best = \(overLength \? pickable\.find\(.*\) : null\) \|\| pickable\[0\];/);
});
