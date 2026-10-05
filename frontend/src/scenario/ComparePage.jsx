import { useEffect, useState } from 'react';
import { api } from '../api/client';
import { resultKey } from './resultKey';
import { isStale, STALE_MESSAGE } from './stale';

/**
 * 시나리오 비교 (제안서 기능 8).
 *
 * 한 노선 안에서 수단을 고르는 것은 비교표가 이미 한다. 여기서는 **노선끼리** 견준다 —
 * "강북 노선과 강남 노선 중 어디가 먼저인가" 같은 질문이다.
 *
 * 목록 API 는 요약만 주므로 고른 시나리오의 상세를 따로 받는다. 한 번에 서너 개라
 * 호출 수가 문제되지 않고, 요약에 필드를 더하면 목록이 무거워진다.
 */
const MODE_LABEL = {
  HEAVY_METRO: '지하철', LIGHT_RAIL: '경전철', TRAM: '트램',
  BRT_HIGH: 'BRT 고급형', BRT_LOW: 'BRT 저급형', DOUBLE_ELEC: '복선전철',
};
const STRUCTURE_LABEL = { UNDERGROUND: '지하', ELEVATED: '고가', AT_GRADE: '지상' };
const MAX_COMPARE = 4;

function money(eok) {
  if (eok == null) return '—';
  if (eok >= 10000) {
    const jo = Math.floor(eok / 10000);
    const rest = Math.round(eok % 10000);
    return rest ? `${jo}조 ${rest.toLocaleString()}억` : `${jo}조`;
  }
  return `${Math.round(eok).toLocaleString()}억`;
}

const people = (n) =>
  n == null ? '—' : n >= 10000 ? `${(n / 10000).toFixed(1)}만` : n.toLocaleString();

const bestOf = (d) =>
  (d.results || []).filter((r) => r.bcRatio != null)
    .sort((a, b) => b.bcRatio - a.bcRatio)[0] || null;

