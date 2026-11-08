/**
 * 이용객 표기 — 수요 회귀가 경전철만 따로 보정하므로(경전철 0/1 변수, etl/demand_model.py) 행마다 값이 다를 수 있다.
 * 패널의 한 줄과 시나리오 비교는 **기준값(경전철이 아닌 행)** 을 적고, 경전철 행이 그 몇 배인지를 따로 적는다.
 * 트램·BRT 는 국내 운영 자료가 없어 보정하지 않았다 — 같다는 뜻이 아니라 모른다는 뜻이라 그렇게 적는다.
 */

const known = (r) => r.estimatedRidership != null;

/** 이용객을 아는 행 중 경전철이 아닌 첫 행. 경전철뿐이면 그 행이라도 */
export function baseRow(results) {
  return results.find((r) => known(r) && r.modeType !== 'LIGHT_RAIL') || results.find(known) || null;
}

export function baseRiders(results) {
  return baseRow(results)?.estimatedRidership ?? null;
}

/** 경전철 행이 기준값의 몇 배인가. 둘 다 있어야 잰다(DB 계수를 화면에 또 적지 않으려고 결과에서 잰다) */
export function lightRailFactor(results) {
  const base = baseRow(results);
  const light = results.find((r) => known(r) && r.modeType === 'LIGHT_RAIL');
  if (!base || !light || base === light || !base.estimatedRidership) return null;
  return light.estimatedRidership / base.estimatedRidership;
}
