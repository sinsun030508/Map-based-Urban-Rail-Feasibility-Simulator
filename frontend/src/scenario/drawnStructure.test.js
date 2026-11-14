// `npm test` (node --test). 안내(산·물)가 따를 구조 — 지도에 그린 행이 있으면 그 행의 구조, 없으면(계산 전) 선택창의 구조(11-15).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { drawnStructure } from './drawnStructure.js';

test('계산 뒤에는 지도에 그린 행의 구조를 따른다 — 선택창이 지하여도 고가 행을 그렸으면 고가', () => {
  assert.equal(drawnStructure({ modeType: 'HEAVY_METRO', structureType: 'ELEVATED' }, 'UNDERGROUND'), 'ELEVATED');
  assert.equal(drawnStructure({ modeType: 'TRAM', structureType: 'AT_GRADE' }, 'UNDERGROUND'), 'AT_GRADE');
  assert.equal(drawnStructure({ modeType: 'HEAVY_METRO', structureType: 'UNDERGROUND' }, 'ELEVATED'), 'UNDERGROUND');
});

test('그린 행이 없으면(계산 전) 선택창의 구조 — 행이 구조를 안 갖고 있어도 마찬가지', () => {
  assert.equal(drawnStructure(null, 'UNDERGROUND'), 'UNDERGROUND');
  assert.equal(drawnStructure(undefined, 'ELEVATED'), 'ELEVATED');
  assert.equal(drawnStructure({ modeType: 'TRAM' }, 'AT_GRADE'), 'AT_GRADE');
});
