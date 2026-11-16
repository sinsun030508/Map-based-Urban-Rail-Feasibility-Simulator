// `npm test` (node --test). 지도 위에 띄우는 상세 창의 자리 계산 — 창이 지도를 가리거나 화면 밖으로 사라지지 않게 한다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { clampPosition, initialRect, HEADER_H, MIN_VISIBLE, GAP, DEFAULT_W, MIN_W } from './windowPlacement.js';

test('처음 자리는 결과 패널 바로 왼쪽 — 지도를 덮지 않는다', () => {
  // 1920 폭 화면, 패널은 왼쪽 가장자리가 1420 (폭 500)
  const r = initialRect({ vw: 1920, vh: 1000, panelLeft: 1420 });
  assert.equal(r.w, DEFAULT_W);
  assert.equal(r.x + r.w + GAP, 1420, '창 오른쪽 끝과 패널 사이에 GAP 만 둔다');
  assert.equal(r.y, HEADER_H + GAP, '머리글 밑에서 시작');
  assert.ok(r.y + r.h <= 1000, '아래로 삐져나가지 않는다');
});

test('지도가 좁으면 폭을 줄이되 MIN_W 밑으로는 안 내린다', () => {
  // 패널 왼쪽이 700 이면 자리가 676 — 620 이 들어간다
  assert.equal(initialRect({ vw: 1200, vh: 800, panelLeft: 700 }).w, DEFAULT_W);
  // 패널 왼쪽이 500 이면 자리가 476 — 그만큼으로 줄인다
  assert.equal(initialRect({ vw: 1000, vh: 800, panelLeft: 500 }).w, 476);
  // 패널 왼쪽이 300 이면 자리가 276 — MIN_W 까지만 줄이고 x 는 GAP 아래로 안 간다
  const tight = initialRect({ vw: 700, vh: 800, panelLeft: 300 });
  assert.equal(tight.w, MIN_W);
  assert.equal(tight.x, GAP);
});

test('끌어서 머리글 위로 올리면 머리글 밑에서 멈춘다', () => {
  const p = clampPosition({ x: 300, y: -50 }, { w: 600 }, { vw: 1600, vh: 900 });
  assert.equal(p.y, HEADER_H);
});

test('끌어서 아래로 내려도 제목줄이 화면에 남는다', () => {
  const p = clampPosition({ x: 300, y: 5000 }, { w: 600 }, { vw: 1600, vh: 900 });
  assert.equal(p.y, 900 - 40);
});

test('왼쪽·오른쪽으로 거의 다 내보내도 제목줄 MIN_VISIBLE 은 남는다 — 안 그러면 되찾을 수 없다', () => {
  const left = clampPosition({ x: -9999, y: 100 }, { w: 600 }, { vw: 1600, vh: 900 });
  assert.equal(left.x, MIN_VISIBLE - 600);
  assert.ok(left.x + 600 >= MIN_VISIBLE, '창의 오른쪽 80px 은 보인다');
  const right = clampPosition({ x: 9999, y: 100 }, { w: 600 }, { vw: 1600, vh: 900 });
  assert.equal(right.x, 1600 - MIN_VISIBLE);
});

test('화면 안에 있는 자리는 건드리지 않는다', () => {
  assert.deepEqual(clampPosition({ x: 120, y: 200 }, { w: 600 }, { vw: 1600, vh: 900 }), { x: 120, y: 200 });
});
