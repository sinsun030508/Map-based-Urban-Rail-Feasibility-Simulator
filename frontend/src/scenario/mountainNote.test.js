// `npm test` (node --test). 산 구간 안내 문구 — 「산 없음」 과 「지형 자료 없음」, 「0원」 과 「모름」 을 섞지 않는다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { followsRoad, mergeRoadMountain, mountainFor, mountainNote, newPieces, roadMountainNote, roadMountainView } from './mountainNote.js';

const RES = {
  available: true,
  mountains: [
    { index: 1, tunnelKm: 2.0, detoured: true, addedKm: 5.1, reason: null, pieces: [] },
    { index: 2, tunnelKm: 1.2, detoured: false, addedKm: 0, reason: '35‰ 를 넘지 않고 1.5배 안에서 돌아갈 길이 없습니다', pieces: [] },
  ],
  deltas: [
    { mode: 'HEAVY_METRO', structure: 'ELEVATED', tunnelExtraCost: 1040, detourExtraCost: 3195, detourExtraMin: 9.4 },
    { mode: 'TRAM', structure: 'AT_GRADE', tunnelExtraCost: null, detourExtraCost: null, detourExtraMin: 13.8 },
  ],
};

test('계산 전에는 수단과 무관한 길이만 — 비용 칸이 없다', () => {
  const n = mountainNote(RES, null);
  assert.equal(n.cost, null);
  assert.match(n.lengths, /터널 3\.2km/);
  assert.match(n.lengths, /돌아가면 \+5\.1km/);
  assert.equal(n.canApply, true);
});

test('뚫는 비용은 비교표 건설비에 이미 들어 있다고 적고, 돌아가는 안은 표와의 차액으로 적는다(11-09)', () => {
  const n = mountainNote(RES, { modeType: 'HEAVY_METRO', structureType: 'ELEVATED' });
  assert.match(n.cost, /뚫는 비용 1,040억 — 비교표 건설비에 들어 있습니다/);
  assert.match(n.cost, /돌아가면 건설비 \+2,155억 · \+9\.4분/);   // 3,195 − 1,040
  assert.doesNotMatch(n.cost, /뚫기 \+/);                            // 두 번 더해 읽히지 않게
});

test('돌아가는 편이 싸면 차액이 음수다', () => {
  const res = { ...RES, deltas: [{ mode: 'HEAVY_METRO', structure: 'ELEVATED', tunnelExtraCost: 1040, detourExtraCost: 700, detourExtraMin: 3.0 }] };
  const n = mountainNote(res, { modeType: 'HEAVY_METRO', structureType: 'ELEVATED' });
  assert.match(n.cost, /돌아가면 건설비 −340억 · \+3\.0분/);
});

test('터널비를 모르면(응답이 null) 0원이 아니라 모른다고 적는다', () => {
  const n = mountainNote(RES, { modeType: 'TRAM', structureType: 'AT_GRADE' });
  assert.match(n.cost, /터널비를 모릅니다 — 비교표 건설비에 없습니다/);
  assert.doesNotMatch(n.cost, /\+0억/);
});

test('BRT 는 도로 기울기(7%)로 따로 잰 터널을 적는다 — 비교표 건설비에 들어 있다', () => {
  const res = { ...RES, roadTunnelKm: 1.2,
    deltas: [...RES.deltas, { mode: 'BRT_HIGH', structure: 'AT_GRADE', tunnelExtraCost: 176, detourExtraCost: null, detourExtraMin: null }] };
  const n = mountainNote(res, { modeType: 'BRT_HIGH', structureType: 'AT_GRADE' });
  assert.match(n.cost, /BRT 는 도로 기울기\(7%\)로 봅니다 — 터널 1\.2km · 뚫는 비용 176억 — 비교표 건설비에 들어 있습니다/);
});

test('도로 기울기로는 터널이 없으면 그렇게 적는다', () => {
  const res = { ...RES, roadTunnelKm: 0,
    deltas: [...RES.deltas, { mode: 'BRT_HIGH', structure: 'AT_GRADE', tunnelExtraCost: 0, detourExtraCost: null, detourExtraMin: null }] };
  const n = mountainNote(res, { modeType: 'BRT_HIGH', structureType: 'AT_GRADE' });
  assert.match(n.cost, /BRT 는 도로 기울기\(7%\)로 보면 터널이 필요 없습니다/);
});

