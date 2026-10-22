import { useEffect, useState } from 'react';
import { api } from '../api/client';
import { gainsNothing } from './gainsNothing';
import { resultKey } from './resultKey';
import { isStale, STALE_MESSAGE } from './stale';
import { MODE_LABEL, STRUCTURE_LABEL } from '../modeLabels';
import { money, people } from '../format';

/**
 * 시나리오 비교 (제안서 기능 8).
 *
 * 한 노선 안에서 수단을 고르는 것은 비교표가 이미 한다. 여기서는 **노선끼리** 견준다 —
 * "강북 노선과 강남 노선 중 어디가 먼저인가" 같은 질문이다.
 *
 * 목록 API 는 요약만 주므로 고른 시나리오의 상세를 따로 받는다. 한 번에 서너 개라
 * 호출 수가 문제되지 않고, 요약에 필드를 더하면 목록이 무거워진다.
 */
const MAX_COMPARE = 4;


/**
 * 그 노선의 **추천안** — 「추천 수단」 칸이 가리키는 바로 그 안이다.
 *
 * 전에는 B/C 최고안을 그냥 집었다. 그러면 서울~부산에서 「추천 수단」은 광역급행인데
 * 「최고 B/C」는 **340km 짜리 BRT 고급형**이라고 적혀, 한 표가 두 안을 동시에
 * 가리켰다. 권장 연장을 넘는 수단을 거르는 규칙은 백엔드가 이미 적용해
 * `recommendedMode` 로 알려 주므로, 화면은 **그 답을 따르기만 하면 된다** —
 * 여기서 다시 계산하면 또 어긋난다.
 *
 * 한 수단에 지하·고가 두 행이 있으므로 그중 B/C 가 높은 쪽을 쓴다.
 *
 * **B/C 0 은 추천안이 아니다**(`gainsNothing`). 백엔드는 전부 0 이면 추천을 비우는데,
 * 그때 이 함수가 아무거나 집으면 「추천 수단 —」 옆에 「추천안 B/C 0.00」 이 찍힌다.
 */
const bestOf = (d) =>
  (d.results || [])
    .filter((r) => r.bcRatio != null && !gainsNothing(r)
      && (d.recommendedMode == null || r.modeType === d.recommendedMode))
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
                {/* 신설안끼리만 견주면 **이미 있는 길에 또 짓는 안**이 1위로 올라와도
                    보이지 않는다. 예타도 신설안만 보지 않고 기존 시설 활용안과 비교한다 —
                    노선을 고르는 화면이라면 이 줄이 가장 먼저 봐야 할 줄일 수 있다 */}
                <tr>
                  <td className="name">이미 있는 수단</td>
                  {/* **설명을 칸에 넣지 않는다.** `table-layout: auto` 라 한 칸의 글자가
                      열 전체 폭을 정한다 — 60자짜리 KTX 설명 때문에 그 열만 460px 로
                      부풀어(다른 열은 117~130px) 「추천안 B/C」 가 "BRT 고급형 지/상" 으로
                      줄바꿈됐다. 비용표에서 이미 한 번 겪은 함정이다.
                      여기서 볼 것은 **있느냐 없느냐**이고, 어느 역을 쓰는지는 그 노선의
                      비용 비교 화면에 그대로 나온다 */}
                  {chosen.map((d) => (
                    <td key={d.id} className="existing"
                        title={d.existingOption?.detail || undefined}>
                      {d.existingOption
                        ? <strong>{d.existingOption.label}</strong>
                        : <span className="muted">없음</span>}
                    </td>
                  ))}
                </tr>
                <tr>
                  <td className="name">추천안 B/C</td>
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
                  <td className="name">추천안 건설비</td>
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
                    // 그 수단에서 B/C 가 가장 높은 노선을 굵게 — 어느 노선에 맞는 수단인지 보인다.
                    // 노선이 하나뿐이면 전부 1등이라 강조가 의미를 잃는다
                    const best = chosen.length > 1
                      ? Math.max(...chosen.map((d) => cellOf(d, key)?.bcRatio ?? -1))
                      : null;
                    const [mode, structure] = key.split('-');
                    return (
                      <tr key={key}>
                        <td className="name">
                          {MODE_LABEL[mode] || mode} {STRUCTURE_LABEL[structure]}
                        </td>
                        {chosen.map((d) => {
                          const r = cellOf(d, key);
                          const top = best != null && r && r.bcRatio != null
                            && Number(r.bcRatio) === best;
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
