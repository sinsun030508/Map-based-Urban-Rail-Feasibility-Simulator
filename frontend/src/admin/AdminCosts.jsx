import { Fragment, useEffect, useState } from 'react';
import { api } from '../api/client';
import { MODE_LABEL, STRUCTURE_LABEL } from '../modeLabels';
import { costDraftError } from './adminRules';

const FIELDS = [
  ['fixedCost', '고정비'],
  ['costPerKm', 'km단가'],
  ['costPerStation', '역 단가'],
];

const label = (r) => `${MODE_LABEL[r.modeType] || r.modeType} ${STRUCTURE_LABEL[r.structureType] || ''}`.trim();

/**
 * 건설비 단가 (제안서 기능 8). 수정은 **새 행을 만들고 이전 행을 내린다** — 저장된 결과가 그 행을 참조해 덮어쓰면 과거
 * 결과의 근거가 바뀐다. 그래서 저장하면 행의 id 가 바뀌고, 이전 값은 아래 이력에 남는다. 실패는 그 행 아래에 적는다.
 */
export default function AdminCosts() {
  const [rows, setRows] = useState(null);
  const [draft, setDraft] = useState({});
  const [error, setError] = useState(null);
  const [saved, setSaved] = useState(null);

  const load = () => api.adminCosts().then(setRows);

  useEffect(() => {
    load().catch((e) => setError({ id: null, message: e.message }));
  }, []);

  useEffect(() => {
    if (!saved) return undefined;
    const timer = setTimeout(() => setSaved(null), 3000);
    return () => clearTimeout(timer);
  }, [saved]);

  const edit = (id, patch) => {
    setError((e) => (e?.id === id ? null : e));
    setDraft((d) => ({ ...d, [id]: { ...d[id], ...patch } }));
  };

  async function save(row) {
    const d = { ...row, ...draft[row.id] };
    setError(null);
    try {
      const updated = await api.updateCost(row.id, {
        fixedCost: Number(d.fixedCost),
        costPerKm: Number(d.costPerKm),
        costPerStation: Number(d.costPerStation),
        source: d.source,
      });
      setDraft((x) => ({ ...x, [row.id]: undefined }));
      await load();
      setSaved(updated.id);
    } catch (e) {
      setError({ id: row.id, message: e.message });
    }
  }

  if (error?.id === null) return <p className="error">{error.message}</p>;
  if (!rows) return <p className="muted small">불러오는 중입니다</p>;

  const active = rows.filter((r) => r.active);
  const past = rows.filter((r) => !r.active);

  return (
    <section>
      <h2>건설비 단가 ({active.length})</h2>
      <p className="muted small">
        억원 기준, 건설비 = 고정비 + km단가 × 연장 + 역 단가 × 역 수입니다. 이 값은 <strong>회귀 결과</strong>입니다
        (<code>etl/cost_model.py</code>, 교차검증 1.25배) — 손으로 고치면 모델과 어긋나고, 시드를 다시 넣으면 새 값으로
        내려갑니다. 저장하면 이전 값은 아래 이력에 남고, 그 단가로 계산한 결과에는 「다시 계산」 알림이 뜹니다.
      </p>
      <table className="admin-table">
        <thead>
          <tr>
            <th>수단</th>
            {FIELDS.map(([f, name]) => <th key={f}>{name}</th>)}
            <th>기준연도</th><th>적용일</th><th>근거</th><th />
          </tr>
        </thead>
        <tbody>
          {active.map((r) => {
            const d = draft[r.id] || {};
            const problem = costDraftError(r, d);
            return (
              <Fragment key={r.id}>
                <tr className={saved === r.id ? 'just-saved' : ''}>
                  <td className="name">{label(r)}</td>
                  {FIELDS.map(([f]) => (
                    <td key={f}>
                      <input
                        type="number" step="1" className="narrow"
                        value={d[f] ?? r[f]}
                        onChange={(e) => edit(r.id, { [f]: e.target.value })}
                      />
                    </td>
                  ))}
                  <td className="unit">{r.baseYear}</td>
                  <td className="unit">{r.effectiveFrom}</td>
                  <td className="source">
                    <input
                      maxLength={150}
                      value={d.source ?? r.source ?? ''}
                      onChange={(e) => edit(r.id, { source: e.target.value })}
                      placeholder="어디서 온 값인지 적어 주세요"
                    />
                  </td>
                  <td>
                    <button disabled={problem !== null} onClick={() => save(r)}>저장</button>
                  </td>
                </tr>
                {(problem || error?.id === r.id) && (
                  <tr className="row-error">
                    <td />
                    <td colSpan={7} className="error">{problem || error.message}</td>
                  </tr>
                )}
              </Fragment>
            );
          })}
        </tbody>
      </table>
      {past.length > 0 && (
        <details className="cost-history">
          <summary>이전 기준 {past.length}개 — 저장된 결과가 참조해 지우지 않습니다</summary>
          <table className="admin-table">
            <thead>
              <tr>
                <th>수단</th>
                {FIELDS.map(([f, name]) => <th key={f}>{name}</th>)}
                <th>적용일</th><th>근거</th>
              </tr>
            </thead>
            <tbody>
              {past.map((r) => (
                <tr key={r.id}>
                  <td className="name">{label(r)}</td>
                  {FIELDS.map(([f]) => <td key={f} className="num">{r[f].toLocaleString()}</td>)}
                  <td className="unit">{r.effectiveFrom}</td>
                  <td className="muted small">{r.source}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </details>
      )}
    </section>
  );
}
