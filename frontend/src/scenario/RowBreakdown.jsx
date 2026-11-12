import { barPath, costShares, stationRows } from './breakdown';
import { money } from '../format';
import { MODE_LABEL_SHORT as MODE_LABEL, STRUCTURE_LABEL } from '../modeLabels';

const BAR_H = 14;
const ROW_H = 22;
const LABEL_W = 84;
const VALUE_W = 46;
const WIDTH = 300;
const TOP_STATIONS = 10;

/** 한 색 가로 막대 — `BcChart`·`CostChart` 와 같은 집이다. 값 글자는 `valueOf` 가 준 행에만 적는다 */
function Bars({ rows, ariaLabel, valueOf }) {
  const max = Math.max(...rows.map((r) => r.value));
  const plotW = WIDTH - LABEL_W - VALUE_W;
  return (
    <svg viewBox={`0 0 ${WIDTH} ${rows.length * ROW_H}`} role="img" aria-label={ariaLabel}>
      {rows.map((r, i) => {
        const w = max > 0 ? Math.max(1, (r.value / max) * plotW) : 1;
        const text = valueOf(r, max);
        return (
          <g key={r.key} className="bc-row stat-day">
            <title>{r.title}</title>
            <rect x={0} y={i * ROW_H} width={WIDTH} height={ROW_H} className="bc-hit" />
            <text x={LABEL_W - 6} y={i * ROW_H + ROW_H / 2 + 4} className="bc-label">{r.label}</text>
            <path d={barPath(LABEL_W, i * ROW_H + (ROW_H - BAR_H) / 2, w, BAR_H, 4)} className="bc-bar" />
            {text && <text x={LABEL_W + w + 4} y={i * ROW_H + ROW_H / 2 + 4} className="bc-clip">{text}</text>}
          </g>
        );
      })}
    </svg>
  );
}

/**
 * 지도에 그린 안의 비용 구성·역별 예상 이용객 (제안서 「결과 대시보드」). 수단마다 다르므로 비교표에서 고른 행을 따른다.
 *
 * 비용 구성은 항목이 셋뿐이라 쌓은 막대 대신 나란한 막대에 비율을 바로 적는다 — 색으로 구별할 일이 없다.
 * **회귀식의 항이지 공종별 내역이 아니다** — 「기본 고정비」 를 토목비로 읽으면 안 되므로 같은 자리에 적는다.
 * 역별 이용객은 역이 수백 개일 수 있어 상위만 그린다. 값은 노선 전체를 역세권 인구 비율로 나눈 근사다.
 */
export default function RowBreakdown({ row }) {
  if (!row) return null;
  const name = `${MODE_LABEL[row.modeType] || row.modeType} ${STRUCTURE_LABEL[row.structureType] || ''}`.trim();
  const shares = costShares(row.costParts);
  const { rows, known, unknown } = stationRows(row.stations, TOP_STATIONS);

  return (
    <div className="row-breakdown">
      {shares && (
        <figure className="bc-chart">
          <figcaption>{name} — 건설비 구성</figcaption>
          <Bars
            rows={shares.map((s) => ({ ...s, title: `${s.label} · ${money(s.value)} · ${s.pct}` }))}
            ariaLabel={`${name} 건설비 구성. ${shares.map((s) => `${s.label} ${s.pct}`).join(', ')}`}
            valueOf={(r) => r.pct}
          />
          <p className="muted small">
            비용 회귀식의 세 항으로 나눈 몫입니다 — 공사 내역(토목·궤도 등)이 아닙니다.
            {shares.some((s) => s.key === 'tunnel') && (
              <> 산 구간 터널은 그린 노선이 산을 지나 뚫는 몫으로, KDI 예타 세부지침(2021)의 산악 터널 표준공사비에서 매겼습니다.</>
            )}
            {shares.some((s) => s.key === 'road') && (
              <> 새 도로 다리는 기존 도로로 못 가 새로 놓는 구간이 강·호수를 건너는 몫으로, KDI 세부지침의 일반국도 교량 표준공사비에서 매겼습니다(땅 위 새 도로 공사비는 빠져 있습니다).</>
            )}
          </p>
        </figure>
      )}
      {rows.length > 0 ? (
        <figure className="bc-chart">
          <figcaption>
            {name} — 역별 예상 이용객{known > rows.length ? ` (상위 ${rows.length}개 / ${known}개)` : ''}
          </figcaption>
          <Bars
            rows={rows.map((s) => ({
              key: s.sequence,
              label: s.name,
              value: s.estimatedDailyUsers,
              title: `${s.name} · ${s.estimatedDailyUsers.toLocaleString()}명/일`,
            }))}
            ariaLabel={`${name} 역별 예상 이용객. 가장 많은 역은 ${rows[0].name} 하루 ${rows[0].estimatedDailyUsers.toLocaleString()}명입니다`}
            valueOf={(r, max) => (r.value === max ? r.value.toLocaleString() : null)}
          />
          <p className="muted small">
            노선 전체 이용객을 역세권 인구 비율로 나눈 근사입니다 — 역마다 따로 추정한 값이 아닙니다.
            {unknown > 0 && ` 인구 자료가 없는 역 ${unknown}개는 몰라서 뺐습니다.`}
          </p>
        </figure>
      ) : (
        <p className="muted small">{name} — 역별 예상 이용객을 모릅니다</p>
      )}
    </div>
  );
}
