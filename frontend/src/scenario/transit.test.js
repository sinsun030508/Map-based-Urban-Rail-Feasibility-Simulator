import test from 'node:test';
import assert from 'node:assert/strict';
import {
  routeKey, isCurrent, minutes, legName, legColor, legWeight, itinerarySummary, ownSummary, searchedLabel,
  statusMessage,
} from './transit.js';

const walk = { mode: 'WALK', sec: 720, route: null, color: null, running: true };
const it = { totalSec: 3120, walkSec: 720, transfers: 2, fare: 1650, running: true, legs: [walk] };

test('점이 바뀌면 키가 달라지고, 옛 키의 답은 지금 것이 아니다', () => {
  const a = routeKey([[126.9, 37.5], [127.0, 37.6]]);
  const b = routeKey([[126.9, 37.5], [127.1, 37.6]]);
  assert.notEqual(a, b);
  assert.equal(isCurrent({ key: a }, a), true);
  assert.equal(isCurrent({ key: a }, b), false);
  assert.equal(isCurrent(null, a), false);
});

test('시간은 반올림한 분이다', () => {
  assert.equal(minutes(3120), 52);
  assert.equal(minutes(89), 1);
  assert.equal(minutes(90), 2);
});

test('구간 이름 — 도보, 버스 번호에서 등급 머리말을 떼고, 지하철에서 「수도권」을 뗀다', () => {
  assert.equal(legName(walk), '도보');
  assert.equal(legName({ mode: 'BUS', route: '간선:511' }), '511');
  assert.equal(legName({ mode: 'SUBWAY', route: '수도권2호선' }), '2호선');
  assert.equal(legName({ mode: 'SUBWAY', route: '수인분당선' }), '수인분당선');
  assert.equal(legName({ mode: 'BUS', route: null }), '버스');
  assert.equal(legName({ mode: 'FERRY', route: null }), '해운');
  assert.equal(legName({ mode: 'SOMETHING' }), '이동');
});

test('색은 응답의 여섯 자리만 쓰고, 도보·없음·엉뚱한 값은 회색이다', () => {
  assert.equal(legColor({ mode: 'SUBWAY', color: '009D3E' }), '#009D3E');
  assert.equal(legColor({ mode: 'WALK', color: '009D3E' }), '#9ca3af');
  assert.equal(legColor({ mode: 'BUS', color: null }), '#9ca3af');
  assert.equal(legColor({ mode: 'BUS', color: 'red;x' }), '#9ca3af');
});

test('구간 폭은 시간에 비례하되 3분보다 좁아지지 않는다', () => {
  assert.equal(legWeight({ sec: 600 }), 600);
  assert.equal(legWeight({ sec: 30 }), 180);
});

test('경로 요약 — 걷기를 뺀 시간을 보조로 적고 「몇 분 빠름」 은 적지 않는다', () => {
  const box = itinerarySummary(it);
  assert.deepEqual(box, { total: '52분', facts: '걷기 12분 · 환승 2 · 1,650원', ride: '걷기 빼면 40분' });
  assert.equal(itinerarySummary({ ...it, fare: null }).facts, '걷기 12분 · 환승 2');
  assert.doesNotMatch(Object.values(box).join(' '), /빠/);
});

test('신설안 요약 — 역~역이라고 적고, 행이나 소요시간이 없으면 없다', () => {
  const own = ownSummary({ modeType: 'EXPRESS_RAIL', structureType: 'ELEVATED', travelTimeMin: 17.6 });
  assert.deepEqual(own, { name: '신설 광역급행 고가', total: '18분', note: '역~역 · 걷기·대기 제외' });
  assert.equal(ownSummary(undefined), null);
  assert.equal(ownSummary({ modeType: 'TRAM', structureType: 'AT_GRADE', travelTimeMin: null }), null);
});

test('시각 문구는 운행 여부에만 걸린다', () => {
  assert.equal(searchedLabel('2026-11-30T08:00'), '운행 여부는 평일 08:00(11월 30일) 기준');
  assert.equal(searchedLabel(null), '');
});

test('실패 이유마다 다른 말을 하고 OK 는 말이 없다', () => {
  assert.equal(statusMessage('OK'), '');
  const messages = ['DISABLED', 'LIMIT', 'NO_ROUTE', 'FAILED'].map(statusMessage);
  assert.equal(new Set(messages).size, 4);
  messages.forEach((m) => assert.ok(m.length > 0));
});
