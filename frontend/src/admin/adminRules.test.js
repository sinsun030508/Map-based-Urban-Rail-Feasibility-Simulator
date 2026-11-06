// `npm test` (node --test). 회원 관리 화면의 규칙 — 버튼은 서버가 막는 규칙과 같아야 한다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { userActions, deleteMessage, calculatedLine, peakOf, costDraftError } from './adminRules.js';

const row = (o) => ({ id: 2, role: 'USER', nickname: '회원', scenarioCount: 0, ...o });

test('일반 회원은 올릴 수 있고 지울 수 있다', () => {
  const a = userActions(row({}), 1);
  assert.equal(a.nextRole, 'ADMIN');
  assert.equal(a.canDelete, true);
});

test('다른 관리자는 내릴 수 있지만 바로 못 지운다 — 내린 뒤에 지운다', () => {
  const a = userActions(row({ role: 'ADMIN' }), 1);
  assert.equal(a.nextRole, 'USER');
  assert.equal(a.canDelete, false);
  assert.match(a.note, /일반 회원으로 내린 뒤/);
});

test('자기 자신은 아무것도 못 한다 — 「내린 뒤」 안내도 하지 않는다', () => {
  const a = userActions(row({ id: 1, role: 'ADMIN' }), 1);
  assert.equal(a.nextRole, null);
  assert.equal(a.canDelete, false);
  assert.doesNotMatch(a.note, /내린 뒤/);
});

test('삭제 확인은 함께 사라지는 시나리오 수를 말한다', () => {
  assert.match(deleteMessage(row({ scenarioCount: 3 })), /시나리오 3개도 함께/);
  assert.match(deleteMessage(row({ scenarioCount: 3 })), /되돌릴 수 없습니다/);
  assert.doesNotMatch(deleteMessage(row({ scenarioCount: 0 })), /시나리오/);
});

test('추천이 없는 시나리오가 있으면 드러낸다', () => {
  assert.equal(calculatedLine({ calculated: 5, noRecommendation: 2 }), '계산 5개 · 그중 추천 없음 2개');
  assert.equal(calculatedLine({ calculated: 5, noRecommendation: 0 }), '계산 5개');
});

test('막대의 기준값 — 전부 0 이면 그릴 것이 없다', () => {
  assert.equal(peakOf([{ count: 0 }, { count: 4 }, { count: 2 }]), 4);
  assert.equal(peakOf([{ count: 0 }]), 0);
  assert.equal(peakOf([]), 0);
});

const cost = { fixedCost: 1000, costPerKm: 550, costPerStation: 200, source: '시드' };

test('단가 입력 — 서버가 막는 것을 저장 전에 알려 준다', () => {
  assert.equal(costDraftError(cost, { ...cost, costPerKm: '600' }), null);
  assert.match(costDraftError(cost, { ...cost, costPerKm: '0' }), /km단가/);
  assert.match(costDraftError(cost, { ...cost, costPerKm: '' }), /km단가/);
  assert.match(costDraftError(cost, { ...cost, fixedCost: '-1' }), /고정비/);
  assert.match(costDraftError(cost, { ...cost, costPerStation: '1.5' }), /역 단가/);
  assert.match(costDraftError(cost, { ...cost, costPerKm: '600', source: '  ' }), /근거/);
});

test('값도 근거도 그대로면 저장할 것이 없다 — 안내 없이 버튼만 잠근다', () => {
  assert.equal(costDraftError(cost, { ...cost }), '');
  assert.equal(costDraftError(cost, { costPerKm: '550' }), '');
});
