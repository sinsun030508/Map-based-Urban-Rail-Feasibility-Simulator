// `npm test` (node --test). 패널의 이용객 한 줄 — 경전철만 보정해서 행마다 값이 다를 수 있다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { baseRiders, brtFactor, lightRailFactor } from './demandNote.js';

const r = (modeType, estimatedRidership) => ({ modeType, estimatedRidership });

test('경전철이 아닌 행의 이용객을 기준으로 쓴다 — 경전철이 첫 행이어도', () => {
  assert.equal(baseRiders([r('LIGHT_RAIL', 4600), r('HEAVY_METRO', 10000)]), 10000);
});

test('경전철뿐이면 그 값이라도 쓴다', () => {
  assert.equal(baseRiders([r('LIGHT_RAIL', 4600)]), 4600);
});

test('모르면 null', () => {
  assert.equal(baseRiders([r('HEAVY_METRO', null)]), null);
  assert.equal(baseRiders([]), null);
});

test('경전철 배수는 결과에서 잰다 — 경전철이나 기준 행이 없으면 null', () => {
  assert.equal(lightRailFactor([r('HEAVY_METRO', 10000), r('LIGHT_RAIL', 4600)]), 0.46);
  assert.equal(lightRailFactor([r('HEAVY_METRO', 10000)]), null);
  assert.equal(lightRailFactor([r('LIGHT_RAIL', 4600)]), null);
});

test('BRT 도 기준 행이 아니다 — BRT 가 첫 행이어도 중전철 값을 쓴다', () => {
  assert.equal(baseRiders([r('BRT_HIGH', 3500), r('LIGHT_RAIL', 4600), r('HEAVY_METRO', 10000)]), 10000);
});

test('BRT 배수도 결과에서 잰다 — 두 등급 중 아는 쪽', () => {
  assert.equal(brtFactor([r('HEAVY_METRO', 10000), r('BRT_LOW', 3500)]), 0.35);
  assert.equal(brtFactor([r('HEAVY_METRO', 10000)]), null);
});
