import { useEffect, useRef, useState } from 'react';
import { api } from '../api/client';
import {
  routeKey, isCurrent, minutes, legName, legColor, legWeight, itinerarySummary, ownSummary, searchedLabel,
  statusMessage,
} from './transit';
import { transitFeatures } from '../geo/transitLine';

/**
 * 지도 아래의 「지금 있는 대중교통과 비교」 — 그린 노선 양 끝의 실제 경로(TMAP)를 신설안 아래에 늘어놓는다.
 *
 * **누를 때만 부른다**(무료 하루 10건). 점이 바뀌면 앞 답은 그리지 않는다. 계산 결과가 있고 서버에 키가 있을 때만 보인다.
 */
export default function TransitBar({ points, row, ready, selected, onSelect }) {
  const [enabled, setEnabled] = useState(false);
  const [answer, setAnswer] = useState(null);
  const [loadingKey, setLoadingKey] = useState(null);
  const [collapsed, setCollapsed] = useState(false);
  const lastAsked = useRef(null);
  const key = routeKey(points);

  useEffect(() => {
    api.transitStatus().then((r) => setEnabled(r.enabled)).catch(() => {});
  }, []);

  if (!enabled || !ready || points.length < 2) return null;

  const now = isCurrent(answer, key) ? answer : null;
  const loading = loadingKey === key;
  const own = ownSummary(row);

  async function ask() {
    lastAsked.current = key;
    onSelect(null);
    setLoadingKey(key);
    const [from, to] = [points[0], points[points.length - 1]]
      .map(([lng, lat]) => ({ latitude: lat, longitude: lng }));
    let next;
    try {
      next = { key, ...(await api.transit(from, to)) };
    } catch (e) {
      next = { key, status: 'FAILED', message: e.message };
    }
    // 그사이 다른 노선을 물었으면 이 답은 버린다
    if (lastAsked.current !== key) return;
    setAnswer(next);
    setLoadingKey(null);
    setCollapsed(false);
  }

  if (!now) {
    return (
      <button type="button" className="transit-open" onClick={ask} disabled={loading}>
        {loading ? '대중교통 경로를 찾는 중…' : '지금 있는 대중교통과 비교'}
      </button>
    );
  }

  return (
    <section className="transit-bar" aria-label="지금 있는 대중교통과 비교">
      <div className="transit-head">
        <strong>지금 있는 대중교통과 비교</strong>
        <span className="muted small">
          TMAP{now.searchedFor ? ` · ${searchedLabel(now.searchedFor)}` : ''} · 경로 상자를 누르면 지도에 그립니다
        </span>
        <span className="spacer" />
        <button type="button" onClick={() => setCollapsed((v) => !v)}>{collapsed ? '펼치기' : '접기'}</button>
        <button type="button" onClick={() => { onSelect(null); setAnswer(null); }}>닫기</button>
      </div>
      {!collapsed && (
        <ol className="transit-list">
          {own && (
            <li className="transit-card own" onClick={() => onSelect(null)} title="누르면 실제 경로를 지도에서 지웁니다">
              <strong className="transit-name">{own.name}</strong>
              <span className="transit-total">{own.total}</span>
              <span className="muted">{own.note}</span>
            </li>
          )}
          {now.status !== 'OK' && (
            <li className="transit-card">
              <span className="warn">{now.message || statusMessage(now.status)}</span>
              {(now.status === 'FAILED') && <button type="button" onClick={ask} disabled={loading}>다시 시도</button>}
            </li>
          )}
          {(now.itineraries || []).map((it, i) => {
            const box = itinerarySummary(it);
            const picked = selected === i;
            const drawable = transitFeatures(it.legs).length > 0;
            const pick = () => { if (drawable) onSelect(picked ? null : { key, index: i, legs: it.legs }); };
            return (
              <li key={i}
                className={['transit-card', it.running ? '' : 'idle', drawable ? 'pickable' : '', picked ? 'picked' : ''].join(' ')}
                role={drawable ? 'button' : undefined} tabIndex={drawable ? 0 : undefined}
                aria-pressed={drawable ? picked : undefined} onClick={pick}
                onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pick(); } }}>
                <strong className="transit-name">실제 경로 {i + 1}</strong>
                <span className="transit-total">{box.total}</span>
                <span className="muted">{box.facts}</span>
                <span className="muted">{box.ride}</span>
                {!it.running && <span className="warn">그 시각에 다니지 않는 구간이 있습니다</span>}
                {!drawable && <span className="muted">선 좌표가 없어 지도에 그리지 못합니다</span>}
                <div className="transit-legs">
                  {it.legs.map((leg, j) => (
                    <span key={j} className="transit-leg" title={[leg.from, leg.to].filter(Boolean).join(' → ')}
                      style={{ flexGrow: legWeight(leg), borderBottomColor: legColor(leg) }}>
                      {legName(leg)} <small>{minutes(leg.sec)}분</small>{!leg.running && <small className="warn"> 운행 안 함</small>}
                    </span>
                  ))}
                </div>
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}
