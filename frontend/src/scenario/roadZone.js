/**
 * 도로를 따라가는 행(트램·BRT·광역급행)의 확인 필요 구역(궁궐·상수원·군사) — **새로 놓는 구간만** 본다(11-14 사용자 요청). 기존 도로는 이미 있다.
 * 그린 직선 기준 카드는 트램이 율곡로·사직로 같은 기존 도로로 두 궁 구역을 지나는데도 「피해 가면 +0.9km」 라고 말했다(산·물과 같은 어긋남).
 */

/**
 * 새 구간마다 물은 `/api/route/zones` 응답을 하나로 — 같은 구역은 한 번만, 처음 닿는 순서를 지킨다. **피해 가는 제안(`crossings`)은 버린다** —
 * 직선 구간의 선형을 바꾸는 제안이라 도로 노선과 안 맞고 「바꾸기」 도 없다(산·물과 같다). 받은 것이 없으면 null
 */
export function mergeRoadZones(responses) {
  if (!responses?.length) return null;
  const seen = new Map();
  responses.forEach((r) => (r.zones || []).forEach((z) => {
    if (!seen.has(z.name)) seen.set(z.name, z);
  }));
  return { zones: [...seen.values()], crossings: [] };
}
