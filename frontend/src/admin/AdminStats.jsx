import { useEffect, useState } from 'react';
import { api } from '../api/client';
import { MODE_LABEL } from '../modeLabels';
import { calculatedLine, peakOf } from './adminRules';

const PROVIDER_LABEL = { LOCAL: '이메일', GOOGLE: 'Google', KAKAO: '카카오' };

const W = 640;
const H = 130;
const TOP = 16;       // 최댓값 글자 자리
const BOTTOM = 18;    // 날짜 글자 자리

/**
 * 최근 30일 일별 생성 수 — 계열이 하나라 한 색·범례 없음(제목이 곧 이름), `BcChart`·`CostChart` 와 같은 집이다.
 * 막대마다 숫자를 달지 않고 **최댓값만** 적는다. 날짜별 값은 칸 전체에 건 `title` 로 본다.
 */
function DailyChart({ days }) {
  const peak = peakOf(days);
  if (!peak) return <p className="muted small">최근 {days.length}일에 만든 시나리오가 없습니다</p>;
  const slot = W / days.length;
  const plotH = H - TOP - BOTTOM;
  const base = H - BOTTOM;
  const ticks = [0, Math.floor(days.length / 2), days.length - 1];
  const peakIndex = days.findIndex((d) => d.count === peak);
  return (
    <figure className="bc-chart stats-daily">
      <figcaption>최근 {days.length}일 일별 시나리오 생성 수</figcaption>
      <svg viewBox={`0 0 ${W} ${H}`} role="img"
           aria-label={`최근 ${days.length}일 일별 시나리오 생성 수. 가장 많은 날은 ${days[peakIndex].date} ${peak}개입니다`}>
        {days.map((d, i) => {
          const h = (d.count / peak) * plotH;
          return (
            <g key={d.date} className="bc-row stat-day">
              <title>{`${d.date} · ${d.count}개`}</title>
              <rect x={i * slot} y={0} width={slot} height={H} className="bc-hit" />
              {d.count > 0 && (
                <rect x={i * slot + 1} y={base - h} width={slot - 2} height={h} rx={2} className="bc-bar" />
              )}
            </g>
          );
        })}
        <line x1={0} y1={base} x2={W} y2={base} className="bc-rule" />
        <text x={peakIndex * slot + slot / 2} y={base - plotH - 4} textAnchor="middle" className="bc-clip">{peak}</text>
        {ticks.map((i) => (
          <text key={i} x={i * slot + slot / 2} y={H - 4} className="bc-clip"
                textAnchor={i === 0 ? 'start' : i === days.length - 1 ? 'end' : 'middle'}>
            {days[i].date.slice(5)}
          </text>
        ))}
      </svg>
    </figure>
  );
}

/** 항목이 몇 개뿐이라 표 안의 막대로 둔다 — 표가 곧 표 보기다 */
function CountTable({ title, counts, label, empty }) {
  const rows = Object.entries(counts || {}).map(([key, count]) => ({ key, count }));
  const peak = peakOf(rows);
  return (
    <div className="stat-table">
      <h3>{title}</h3>
      {rows.length === 0 ? <p className="muted small">{empty}</p> : (
        <table className="admin-table">
          <tbody>
            {rows.map((r) => (
              <tr key={r.key}>
                <td className="name">{label[r.key] || r.key}</td>
                <td className="stat-bar-cell">
                  <span className="stat-bar" style={{ width: `${(r.count / peak) * 100}%` }} />
                </td>
                <td className="num">{r.count}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

/** 시나리오 생성 통계 (제안서 기능 8). **지금 남아 있는 것만** 센다 — 지운 시나리오의 기록은 없다 */
export default function AdminStats() {
  const [stats, setStats] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    api.adminStats().then(setStats).catch((e) => setError(e.message));
  }, []);

  if (error) return <p className="error">{error}</p>;
  if (!stats) return <p className="muted small">불러오는 중입니다</p>;

  return (
    <section>
      <h2>시나리오 생성 통계</h2>
      <p className="muted small">지금 남아 있는 회원·시나리오만 셉니다 — 지운 시나리오는 기록이 없어 세지 못합니다.</p>
      <div className="stat-tiles">
        <div><b>{stats.users}</b><span>회원</span></div>
        <div><b>{stats.scenarios}</b><span>시나리오</span></div>
        <div><b>{stats.calculated}</b><span>{calculatedLine(stats)}</span></div>
      </div>
      <DailyChart days={stats.daily} />
      <div className="stat-tables">
        <CountTable title="추천 수단" counts={stats.byMode} label={MODE_LABEL} empty="추천된 수단이 없습니다" />
        <CountTable title="가입 수단" counts={stats.byProvider} label={PROVIDER_LABEL} empty="회원이 없습니다" />
      </div>
    </section>
  );
}
