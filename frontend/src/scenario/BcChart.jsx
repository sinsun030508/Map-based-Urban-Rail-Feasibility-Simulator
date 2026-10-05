import { resultKey } from './resultKey';

/**
 * 수단별 B/C 막대. 표 바로 위에 두고 **모양만** 보여 준다 — 숫자는 표가 갖고 있다.
 *
 * 이 프로젝트의 핵심 메시지가 "이 구간은 BRT면 충분한데 지하철을 지으면 B/C가 떨어진다"인데,
 * 숫자만 늘어놓으면 그게 안 보인다. 1.0 기준선 하나를 그으면 어떤 안이 선을 넘는지 바로 읽힌다.
 *
 * 한 계열이라 범례를 두지 않고(제목이 곧 계열 이름) 막대는 한 색이다. 통과·미달을 색으로
 * 가르지 않는다 — 기준선이 이미 그 일을 하고, 색으로 상태를 말하면 표의 경고와 역할이 겹친다.
 *
 * **BRT 가 6~15 로 튀어 축을 독식한다.** 그대로 두면 0.5~2.2 구간이 전부 실오라기가 되어
 * 정작 비교할 것이 안 보인다. 축을 {@link MAX_DOMAIN} 에서 자르고 잘린 막대에는 값을 적는다.
 */
const MAX_DOMAIN = 3;        // 이보다 큰 값은 자른다 (BRT 가 축을 독식한다)
const MIN_DOMAIN = 1.5;      // 전부 낮아도 1.0 기준선이 보이게 한다
const BAR_H = 14;            // 규격 상한 24px 안
const ROW_H = 22;            // 막대 사이 8px 여백 (표면 간격 2px 이상)
const LABEL_W = 84;
const VALUE_W = 30;
const WIDTH = 300;

const MODE_LABEL = {
  HEAVY_METRO: '지하철', LIGHT_RAIL: '경전철', TRAM: '트램',
  BRT_HIGH: 'BRT 고급', BRT_LOW: 'BRT 저급', DOUBLE_ELEC: '복선전철',
};
const STRUCTURE_LABEL = { UNDERGROUND: '지하', ELEVATED: '고가', AT_GRADE: '지상' };

/** 데이터 끝만 둥글게 — 기준선 쪽은 각지게 둔다 */
function barPath(x, y, w, h, r) {
  const radius = Math.min(r, w);
  return `M${x},${y} H${x + w - radius} a${radius},${radius} 0 0 1 ${radius},${radius}`
    + ` V${y + h - radius} a${radius},${radius} 0 0 1 ${-radius},${radius} H${x} Z`;
}

export default function BcChart({ results, selected, onSelect }) {
  const rows = results.filter((r) => r.bcRatio != null);
  if (rows.length < 2) {
    return null;
  }
  const max = Math.min(MAX_DOMAIN,
    Math.max(MIN_DOMAIN, ...rows.map((r) => Number(r.bcRatio))));
  const plotW = WIDTH - LABEL_W - VALUE_W;
  const height = rows.length * ROW_H + 18;
  const x = (v) => LABEL_W + Math.min(v, max) / max * plotW;

  return (
    <figure className="bc-chart">
      <figcaption>수단별 B/C — 1.0 을 넘어야 편익이 비용을 덮는다</figcaption>
      <svg viewBox={`0 0 ${WIDTH} ${height}`} role="img"
           aria-label="수단별 B/C 막대. 자세한 값은 아래 표에 있습니다">
        {/* 기준선은 실선 hairline — 점선은 격자처럼 읽혀 노이즈가 된다 */}
        <line x1={x(1)} y1={0} x2={x(1)} y2={rows.length * ROW_H}
              className="bc-rule" />
        <text x={x(1)} y={height - 5} className="bc-rule-label">1.0</text>

        {rows.map((r, i) => {
          const value = Number(r.bcRatio);
          const y = i * ROW_H + (ROW_H - BAR_H) / 2;
          const w = Math.max(1, x(value) - LABEL_W);
          const clipped = value > max;
          return (
            <g key={resultKey(r)}
               className={resultKey(r) === selected ? 'bc-row picked' : 'bc-row'}
               onClick={() => onSelect?.(resultKey(r))}>
              <title>
                {`${MODE_LABEL[r.modeType] || r.modeType} `
                  + `${STRUCTURE_LABEL[r.structureType]} · B/C ${value.toFixed(2)}`}
              </title>
              <rect x={0} y={i * ROW_H} width={WIDTH} height={ROW_H} className="bc-hit" />
              <text x={LABEL_W - 6} y={i * ROW_H + ROW_H / 2 + 4} className="bc-label">
                {MODE_LABEL[r.modeType] || r.modeType} {STRUCTURE_LABEL[r.structureType]}
              </text>
              <path d={barPath(LABEL_W, y, w, BAR_H, 4)} className="bc-bar" />
              {/* 잘린 막대만 값을 적는다 — 나머지는 표가 말한다 */}
              {clipped && (
                <text x={LABEL_W + plotW + 4} y={i * ROW_H + ROW_H / 2 + 4}
                      className="bc-clip">{value.toFixed(1)}</text>
              )}
            </g>
          );
        })}
      </svg>
    </figure>
  );
}
