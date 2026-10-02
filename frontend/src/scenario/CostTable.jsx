import { STALE_MESSAGE } from './stale';

const MODE_LABEL = {
  HEAVY_METRO: '지하철',
  LIGHT_RAIL: '경전철',
  TRAM: '트램',
  BRT_HIGH: 'BRT 고급형',
  BRT_LOW: 'BRT 저급형',
  DOUBLE_ELEC: '복선전철',
};

const STRUCTURE_LABEL = { UNDERGROUND: '지하', ELEVATED: '고가', AT_GRADE: '지상' };

/** 수단×구조가 한 행 — 선택 상태를 이 키로 들고 다닌다 */
export const key = (r) => `${r.modeType}-${r.structureType}`;

/** 억원 → 읽기 쉬운 단위. 1조가 넘으면 조 단위로 끊는다 */
function money(eok) {
  if (eok >= 10000) {
    const jo = Math.floor(eok / 10000);
    const rest = Math.round(eok % 10000);
    return rest ? `${jo}조 ${rest.toLocaleString()}억` : `${jo}조`;
  }
  return `${Math.round(eok).toLocaleString()}억`;
}

function people(n) {
  if (n == null) return '—';
  return n >= 10000 ? `${(n / 10000).toFixed(1)}만` : n.toLocaleString();
}

export default function CostTable({ results, structure, population, selected, stale, onSelect }) {
  if (!results.length) return null;

  const hasDemand = results.some((r) => r.bcRatio != null);
  // B/C 가 있으면 그 순, 없으면 싼 순 — 볼 것이 다르다
  const sorted = [...results].sort((a, b) =>
    hasDemand ? (b.bcRatio ?? -1) - (a.bcRatio ?? -1) : a.totalCost - b.totalCost
  );
  const best = sorted[0];
  const riders = results.find((r) => r.estimatedRidership != null)?.estimatedRidership;
  const peak = results.find((r) => r.peakPphpd != null)?.peakPphpd;

  // 같은 수단의 지하·고가 차이 — "지하를 고르면 얼마가 더 드는가"
  const pair = results.find(
    (r) => r.structureType === 'UNDERGROUND' &&
      results.some((o) => o.modeType === r.modeType && o.structureType === 'ELEVATED')
  );
  const elevated = pair && results.find(
    (r) => r.modeType === pair.modeType && r.structureType === 'ELEVATED'
  );

  return (
    <section>
      <h2>수단별 비교</h2>
      {stale && <p className="error">{STALE_MESSAGE}</p>}
      <p className="muted small">
        행을 누르면 그 수단의 정차역이 지도에 표시됩니다.
        역수는 수단별 표준 역간격으로 추정했습니다.
        {hasDemand
          ? ' B/C가 높은 순입니다.'
          : ' 수요를 구하지 못해 건설비 순으로 보여 줍니다.'}
      </p>

      {hasDemand && (
        <div className="metric small">
          주변 인구 <strong>{people(population)}명</strong> · 예상 이용객{' '}
          <strong>{people(riders)}명/일</strong> · 첨두 <strong>{people(peak)}명/시</strong>
        </div>
      )}

      <table className="cost">
        <thead>
          <tr>
            <th>수단</th><th>구조</th><th>역</th><th>소요</th>
            <th>건설비</th><th>B/C</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((r) => (
            <tr
              key={key(r)}
              className={[
                r === best ? 'cheapest' : '',
                key(r) === (selected || key(best)) ? 'picked' : '',
                r.structureType === structure ? 'selected' : '',
                r.feasible === false ? 'infeasible' : '',
              ].join(' ')}
              title={r.warning || ''}
              onClick={() => onSelect?.(key(r))}
            >
              <td>{MODE_LABEL[r.modeType] || r.modeType}</td>
              <td>{STRUCTURE_LABEL[r.structureType]}</td>
              <td>{r.stationCount}</td>
              <td>{r.travelTimeMin ? `${Math.round(r.travelTimeMin)}분` : '—'}</td>
              <td className="num">{money(r.totalCost)}</td>
              <td className="num">
                {r.bcRatio == null ? '—' : Number(r.bcRatio).toFixed(2)}
                {r.feasible === false && <span className="tag">수송능력 초과</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {elevated && (
        <p className="small note">
          {MODE_LABEL[pair.modeType]}을 지하로 지으면 고가보다{' '}
          <strong>+{money(pair.totalCost - elevated.totalCost)}</strong> 더 듭니다
          ({(pair.totalCost / elevated.totalCost).toFixed(1)}배)
          {pair.bcRatio != null && elevated.bcRatio != null && (
            <> — B/C {Number(elevated.bcRatio).toFixed(2)} → {Number(pair.bcRatio).toFixed(2)}</>
          )}.
        </p>
      )}

      {[...new Set(results.map((r) => r.warning).filter(Boolean))].map((w) => (
        <p key={w} className="small warn">⚠ {w}</p>
      ))}

      <p className="small muted">
        비용 모델 오차 1.25배, 수요 모델 오차 1.75배입니다. 단일 값이 아니라 비교용 지표로 보세요.
        {hasDemand && (
          <>
            <br />
            B/C는 투자평가지침 제7판 원단위(시간가치 7,231원/인·시, 할인율 4.5%·3.5%)로
            공사 5년과 개통 후 40년을 현재가치 환산한 값입니다. 표의 건설비는 명목 총액이라
            B/C와 직접 나눠지지 않습니다.
            <br />
            <strong>절대값보다 순위를 보세요</strong> — 첨두율과 전환 전 속도는 지침에
            원단위가 없는 가정값이고, 편익은 통행시간 절감만 셉니다.
          </>
        )}
      </p>
    </section>
  );
}
