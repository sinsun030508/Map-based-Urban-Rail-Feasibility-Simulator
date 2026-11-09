/**
 * 비용 구성·역별 이용객 차트의 값. 컴포넌트 파일에 두면 Fast Refresh 가 전체 리로드로 떨어진다(`resultKey.js` 와 같다).
 */

const PARTS = [
  ['fixed', '기본 고정비'],
  ['length', '연장 비례'],
  ['stations', '역 수 비례'],
  ['tunnel', '산 구간 터널'],   // 산을 지날 때만 — 0 이면 막대를 붙이지 않는다(11-09)
];

/** @returns 몫과 비율(셋, 산 구간 터널이 있으면 넷). 구성을 모르면 null — 0% 로 그리면 「공짜」 로 읽힌다 */
export function costShares(parts) {
  if (!parts) return null;
  const total = parts.fixed + parts.length + parts.stations + (parts.tunnel || 0);
  if (!(total > 0)) return null;
  return PARTS.filter(([key]) => key !== 'tunnel' || parts.tunnel > 0).map(([key, label]) => {
    const share = (parts[key] / total) * 100;
    const pct = share > 0 && share < 1 ? '<1%' : `${Math.round(share)}%`;
    return { key, label, value: parts[key], pct };
  });
}

/**
 * 이용객이 많은 순 상위 역. **이름 없는 역의 0 은 「모름」 이다** — 집계구 자료가 없는 곳이고 백엔드는 모를 때도
 * 0 을 싣는다. 지도의 역 표시(`MapView`)와 같은 규칙이어야 한다.
 */
export function stationRows(stations, limit) {
  const all = stations || [];
  const known = all.filter((s) => s.name && s.estimatedDailyUsers != null);
  const rows = [...known]
    .sort((a, b) => b.estimatedDailyUsers - a.estimatedDailyUsers || a.sequence - b.sequence)
    .slice(0, limit);
  return { rows, known: known.length, unknown: all.length - known.length };
}

/** 데이터 끝만 둥글게 — 0 쪽은 각지게 둔다 (`BcChart`·`CostChart` 와 같다) */
export function barPath(x, y, w, h, r) {
  const radius = Math.min(r, w);
  return `M${x},${y} H${x + w - radius} a${radius},${radius} 0 0 1 ${radius},${radius}`
    + ` V${y + h - radius} a${radius},${radius} 0 0 1 ${-radius},${radius} H${x} Z`;
}
