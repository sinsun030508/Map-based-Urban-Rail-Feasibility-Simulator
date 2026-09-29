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

export default function CostTable({ results, structure }) {
  if (!results.length) return null;

  const sorted = [...results].sort((a, b) => a.totalCost - b.totalCost);
  const cheapest = sorted[0];

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
      <h2>수단별 건설비</h2>
      <p className="muted small">
        연장과 구조에 비용 계수를 적용한 값입니다. 역수는 수단별 표준 역간격으로 추정했습니다.
        <br />수요·B/C는 SGIS 연동 후에 채웁니다.
      </p>

      <table className="cost">
        <thead>
          <tr><th>수단</th><th>구조</th><th>역</th><th>소요</th><th>건설비</th></tr>
        </thead>
        <tbody>
          {sorted.map((r) => {
            const selected = r.structureType === structure;
            return (
              <tr
                key={`${r.modeType}-${r.structureType}`}
                className={[
                  r === cheapest ? 'cheapest' : '',
                  selected ? 'selected' : '',
                ].join(' ')}
                title={r.warning || ''}
              >
                <td>{MODE_LABEL[r.modeType] || r.modeType}</td>
                <td>{STRUCTURE_LABEL[r.structureType]}</td>
                <td>{r.stationCount}</td>
                <td>{r.travelTimeMin ? `${Math.round(r.travelTimeMin)}분` : '—'}</td>
                <td className="num">{money(r.totalCost)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>

      {elevated && (
        <p className="small note">
          {MODE_LABEL[pair.modeType]}을 지하로 지으면 고가보다{' '}
          <strong>+{money(pair.totalCost - elevated.totalCost)}</strong> 더 듭니다
          ({(pair.totalCost / elevated.totalCost).toFixed(1)}배).
        </p>
      )}

      {[...new Set(results.map((r) => r.warning).filter(Boolean))].map((w) => (
        <p key={w} className="small warn">⚠ {w}</p>
      ))}

      <p className="small muted">
        비용 모델의 교차검증 오차는 1.25배입니다. 단일 값이 아니라 비교용 지표로 보세요.
      </p>
    </section>
  );
}
