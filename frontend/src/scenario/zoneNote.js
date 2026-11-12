/**
 * 확인 필요 구역 안내(`/api/route/zones` → 패널 한 칸, 11-12 사용자 요청). 좌표·반경이 눈대중 근사치라 「통과 불가」 로 단정하지 않고
 * 심의·협의 필요 / 사실상 불가(군사)로만 가른다. 이름 뒤에 조사를 붙이지 않는다 — 「경복궁 일대 — …」.
 *
 * @returns {{zones: string[], detours: string[], canApply: boolean} | null} 지나는 구역이 없으면 null
 */
export function zoneNote(res) {
  if (!res?.zones?.length) return null;
  const zones = res.zones.map((z) => `${z.name} — ${z.severity === 'BLOCKED' ? '사실상 불가' : '심의·협의 필요'}`
    + (z.note ? `(${z.note})` : ''));
  const crossings = res.crossings || [];
  const detours = crossings.map((c) => `${c.name} — ${c.detoured ? `피해 가면 +${c.addedKm.toFixed(1)}km` : c.reason}`);
  return { zones, detours, canApply: crossings.some((c) => c.detoured) };
}
