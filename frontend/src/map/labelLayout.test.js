// `npm test` (node --test). 역 이름표 겹침 — 서울~부산은 역이 촘촘한 양 끝에서 이름이 겹쳐 읽을 수 없었다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { visibleLabels } from './labelLayout.js';

const box = (id, x, y, priority, w = 40, h = 14) => ({ id, x, y, w, h, priority });

test('겹치지 않으면 모두 보인다', () => {
  assert.deepEqual([...visibleLabels([box(1, 0, 0, 1), box(2, 0, 30, 1)])].sort(), [1, 2]);
});

test('겹치면 이용객이 많은 역의 이름만 남긴다', () => {
  const shown = visibleLabels([box(1, 0, 0, 100), box(2, 10, 5, 900)]);
  assert.deepEqual([...shown], [2]);
});

test('같은 우선순위면 앞쪽 역을 남긴다 — 매번 같은 답이어야 지도를 움직일 때 깜빡이지 않는다', () => {
  assert.deepEqual([...visibleLabels([box(2, 10, 5, 5), box(1, 0, 0, 5)])], [1]);
});

test('살짝 떨어져 있어도 글자가 붙어 보이면 겹친 것으로 본다', () => {
  // 오른쪽 끝 40 과 다음 왼쪽 41 — 1px 틈은 읽기 어렵다
  assert.deepEqual([...visibleLabels([box(1, 0, 0, 2), box(2, 41, 0, 1)])], [1]);
  assert.deepEqual([...visibleLabels([box(1, 0, 0, 2), box(2, 50, 0, 1)])].sort(), [1, 2]);
});

test('숨긴 이름표에 가려 다른 이름표가 숨지 않는다 — 놓인 것끼리만 견준다', () => {
  // 1 이 남고 2 는 1 과 겹쳐 숨는다. 3 은 2 와만 겹치므로 보여야 한다
  const shown = visibleLabels([box(1, 0, 0, 3), box(2, 30, 0, 2), box(3, 60, 0, 1)]);
  assert.deepEqual([...shown].sort(), [1, 3]);
});
