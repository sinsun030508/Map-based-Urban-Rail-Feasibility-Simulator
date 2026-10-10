import { totalLengthKm } from '../geo/haversine';
import CostTable from './CostTable';

const STRUCTURES = [
  ['UNDERGROUND', '지하'],
  ['ELEVATED', '고가'],
  ['AT_GRADE', '지상'],
];

export default function ScenarioPanel({
  points, title, structure, scenarios, saving, error, savedLength,
  savedId, results, calculating, population, selectedResult, stale, editingId, existingLine,
  onTitle, onStructure, onUndo, onClear, onSave, onLoad, onDelete, onCalculate,
  onSelectResult,
}) {
  const length = totalLengthKm(points);
  const canSave = points.length >= 2 && title.trim().length > 0;
  // 서버도 같은 Haversine 을 쓰므로 값이 어긋나면 둘 중 하나가 틀린 것이다
  const mismatch =
    savedLength != null && Math.abs(savedLength - length) > 0.01;

  return (
    <aside className="panel">
      <section>
        <h2>노선 그리기</h2>
        <p className="muted">
          지도에서 출발지와 도착지를 클릭하세요. 정차역은 저장 후 '비용 비교하기'를 누르면
          수단별 역간격에 맞춰 그 사이에 자동으로 놓입니다.
        </p>
        <p className="muted small">
          노선을 꺾으려면 더 클릭하세요 — 마지막 점이 도착지가 되고 앞의 점은 경유점(◆)이 됩니다.
          경유점은 정차역이 아닙니다.
        </p>
        <div className="metric">
          <span>{length.toFixed(3)}</span> km
          {points.length > 2 && <> · 경유점 {points.length - 2}개</>}
        </div>
        {mismatch && (
          <p className="error">
            서버 계산 {savedLength.toFixed(3)}km 와 다릅니다 — 확인이 필요합니다
          </p>
        )}
        <div className="row">
          <button onClick={onUndo} disabled={!points.length}>마지막 지점 취소</button>
          <button onClick={onClear} disabled={!points.length}>전체 지우기</button>
        </div>
      </section>

      <section>
        <h2>저장</h2>
        <label>
          제목
          <input value={title} onChange={(e) => onTitle(e.target.value)} placeholder="예: 강남~하남 노선안" />
        </label>
        <label>
          구조 형식
          <select value={structure} onChange={(e) => onStructure(e.target.value)}>
            {STRUCTURES.map(([value, label]) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
        </label>
        <p className="muted small">
          구조는 비용을 가장 크게 가르는 변수입니다. 지하는 고가의 약 1.8배입니다.
        </p>
        {error && <p className="error">{error}</p>}
        {/* 불러온 시나리오를 고치는 중이면 덮어쓴다 — 안 그러면 같은 제목이 하나 더 생긴다 */}
        <button className="primary" onClick={() => onSave(false)} disabled={!canSave || saving}>
          {saving ? '저장 중…' : editingId ? '수정 저장' : '시나리오 저장'}
        </button>
        {editingId && (
          <button className="calc" onClick={() => onSave(true)} disabled={!canSave || saving}>
            새 시나리오로 저장
          </button>
        )}
        <button
          className="calc"
          onClick={onCalculate}
          disabled={!savedId || calculating}
          title={savedId ? '' : '먼저 저장해야 계산할 수 있습니다'}
        >
          {calculating ? '계산 중…' : '비용 비교하기'}
        </button>
      </section>

      <CostTable
        results={results}
        structure={structure}
        population={population}
        selected={selectedResult}
        stale={stale}
        existingLine={existingLine}
        pointCount={points.length}
        onSelect={onSelectResult}
      />

      <section>
        <h2>내 시나리오 ({scenarios.length})</h2>
        {scenarios.length === 0 && <p className="muted">저장된 시나리오가 없습니다.</p>}
        <ul className="list">
          {scenarios.map((s) => (
            <li key={s.id}>
              <button className="link" onClick={() => onLoad(s.id)}>
                {s.title}
              </button>
              <span className="muted small">{Number(s.totalLengthKm).toFixed(1)}km</span>
              <button className="link danger" onClick={() => onDelete(s.id)}>삭제</button>
            </li>
          ))}
        </ul>
      </section>
    </aside>
  );
}
