const MODE_LABEL = {
  HEAVY_METRO: '지하철',
  LIGHT_RAIL: '경전철',
  TRAM: '트램',
  BRT_HIGH: 'BRT 고급형',
  BRT_LOW: 'BRT 저급형',
  DOUBLE_ELEC: '복선전철',
};

const STRUCTURE_LABEL = { UNDERGROUND: '지하', ELEVATED: '고가', AT_GRADE: '지상' };

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

export default function CostTable({ results, structure, population }) {
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
      <p className="muted small">
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
              key={`${r.modeType}-${r.structureType}`}
              className={[
                r === best ? 'cheapest' : '',
                r.structureType === structure ? 'selected' : '',
                r.feasible === false ? 'infeasible' : '',
              ].join(' ')}
              title={r.warning || ''}
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
            <strong>B/C는 아직 인용하지 마세요</strong> — 시간가치·할인율 원단위가 미확정입니다.
          </>
        )}
      </p>
    </section>
  );
}
