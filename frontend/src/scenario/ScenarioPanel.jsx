import { totalLengthKm } from '../geo/haversine';

const STRUCTURES = [
  ['UNDERGROUND', '지하'],
  ['ELEVATED', '고가'],
  ['AT_GRADE', '지상'],
];

export default function ScenarioPanel({
  points, title, structure, scenarios, saving, error, savedLength,
  onTitle, onStructure, onUndo, onClear, onSave, onLoad, onDelete,
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
        <p className="muted">지도를 클릭해 출발·경유·도착을 찍으세요.</p>
        <div className="metric">
          <span>{length.toFixed(3)}</span> km · 지점 {points.length}개
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
        <button className="primary" onClick={onSave} disabled={!canSave || saving}>
          {saving ? '저장 중…' : '시나리오 저장'}
        </button>
      </section>

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
