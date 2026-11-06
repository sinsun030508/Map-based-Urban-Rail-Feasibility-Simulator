// `npm test` (node --test). 비용 구성·역별 이용객 — 「0」 과 「모름」 을 섞지 않는다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { costShares, stationRows } from './breakdown.js';

test('비용 구성은 세 몫과 비율이다', () => {
  const s = costShares({ fixed: 1000, length: 8000, stations: 1000 });
  assert.deepEqual(s.map((x) => x.pct), ['10%', '80%', '10%']);
  assert.deepEqual(s.map((x) => x.value), [1000, 8000, 1000]);
});

test('작지만 양수인 몫은 0% 로 찍지 않는다', () => {
  const s = costShares({ fixed: 3, length: 9994, stations: 3 });
  assert.equal(s[0].pct, '<1%');
});

test('구성을 모르면 그리지 않는다 — 0% 가 아니다', () => {
  assert.equal(costShares(null), null);
  assert.equal(costShares(undefined), null);
  assert.equal(costShares({ fixed: 0, length: 0, stations: 0 }), null);
});

const st = (sequence, name, estimatedDailyUsers) => ({ sequence, name, estimatedDailyUsers });

test('역은 이용객이 많은 순으로 상위만 — 같으면 노선 순서다', () => {
  const r = stationRows([st(0, '가', 10), st(1, '나', 30), st(2, '다', 30), st(3, '라', 20)], 3);
  assert.deepEqual(r.rows.map((x) => x.name), ['나', '다', '라']);
  assert.equal(r.known, 4);
  assert.equal(r.unknown, 0);
});

test('이름 없는 역의 0 은 「0명」 이 아니라 「모름」 이다 — 지도와 같은 규칙', () => {
  const r = stationRows([st(0, '가', 10), st(1, null, 0), st(2, '다', null)], 10);
  assert.deepEqual(r.rows.map((x) => x.name), ['가']);
  assert.equal(r.known, 1);
  assert.equal(r.unknown, 2);
});

test('이름 있는 역의 0 은 0명이다', () => {
  assert.equal(stationRows([st(0, '가', 0)], 10).known, 1);
});

test('역이 없으면 빈 목록', () => {
  assert.deepEqual(stationRows(undefined, 10), { rows: [], known: 0, unknown: 0 });
});
