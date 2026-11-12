/**
 * 도로를 따라가는 행(트램·BRT·광역급행)의 물 안내 — **새로 놓는 구간만** 본다(11-12 사용자 요청). 기존 도로·다리는 이미 있다. 그린 직선이 한강을 지나는
 * 길이를 적으면 트램이 실제로는 기존 다리를 건너는데도 「물 위 구간 1.0km」 가 뜨고 파란 토막이 도로 노선과 떨어져 그려졌다(산 안내와 같은 어긋남).
 */

/**
 * 새 구간마다 물은 `/api/route/detour` 응답을 하나로 — 물 구간은 이름·종류별로 길이를 더하고 토막은 이어 붙인다.
 * **호수 우회 제안(`crossings`)은 버린다** — 직선 구간의 선형을 바꾸는 제안이라 도로 노선과 안 맞고, 돌아가기 버튼도 없다(산과 같다).
 * 받은 것이 없으면 null
 */
export function mergeRoadWater(responses) {
  if (!responses?.length) return null;
  const byName = new Map();
  responses.forEach((r) => (r.waterKm || []).forEach((w) => {
    const key = `${w.kind}|${w.name}`;
    const prev = byName.get(key);
    if (prev) {
      prev.km += w.km;
      prev.pieces = [...prev.pieces, ...(w.pieces || [])];
    } else {
      byName.set(key, { ...w, pieces: [...(w.pieces || [])] });
    }
  }));
  return { waterKm: [...byName.values()], crossings: [] };
}

/**
 * 새 도로가 물을 건너는 몫이 **건설비에 들어 있나** — 트램·BRT 는 일반국도 교량 단가로 더한다(`road_cost`). 광역급행은 철도라 교량 할증이 없다
 * (그래서 「할증은 넣지 않아 낮게 잡혔을 수 있습니다」 가 그대로다)
 */
export function bridgeCostIncluded(row) {
  return ['TRAM', 'BRT_HIGH', 'BRT_LOW'].includes(row?.modeType);
}