test('지형 자료가 없으면 「산 없음」 이 아니라 「안 봤다」 고 적는다', () => {
  const n = mountainNote({ available: false, mountains: [], deltas: [] }, null);
  assert.match(n.lengths, /지형 자료가 없어 산 구간을 보지 않았습니다/);
  assert.equal(n.canApply, false);
});

test('산이 없으면 안내하지 않는다', () => {
  assert.equal(mountainNote({ available: true, mountains: [], deltas: [] }, null), null);
  assert.equal(mountainNote(null, null), null);
});

test('돌아갈 구간이 하나도 없으면 「1.5배」 로 못박지 않는다 — 경유점 50개로 빠진 구간도 있다', () => {
  const n = mountainNote({ available: true, deltas: [], mountains: [
    { index: 1, tunnelKm: 1, detoured: false, addedKm: 0, reason: '돌아가면 경유점이 50개를 넘어 저장할 수 없습니다', pieces: [] },
  ] }, null);
  assert.match(n.lengths, /돌아갈 수 있는 구간이 없습니다/);
  assert.doesNotMatch(n.lengths, /1\.5배/);
  assert.equal(n.canApply, false);
});

test('돌면 손해인 구간은 뚫는다고 적는다 — 「돌아갈 길이 없다」 와 다른 말이다', () => {
  const costlier = { index: 2, tunnelKm: 0.4, detoured: false, costlier: true, addedKm: 0, reason: '손해', pieces: [] };
  const some = mountainNote({ available: true, deltas: [], mountains: [RES.mountains[0], costlier] }, null);
  assert.match(some.lengths, /1곳은 돌아가면 \+5\.1km/);
  assert.match(some.lengths, /1곳은 돌아가는 편이 비싸 뚫습니다/);
  assert.equal(some.canApply, true);

  const all = mountainNote({ available: true, deltas: [], mountains: [costlier] }, null);
  assert.match(all.lengths, /돌아가는 편이 비싸 뚫습니다/);
  assert.doesNotMatch(all.lengths, /돌아갈 수 있는 구간이 없습니다/);
  assert.equal(all.canApply, false);
});

test('철도 고가처럼 터널 추가분이 0 이면 「0억이 들어 있다」 가 아니라 이유를 적는다', () => {
  const res = { ...RES, deltas: [{ mode: 'EXPRESS_RAIL', structure: 'ELEVATED', tunnelExtraCost: 0, detourExtraCost: null, detourExtraMin: null }] };
  const n = mountainNote(res, { modeType: 'EXPRESS_RAIL', structureType: 'ELEVATED' });
  assert.match(n.cost, /산악 터널이 고가와 km당 공사비가 비슷해 더할 비용이 없습니다/);
  assert.doesNotMatch(n.cost, /0억/);
});

test('트램 행을 고르면 트램만의 안(modePlans)을 쓴다 — 철도가 뚫는 산도 트램은 돌 수 있다(11-12)', () => {
  const tramPlan = {
    mode: 'TRAM',
    points: [{ latitude: 36.14, longitude: 128.03 }, { latitude: 36.15, longitude: 128.04 }, { latitude: 36.14, longitude: 128.05 }],
    mountains: [{ index: 1, tunnelKm: 0.6, detoured: true, costlier: false, addedKm: 0.15, reason: null, pieces: [] }],
    deltas: [{ mode: 'TRAM', structure: 'AT_GRADE', tunnelExtraCost: 177, detourExtraCost: 41, detourExtraMin: 0.4 }],
  };
  const res = {
    available: true,
    points: [{ latitude: 36.14, longitude: 128.03 }, { latitude: 36.14, longitude: 128.05 }],
    mountains: [{ index: 1, tunnelKm: 0.6, detoured: false, costlier: true, addedKm: 0, reason: '비쌉니다', pieces: [] }],
    deltas: [{ mode: 'TRAM', structure: 'AT_GRADE', tunnelExtraCost: 177, detourExtraCost: null, detourExtraMin: null }],
    modePlans: [tramPlan],
  };
  const tram = { modeType: 'TRAM', structureType: 'AT_GRADE' };
  const view = mountainFor(res, tram);
  assert.equal(view.points, tramPlan.points);
  assert.equal(view.mountains, tramPlan.mountains);
  assert.equal(view.available, true);
  const n = mountainNote(view, tram);
  assert.equal(n.canApply, true);
  assert.match(n.cost, /돌아가면 건설비 −136억/);   // 41 − 177

  // 다른 수단·계산 전은 공통 안 그대로
  assert.equal(mountainFor(res, { modeType: 'HEAVY_METRO', structureType: 'ELEVATED' }), res);
  assert.equal(mountainFor(res, null), res);
  assert.equal(mountainFor(null, tram), null);
  // 따로 안이 없는 예전 응답도 그대로
  const old = { ...res, modePlans: undefined };
  assert.equal(mountainFor(old, tram), old);
});

