import { totalLengthKm } from '../geo/haversine';
import CostTable from './CostTable';

const STRUCTURES = [
  ['UNDERGROUND', '지하'],
  ['ELEVATED', '고가'],
  ['AT_GRADE', '지상'],
];

export default function ScenarioPanel({
  points, title, structure, scenarios, scenariosLoaded, saving, error, savedLength,
  savedId, results, calculating, population, selectedResult, stale, editingId, existingOption,
  overLength, water, onApplyDetour,
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
        {/* 안내 두 개가 **조건 없이** 떠 있어, 노선을 다 그린 뒤에도 "출발지와 도착지를
            클릭하세요" 가 계속 보였다. 292px 패널에서 6~7줄을 영구히 차지한 셈이다.
            지우지 말고 **필요한 때만** 띄운다 — 그리기 전에는 그리는 법을, 다 그린 뒤에는
            다음에 무슨 일이 일어나는지를 보여 준다 */}
        {points.length === 0 ? (
          <p className="muted small">
            지도에서 출발지와 도착지를 클릭하세요. 더 누르면 사이에 경유점(◆)이 생깁니다 —
            경유점은 정차역이 아닙니다.
          </p>
        ) : !results?.length && (
          <p className="muted small">
            정차역은 저장 후 '비용 비교하기'를 누르면 수단별 역간격에 맞춰 자동으로 놓입니다.
          </p>
        )}
        <div className="metric">
          <span>{length.toFixed(3)}</span> km
          {points.length > 2 && <> · 경유점 {points.length - 2}개</>}
        </div>
        {/* 호수·저수지를 지나면 알리고 **제안만** 한다 — 바꾸는 것은 사용자다. 이름 뒤에
            조사를 붙이지 않는다(「위를」 은 이름이 아니라 「위」 에 붙는다) */}
        {water?.crossings?.length > 0 && (
          <div className="water-note small">
            {water.crossings.map((c) => (
              <p key={c.name}>
                <strong>{c.name}</strong> 위를 지납니다 —{' '}
                {c.detoured ? `돌아가면 ${c.addedKm.toFixed(1)}km 늘어납니다` : c.reason}
              </p>
            ))}
            {water.crossings.some((c) => c.detoured) && (
              <button onClick={onApplyDetour}>돌아가는 선형으로 바꾸기</button>
            )}
            <p className="muted">
              호수·저수지만 봅니다 — 강은 건너야 해서 빼었으니 돌아간 길이 강을 건널 수
              있습니다. 돌아가는 길이 1.5배를 넘으면 건너는 편으로
              둡니다 — 교량 단가 자료가 없어 정한 가정값입니다.
            </p>
          </div>
        )}
        {/* 물 위 구간 — 교량 할증을 못 넣은 몫이 얼마나 되는지 드러낸다(강·호수 모두).
            50m 도 안 되게 물가를 스치는 조각은 잡음이라 뺀다. 이름 뒤에 조사를 붙이지 않는다 */}
        {(() => {
          const spans = (water?.waterKm || []).filter((w) => w.km >= 0.05);
          return spans.length > 0 && (
            <p className="water-span small muted">
              <b>물 위 구간</b>{' '}
              {spans.map((w) => `${w.name} ${w.km.toFixed(1)}km`).join(' · ')} — 교량 할증은
              넣지 않아 건설비가 그만큼 낮게 잡혔을 수 있습니다
            </p>
          );
        })()}
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
        existingOption={existingOption}
        overLength={overLength}
        pointCount={points.length}
        onSelect={onSelectResult}
      />

      <section>
        <h2>내 시나리오 ({scenarios.length})</h2>
        {/* **"없음"과 "못 불러옴"을 가른다.** 목록이 비었다고 무조건 "없습니다"라고
            쓰면 서버가 꺼져 있거나 세션이 끊긴 사람에게 저장한 것이 사라졌다고
            말하는 셈이다 — 그럴듯한데 틀린 답이 가장 나쁘다 */}
        {scenarios.length === 0 && (
          <p className="muted">
            {scenariosLoaded
              ? '저장된 시나리오가 없습니다.'
              : '목록을 불러오지 못했습니다 — 저장된 시나리오가 없다는 뜻은 아닙니다.'}
          </p>
        )}
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
