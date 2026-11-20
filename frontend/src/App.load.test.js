// `npm test` (node --test, 의존성 없음). React 를 그리지 않고 **소스 모양**만 본다.
//
// 저장된 시나리오를 열면(`load`) 비교표에서 고른 행(`selectedResult`)을 비운다(11-21 코드 점검). 안 비우면 시나리오 A 에서 트램 행을 고른 뒤
// B 를 열 때 지도가 B 의 추천안이 아니라 트램을 그린다 — 「지도가 그리는 안·표의 굵은 행·추천이 같은 안」 이어야 한다는 규칙이 새로 연 노선에서 깨진다.
// 노선을 고치면 비우는 `reset()` 은 `load` 가 부르지 않는다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const text = readFileSync(fileURLToPath(new URL('./App.jsx', import.meta.url)), 'utf8');

/** 함수 본문 — `async function name(` 부터 다음 맨 바깥(들여쓰기 2칸) 함수 앞까지 */
function body(name) {
  const start = text.indexOf(`  async function ${name}(`);
  assert.ok(start >= 0, `${name} 함수가 없다`);
  const next = text.slice(start + 1).search(/\n {2}(async )?function \w+\(/);
  return next < 0 ? text.slice(start) : text.slice(start, start + 1 + next);
}

test('저장된 시나리오를 열면 비교표에서 고른 행을 비운다 — 새로 연 노선은 그 노선의 추천안부터 그린다', () => {
  assert.ok(body('load').includes('setSelectedResult(null)'), 'load 가 고른 행을 비우지 않는다');
});