// 트램·BRT·광역급행은 기존 도로를 따라간다(11-12) — 산 안내는 새로 놓는 구간만 본다. 기존 도로는 이미 뚫려 있다
const ROAD_ONLY = { modeType: 'TRAM', structureType: 'AT_GRADE', routeLine: [{ road: true, points: [[37.5, 126.9], [37.6, 126.9]] }], costParts: { tunnel: 0 } };
const WITH_NEW = {
  modeType: 'TRAM',
  structureType: 'AT_GRADE',
  routeLine: [{ road: true, points: [[37.5, 126.9], [37.6, 126.9]] }, { road: false, points: [[37.6, 126.9], [37.62, 126.95]] }],
  costParts: { tunnel: 530 },
};
const MTN = { available: true, mountains: [{ index: 1, tunnelKm: 0.8, detoured: true, costlier: false, addedKm: 0.3, pieces: [] }], roadTunnelKm: 0.4 };

test('도로를 따라가는 행인가 — 노선 모양이 있으면, 새로 놓는 구간만 골라낸다', () => {
  assert.equal(followsRoad(ROAD_ONLY), true);
  assert.equal(followsRoad({ modeType: 'HEAVY_METRO', structureType: 'ELEVATED' }), false);
  assert.equal(followsRoad(null), false);
  assert.deepEqual(newPieces(ROAD_ONLY), []);
  assert.equal(newPieces(WITH_NEW).length, 1);
});

test('기존 도로만 따라가면 산 안내를 숨긴다 — 이미 뚫려 있고, 직선 기준 갈색 토막이 빨간 도로 선과 어긋나 보이지 않게', () => {
  assert.equal(roadMountainView(MTN, WITH_NEW) !== null, true);
  // 새 구간이 없으면 서버 응답이 있어도 안 보인다 — App 이 새 구간이 없을 때 묻지 않아 roadMountain 이 비는 것과 같은 결과
  assert.equal(roadMountainView(null, ROAD_ONLY), null);
  assert.equal(roadMountainView({ available: true, mountains: [] }, WITH_NEW), null);
  assert.equal(roadMountainView({ available: false, mountains: [] }, WITH_NEW), null);
});

test('새로 놓는 구간의 산 — 돌아가기 버튼 없이(돌아가는 안은 직선 기준이라 도로 노선과 안 맞는다), 비용은 표의 터널비 그대로', () => {
  const view = roadMountainView(MTN, WITH_NEW);
  assert.equal(view.mountains[0].detoured, false);
  const n = roadMountainNote(view, WITH_NEW);
  assert.match(n.lengths, /새로 놓는 구간/);
  assert.match(n.lengths, /터널 0\.8km/);
  assert.match(n.cost, /뚫는 비용 530억 — 비교표 건설비에 들어 있습니다/);
  assert.equal(n.canApply, false);
  // 터널비가 0 이면 「0억이 들어 있다」 고 적지 않는다
  const zero = roadMountainNote(view, { ...WITH_NEW, costParts: { tunnel: 0 } });
  assert.match(zero.cost, /더할 비용이 없습니다/);
});

test('BRT 는 도로 기울기로 잰 터널 km 를 적고, 0 이면 숨긴다', () => {
  const brt = { ...WITH_NEW, modeType: 'BRT_LOW' };
  assert.match(roadMountainNote(roadMountainView(MTN, brt), brt).lengths, /터널 0\.4km/);
  assert.equal(roadMountainView({ ...MTN, roadTunnelKm: 0 }, brt), null);
});

test('새 구간이 여럿이면 묶어 센다 — 구간 번호는 다시 매기고, 하나라도 모르면 도로 터널 km 도 모른다', () => {
  const merged = mergeRoadMountain([MTN, { ...MTN, mountains: [{ ...MTN.mountains[0], tunnelKm: 0.5 }], roadTunnelKm: 0.1 }]);
  assert.deepEqual(merged.mountains.map((m) => m.index), [1, 2]);
  assert.ok(Math.abs(merged.roadTunnelKm - 0.5) < 1e-9);
  assert.equal(mergeRoadMountain([MTN, { ...MTN, roadTunnelKm: null }]).roadTunnelKm, null);
  assert.equal(mergeRoadMountain([]), null);
});