export default function ComparePage({ onClose }) {
  const [list, setList] = useState([]);
  const [picked, setPicked] = useState([]);
  const [details, setDetails] = useState({});
  const [error, setError] = useState(null);
  const [referenceUpdatedAt, setReferenceUpdatedAt] = useState(null);

  useEffect(() => {
    api.listScenarios().then(setList).catch((e) => setError(e.message));
    api.referenceUpdatedAt().then(setReferenceUpdatedAt).catch(() => {});
  }, []);

  useEffect(() => {
    const missing = picked.filter((id) => !details[id]);
    if (!missing.length) return;
    Promise.all(missing.map((id) => api.getScenario(id)))
      .then((loaded) => setDetails((d) => ({
        ...d,
        ...Object.fromEntries(loaded.map((s) => [s.id, s])),
      })))
      .catch((e) => setError(e.message));
  }, [picked, details]);

  function toggle(id) {
    setPicked((p) =>
      p.includes(id) ? p.filter((x) => x !== id)
        : p.length >= MAX_COMPARE ? p : [...p, id]);
  }

  const chosen = picked.map((id) => details[id]).filter(Boolean);
  // 고른 시나리오 중 하나라도 가진 수단×구조를 모아 행으로 쓴다
  const modeRows = [...new Map(
    chosen.flatMap((d) => d.results || []).map((r) => [resultKey(r), r])
  ).keys()];

  const cellOf = (d, key) => (d.results || []).find((r) => resultKey(r) === key);

  return (
    <div className="admin">
      <header>
        <strong>시나리오 비교</strong>
        <span className="muted small">최대 {MAX_COMPARE}개까지 나란히 볼 수 있습니다.</span>
        <span className="spacer" />
        <button onClick={onClose}>지도로 돌아가기</button>
      </header>

      {error && <p className="error">{error}</p>}

      <section>
        <h2>비교할 노선 고르기 ({picked.length}/{MAX_COMPARE})</h2>
        {list.length === 0 && <p className="muted">저장된 시나리오가 없습니다.</p>}
        <ul className="pick-list">
          {list.map((s) => (
            <li key={s.id}>
              <label>
                <input
                  type="checkbox"
                  checked={picked.includes(s.id)}
                  disabled={!picked.includes(s.id) && picked.length >= MAX_COMPARE}
                  onChange={() => toggle(s.id)}
                />
                {s.title}
                <span className="muted small"> {Number(s.totalLengthKm).toFixed(1)}km</span>
              </label>
            </li>
          ))}
        </ul>
      </section>

      {chosen.length > 0 && (
        <>
          <section>
            <h2>개요</h2>
            <table className="admin-table compare">
              <thead>
                <tr>
                  <th>항목</th>
                  {chosen.map((d) => (
                    <th key={d.id}>
                      {d.title}
                      {isStale(d.calculatedAt, referenceUpdatedAt) && (
                        <span className="tag" title={STALE_MESSAGE}>기준값 변경됨</span>
                      )}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td className="name">연장</td>
                  {chosen.map((d) => (
                    <td key={d.id}>{Number(d.totalLengthKm).toFixed(2)} km</td>
                  ))}
                </tr>
                <tr>
                  <td className="name">주변 인구 (반경 1km)</td>
                  {chosen.map((d) => <td key={d.id}>{people(d.population1km)}명</td>)}
                </tr>
                <tr>
                  <td className="name">예상 이용객</td>
                  {chosen.map((d) => {
                    const r = (d.results || [])[0];
                    return <td key={d.id}>{people(r?.estimatedRidership)}명/일</td>;
                  })}
                </tr>
                <tr>
                  <td className="name">첨두 단면</td>
                  {chosen.map((d) => {
                    const r = (d.results || [])[0];
                    return <td key={d.id}>{people(r?.peakPphpd)}명/시</td>;
                  })}
                </tr>
                <tr>
                  <td className="name">추천 수단</td>
                  {chosen.map((d) => (
                    <td key={d.id}>{MODE_LABEL[d.recommendedMode] || '—'}</td>
                  ))}
                </tr>
                <tr>
                  <td className="name">최고 B/C</td>
                  {chosen.map((d) => {
                    const b = bestOf(d);
                    return (
                      <td key={d.id}>
                        {b ? (
                          <>
                            <strong>{Number(b.bcRatio).toFixed(2)}</strong>
                            <span className="muted small">
                              {' '}{MODE_LABEL[b.modeType]} {STRUCTURE_LABEL[b.structureType]}
                            </span>
                          </>
                        ) : '계산 전'}
                      </td>
                    );
                  })}
                </tr>
                <tr>
                  <td className="name">최고안 건설비</td>
                  {chosen.map((d) => <td key={d.id}>{money(bestOf(d)?.totalCost)}</td>)}
                </tr>
              </tbody>
            </table>
          </section>

          {modeRows.length > 0 && (
            <section>
              <h2>수단별 B/C</h2>
              <p className="muted small">
                같은 수단을 노선끼리 견줍니다. 괄호는 건설비입니다.
                절대값보다 순위를 보세요 — 수요 모델 오차가 1.75배입니다.
              </p>
              <table className="admin-table compare">
                <thead>
                  <tr>
                    <th>수단</th>
                    {chosen.map((d) => <th key={d.id}>{d.title}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {modeRows.map((key) => {
                    // 그 수단에서 B/C 가 가장 높은 노선을 굵게 — 어느 노선에 맞는 수단인지 보인다
                    const best = Math.max(
                      ...chosen.map((d) => cellOf(d, key)?.bcRatio ?? -1));
                    const [mode, structure] = key.split('-');
                    return (
                      <tr key={key}>
                        <td className="name">
                          {MODE_LABEL[mode] || mode} {STRUCTURE_LABEL[structure]}
                        </td>
                        {chosen.map((d) => {
                          const r = cellOf(d, key);
                          const top = r && r.bcRatio != null && Number(r.bcRatio) === best;
                          return (
                            <td key={d.id} className={top ? 'top' : ''}>
                              {r?.bcRatio == null ? '—' : Number(r.bcRatio).toFixed(2)}
                              {r && (
                                <span className="muted small"> ({money(r.totalCost)})</span>
                              )}
                              {r?.feasible === false && (
                                <span className="tag">수송능력 초과</span>
                              )}
                            </td>
                          );
                        })}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </section>
          )}
        </>
      )}
    </div>
  );
}
