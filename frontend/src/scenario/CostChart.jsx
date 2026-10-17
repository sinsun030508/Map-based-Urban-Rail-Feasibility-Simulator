import { resultKey } from './resultKey';
import { money } from '../format';
import { MODE_LABEL_SHORT as MODE_LABEL, STRUCTURE_LABEL } from '../modeLabels';

/**
 * 건설비 막대 — **기존 수단은 0 이다.**
 *
 * 표에는 이미 `기존 KTX ... 0` 이 한 줄 들어가 있지만, 42조와 0 이 숫자로만 나란히
 * 있으면 그 차이가 읽히지 않는다. 막대로 보면 **"신설은 저만큼, 기존은 아예 없음"**
 * 이 한눈에 들어온다 — 이 기능의 요점이 그것이다.
 *
 * **B/C 차트에 섞지 않고 따로 둔다.** 축이 다른 두 측정을 한 그림에 넣으면(이중 축)
 * 어느 막대가 무엇인지 알 수 없다. 게다가 기존 수단은 **B/C 를 일부러 비워 뒀으므로**
 * B/C 차트에는 애초에 그릴 값이 없다.
 *
 * **기존 수단이 없으면 그리지 않는다.** 비교할 것이 없으면 비용 순위는 표가 이미
 * 말하고 있어 그림이 한 장 더 늘기만 한다.
 *
 * 쓰는 것은 `BcChart` 와 같은 집이다 — 한 색, 범례 없음(제목이 곧 계열 이름),
 * 막대 14px, 기준선은 hairline 실선. 값은 **가장 비싼 안과 기존 수단에만** 적는다
 * (모든 막대에 숫자를 달면 표를 한 번 더 그리는 셈이다).
 */
const BAR_H = 14;
const ROW_H = 22;
const LABEL_W = 84;
const VALUE_W = 54;      // `42조 4,451억` 이 들어가는 폭
const WIDTH = 300;

/** 데이터 끝만 둥글게 — 0 쪽은 각지게 둔다 (BcChart 와 같다) */
function barPath(x, y, w, h, r) {
  const radius = Math.min(r, w);
  return `M${x},${y} H${x + w - radius} a${radius},${radius} 0 0 1 ${radius},${radius}`
    + ` V${y + h - radius} a${radius},${radius} 0 0 1 ${-radius},${radius} H${x} Z`;
}

export default function CostChart({ results, existingOption, selected, onSelect }) {
  if (!existingOption || results.length < 2) {
    return null;
  }
  const max = Math.max(...results.map((r) => r.totalCost));
  if (!(max > 0)) {
    return null;
  }
  const plotW = WIDTH - LABEL_W - VALUE_W;
  const rows = results.length + 1;                 // 기존 수단 한 줄을 더한다
  const height = rows * ROW_H;
  const x = (v) => LABEL_W + (v / max) * plotW;
  const existingY = results.length * ROW_H;

  return (
    <figure className="bc-chart">
      {/* **라벨에 조사를 붙이지 말 것** — `기존 KTX 을` / `기존 2호선 을` 처럼 노선
            이름에 따라 은·는·을·를 이 틀어진다. 어느 수단인지는 아래 줄이 말한다 */}
        <figcaption>건설비 — 이미 있는 수단은 추가 비용이 들지 않는다</figcaption>
      <svg viewBox={`0 0 ${WIDTH} ${height}`} role="img"
           aria-label={`수단별 건설비 막대. ${existingOption.label}은 추가 건설비가 0 입니다.`
             + ' 자세한 값은 아래 표에 있습니다'}>
        {results.map((r, i) => {
          const y = i * ROW_H + (ROW_H - BAR_H) / 2;
          const w = Math.max(1, x(r.totalCost) - LABEL_W);
          return (
            <g key={resultKey(r)}
               className={resultKey(r) === selected ? 'bc-row picked' : 'bc-row'}
               onClick={() => onSelect?.(resultKey(r))}>
              <title>
                {`${MODE_LABEL[r.modeType] || r.modeType} `
                  + `${STRUCTURE_LABEL[r.structureType]} · ${money(r.totalCost)}`}
              </title>
              <rect x={0} y={i * ROW_H} width={WIDTH} height={ROW_H} className="bc-hit" />
              <text x={LABEL_W - 6} y={i * ROW_H + ROW_H / 2 + 4} className="bc-label">
                {MODE_LABEL[r.modeType] || r.modeType} {STRUCTURE_LABEL[r.structureType]}
              </text>
              <path d={barPath(LABEL_W, y, w, BAR_H, 4)} className="bc-bar" />
              {/* 가장 비싼 안에만 값을 적는다 — 축의 끝을 알려 주는 몫이다 */}
              {r.totalCost === max && (
                <text x={LABEL_W + plotW + 4} y={i * ROW_H + ROW_H / 2 + 4} className="bc-clip">
                  {money(r.totalCost)}
                </text>
              )}
            </g>
          );
        })}

        {/* 기존 수단 — 막대가 없는 것이 곧 메시지라 값을 글자로 적는다 */}
        <g className="bc-row existing">
          <title>{`${existingOption.label} · 추가 건설비 0`}</title>
          <text x={LABEL_W - 6} y={existingY + ROW_H / 2 + 4} className="bc-label">
            {existingOption.label}
          </text>
          <line x1={LABEL_W} y1={existingY + 4} x2={LABEL_W} y2={existingY + ROW_H - 4}
                className="bc-rule" />
          <text x={LABEL_W + 6} y={existingY + ROW_H / 2 + 4} className="bc-zero">0</text>
        </g>
      </svg>
    </figure>
  );
}
