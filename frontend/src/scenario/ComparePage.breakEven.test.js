// `npm test` (node --test, 의존성 없음). React 를 그리지 않고 **소스 모양**만 본다.
//
// 시나리오 비교의 「추천안 B/C」 도 패널(`CostTable`)처럼 1.0 미만이면 말해 준다(11-19). 판정 경계(정확히 1.0·0·null)는
// `breakEven.test.js` 가 고정한다 — 여기서는 **비교 화면이 그 판정을 실제로 쓰는지**만 본다. 안 쓰면(import 만 남기거나 줄이 지워지면)
// 패널은 「B/C 0.41 로 1.0 미만」 이라 하는데 같은 안이 비교 화면에서는 「1등」 으로만 읽힌다. 화면을 그려 보지 않는 테스트라 이 정도가 한계다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const text = readFileSync(fileURLToPath(new URL('./ComparePage.jsx', import.meta.url)), 'utf8');

test('비교 화면은 breakEven.js 의 판정을 가져다 쓴다 — 자기 기준(< 1)을 또 두지 않는다', () => {
  assert.match(text, /import \{ belowBreakEven \} from '\.\/breakEven'/);
  assert.doesNotMatch(text, /bcRatio\)? *< *1\b/);
});

test('추천안 B/C 칸의 ⚠ 와 표 아래 설명 줄이 둘 다 같은 판정을 쓴다', () => {
  // 칸: `belowBreakEven(b)` (그 칸의 추천안), 설명 줄: `belowBreakEven(bestOf(d))` (고른 노선 중 하나라도)
  assert.ok(text.includes('belowBreakEven(b) &&'), '칸의 ⚠ 가 판정을 안 쓴다');
  assert.ok(text.includes('chosen.some((d) => belowBreakEven(bestOf(d)))'), '설명 줄이 판정을 안 쓴다');
});
