import { money } from '../format.js';

/**
 * 산 구간 안내 문구(`/api/route/mountain` 응답 → 패널 한 칸). 화면 규칙은 `frontend/CLAUDE.md`.
 *
 * - **계산 전**에는 수단과 무관한 길이만(터널 km · 돌아가면 +km). 건설비·소요시간은 수단마다 다르다
 * - **계산 후**에는 비교표에서 고른 행(없으면 지도에 그린 안)의 단가·속도로 두 안을 적는다. 뚫는 비용은 비교표
 *   건설비에 이미 들어 있고(11-09), 돌아가는 안은 그 값과의 차액이다
 * - 「산 없음」 과 「지형 자료 없음」, 「0원」 과 「모름」 을 섞지 않는다
 *
 * @returns {{lengths: string, cost: string|null, canApply: boolean} | null} 보일 것이 없으면 null
 */
export function mountainNote(res, row) {
  if (!res) return null;
  if (!res.available) {
    return { lengths: '지형 자료가 없어 산 구간을 보지 않았습니다', cost: null, canApply: false };
  }
  const ms = res.mountains || [];
  if (!ms.length) return null;

  const tunnel = ms.reduce((s, m) => s + m.tunnelKm, 0);
  const detoured = ms.filter((m) => m.detoured);
  const added = detoured.reduce((s, m) => s + m.addedKm, 0);
  const costlier = ms.filter((m) => m.costlier).length;
  const some = (n) => (n === ms.length ? '' : `${n}곳은 `);
  const lengths = `산 구간 ${ms.length}곳 — 35‰ 보다 가파릅니다. 뚫으면 터널 ${tunnel.toFixed(1)}km`
    + (detoured.length ? ` · ${some(detoured.length)}돌아가면 +${added.toFixed(1)}km` : '')
    + (costlier ? ` · ${some(costlier)}돌아가는 편이 비싸 뚫습니다` : '')
    + (detoured.length || costlier ? '' : ' · 돌아갈 수 있는 구간이 없습니다');

  return { lengths, cost: row ? costLine(res, row, detoured.length > 0) : null, canApply: detoured.length > 0 };
}

function costLine(res, row, anyDetour) {
  if (row.modeType === 'BRT_HIGH' || row.modeType === 'BRT_LOW') {
    // 도로는 철도(35‰)보다 가파르게 오른다 — 도로 기울기(규칙 제25조, 7%)로 따로 잰 터널을 쓴다(11-09)
    const b = (res.deltas || []).find((x) => x.mode === row.modeType && x.structure === row.structureType);
    if (res.roadTunnelKm === 0) return 'BRT 는 도로 기울기(7%)로 보면 터널이 필요 없습니다';
    if (!b || b.tunnelExtraCost == null || res.roadTunnelKm == null) return 'BRT 의 터널비를 모릅니다 — 비교표 건설비에 없습니다';
    return `BRT 는 도로 기울기(7%)로 봅니다 — 터널 ${res.roadTunnelKm.toFixed(1)}km · 뚫는 비용 ${money(b.tunnelExtraCost)}`
      + ' — 비교표 건설비에 들어 있습니다';
  }
  if (row.structureType === 'UNDERGROUND') {
    return '지하는 이미 땅속이라 산 안내가 없습니다';
  }
  const d = (res.deltas || []).find((x) => x.mode === row.modeType && x.structure === row.structureType);
  if (!d) return null;
  // 비교표 건설비는 그린 노선 그대로 — 산을 뚫는 비용이 이미 들어 있다(11-09). 「뚫기 +」 로 적으면 두 번 더해 읽힌다
  const known = d.tunnelExtraCost != null;
  // KDI 로는 철도 산악 터널이 교량(고가)과 km당 비슷하거나 더 싸 추가분이 0 일 수 있다 — 「0억이 들어 있다」 보다 이유를 적는다
  const tunnel = !known
    ? '터널비를 모릅니다 — 비교표 건설비에 없습니다'
    : d.tunnelExtraCost === 0
      ? '산악 터널이 고가와 km당 공사비가 비슷해 더할 비용이 없습니다'
      : `뚫는 비용 ${money(d.tunnelExtraCost)} — 비교표 건설비에 들어 있습니다`;
  if (!anyDetour) return tunnel;
  const min = d.detourExtraMin == null ? '' : ` · +${d.detourExtraMin.toFixed(1)}분`;
  if (d.detourExtraCost == null) return `${tunnel} / 돌아가기 비용은 남은 터널값을 몰라 모릅니다${min}`;
  // 표에 든 값과의 차액 — 표에 터널비가 없으면(모름) 돌아가는 비용 전부가 더해진다
  const diff = d.detourExtraCost - (known ? d.tunnelExtraCost : 0);
  const sign = diff < 0 ? '−' : '+';
  return `${tunnel} / 돌아가면 건설비 ${sign}${money(Math.abs(diff))}${min}`;
}
