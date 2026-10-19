import BcChart from './BcChart';
import CostChart from './CostChart';
import { resultKey } from './resultKey';
import { STALE_MESSAGE } from './stale';
import { MODE_LABEL, STRUCTURE_LABEL } from '../modeLabels';
import { money, people } from '../format';
import { mergeWarnings } from './warnings';


export default function CostTable({
  results, structure, population, selected, stale, existingOption, pointCount,
  overLength, onSelect,
}) {
  if (!results.length) return null;

  const hasDemand = results.some((r) => r.bcRatio != null);
  // B/C 가 있으면 그 순, 없으면 싼 순 — 볼 것이 다르다
  const sorted = [...results].sort((a, b) =>
    hasDemand ? (b.bcRatio ?? -1) - (a.bcRatio ?? -1) : a.totalCost - b.totalCost
  );
  // **굵게 강조하는 안도 권장 연장을 따른다.** 서울~부산(340km)에서 340km 짜리
  // BRT 고급형이 B/C 최고라 굵게 찍혔는데, 지도는 광역급행을 그리고 있었다 —
  // 화면이 두 안을 동시에 가리킨 셈이다. 지도가 그리는 규칙(`App.jsx` 의 `shown`)과
  // 백엔드 추천(`CostCalculator.recommended`)이 쓰는 규칙과 같아야 한다.
  // 맞는 안이 하나도 없으면 맨 위를 쓴다 — 강조가 사라지는 것보다 낫다.
  const best = (overLength ? sorted.find((r) => !overLength(r)) : null) || sorted[0];
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
      {stale && <p className="error">{STALE_MESSAGE}</p>}
      <p className="muted small">
        행을 누르면 그 수단의 정차역이 지도에 표시됩니다.
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

      <BcChart results={sorted} selected={selected} onSelect={onSelect} />
      <CostChart results={sorted} existingOption={existingOption}
                 selected={selected} onSelect={onSelect} />

      <table className="cost">
        <thead>
          <tr>
            <th>수단</th><th>역</th><th>소요</th>
            <th>건설비</th><th>B/C</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((r) => (
            <tr
              key={resultKey(r)}
              className={[
                r === best ? 'cheapest' : '',
                resultKey(r) === selected ? 'picked' : '',
                r.structureType === structure ? 'selected' : '',
                r.feasible === false ? 'infeasible' : '',
              ].join(' ')}
              title={r.warning || ''}
              onClick={() => onSelect?.(resultKey(r))}
            >
              {/* 수단과 구조를 한 칸에 — 칸을 나누면 첫 칸만 읽혀 `지하철` 이 두 번 있는
                  것처럼 보인다. 막대 그래프·시나리오 비교와 같은 표기다 */}
              <td>{MODE_LABEL[r.modeType] || r.modeType} {STRUCTURE_LABEL[r.structureType]}</td>
              <td>{r.stationCount}</td>
              <td>{r.travelTimeMin ? `${Math.round(r.travelTimeMin)}분` : '—'}</td>
              <td className="num">{money(r.totalCost)}</td>
              <td className="num">
                {r.bcRatio == null ? '—' : Number(r.bcRatio).toFixed(2)}
                {r.feasible === false && <span className="tag">수송능력 초과</span>}
                {/* 0.00 은 계산 실패가 아니라 "전환 전 수단보다 느려 절감이 없다"는 결과다 */}
                {r.benefitTotal === 0 && (
                  <span className="tag note">기준 속도 이하 — 절감 시간 없음</span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
        {/* 이미 있는 수단은 **신설안과 같은 표 안에** 있어야 한다. 전에는 표 위에 경고
            문구로만 떠 있어서, 서울~부산을 그리면 324km 트램까지 후보로 늘어놓는데 정작
            "이미 KTX 가 있고 추가 건설비가 0" 이라는 가장 중요한 비교 대상이 표 밖에
            있었다. 비교는 표에서 일어난다.
            `tbody` 가 아니라 `tfoot` 인 것은 이 줄이 계산 결과가 아니기 때문이다 —
            정렬·'가장 싼 안' 강조·행 클릭(정차역 그리기)에 끼어들면 안 된다 */}
        {existingOption && (
          <tfoot>
            <tr className="existing">
              <td>{existingOption.label}</td>
              <td>—</td>
              {/* 좁은 칸이라 **빠른 계통의 중앙값 하나**만 넣는다. 열차마다 갈리는 폭은
                  아래 설명 줄이 받는다 — 한 칸에 네 숫자를 넣으면 아무도 안 읽는다 */}
              <td>{existingOption.travelMin ? `${existingOption.travelMin}분` : '—'}</td>
              <td className="num">0</td>
              <td className="num">—</td>
            </tr>
            <tr className="existing-note">
              <td colSpan={5}>
                {existingOption.detail}
                <br />
                이미 지어져 있어 <strong>추가 건설비가 0</strong>입니다.{' '}
                {/* 비운 칸은 "못 구했다" 가 아니라 "적으면 안 된다" 는 뜻이다 —
                    둘을 섞어 적지 않는다 */}
                {existingOption.travelDetail
                  ? <>소요시간은 <strong>빠른 계통의 중앙값</strong>입니다 — {existingOption.travelDetail}. </>
                  : existingOption.kind === 'INTERCITY_RAIL'
                    ? '그 구간의 시각표를 아직 받지 못해 소요시간을 비웠습니다. '
                    : '소요시간 자료를 아직 받지 못했습니다. '}
                이미 있는 시설의 B/C는 신설안과 범주가 달라 비웠습니다.
              </td>
            </tr>
          </tfoot>
        )}
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

      {/* 수단마다 온 경고를 조각 단위로 합친다 — 전에는 숫자만 다른 같은 말이 여섯 줄
          쌓였다(`warnings.js`). 정확한 값은 행의 `title` 에 그대로 있다 */}
      {mergeWarnings(results.map((r) => r.warning)).map((w) => (
        <p key={w} className="small warn" title="수단별 정확한 값은 표의 행에 마우스를 올리면 나옵니다">
          ⚠ {w}
        </p>
      ))}

      {/* 이 줄은 접지 않는다 — 읽고 **할 일이 있는** 안내다(경유점 추가) */}
      {pointCount === 2 && (
        <p className="small note">
          두 점만 찍어 <strong>직선으로 계산</strong>했습니다 — 지도를 눌러 경유점을
          더하면 실제 선형에 가까워집니다.
        </p>
      )}

      {/* **지우지 않고 접는다.** 근거와 한계를 화면에서 없애면 다음 사람이 이 숫자를
          공표값으로 읽는다. 대신 가장 중요한 한 줄을 `summary` 에 올려 접힌 채로도
          읽히게 한다 — 펴야 보이는 경고는 없는 경고다 */}
      <details className="small notes">
        <summary>
          <strong>절대값보다 순위를 보세요</strong> — 계산 근거와 한계
        </summary>
        <p>비용 모델 오차 1.25배, 수요 모델 오차 1.75배입니다.</p>
        {hasDemand && (
          <>
            <p>
              B/C는 투자평가지침 제7판 원단위(시간가치 7,231원/인·시, 할인율 4.5%·3.5%)로
              공사 5년과 개통 후 40년을 현재가치 환산한 값입니다. 표의 건설비는 명목
              총액이라 B/C와 직접 나눠지지 않습니다.
            </p>
            <p>
              첨두율과 전환 전 속도는 지침에 원단위가 없는 <strong>가정값</strong>이고,
              편익은 통행시간 절감만 셉니다.
            </p>
          </>
        )}
      </details>
    </section>
  );
}
