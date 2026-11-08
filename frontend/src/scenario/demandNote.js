/**
 * 이용객 표기 — 수요가 수단마다 다를 수 있다. 경전철은 회귀의 경전철 0/1 변수로, BRT 는 세종 BRT 실측에 맞춘
 * 배수로 그 행만 줄인다(etl/demand_model.py). 패널의 한 줄과 시나리오 비교는 **기준값(보정하지 않은 수단의 행)** 을
 * 적고, 보정한 수단이 그 몇 배인지를 따로 적는다.
 * 트램은 국내 운영 노선이 없어 보정하지 않았다 — 같다는 뜻이 아니라 모른다는 뜻이라 그렇게 적는다.
 */

const ADJUSTED = ['LIGHT_RAIL', 'BRT_HIGH', 'BRT_LOW'];
const known = (r) => r.estimatedRidership != null;

/** 이용객을 아는 행 중 보정하지 않은 수단의 첫 행. 그런 행이 없으면 아는 행이라도 */
export function baseRow(results) {
  return results.find((r) => known(r) && !ADJUSTED.includes(r.modeType)) || results.find(known) || null;
}

export function baseRiders(results) {
  return baseRow(results)?.estimatedRidership ?? null;
}

/** 그 수단들의 행이 기준값의 몇 배인가. DB 계수를 화면에 또 적지 않으려고 결과에서 잰다 */
function factorOf(results, modes) {
  const base = baseRow(results);
  const row = results.find((r) => known(r) && modes.includes(r.modeType));
  if (!base || !row || base === row || !base.estimatedRidership) return null;
  return row.estimatedRidership / base.estimatedRidership;
}

export const lightRailFactor = (results) => factorOf(results, ['LIGHT_RAIL']);
export const brtFactor = (results) => factorOf(results, ['BRT_HIGH', 'BRT_LOW']);
