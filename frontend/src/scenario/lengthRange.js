/**
 * 그린 노선 길이가 그 수단이 쓰이는 **범위 밖**인가 — 위로는 권장 연장, 아래로는 역간격(11-22).
 *
 * 범위 밖인 수단은 **기본으로 내세우지 않는다**(지도가 그리는 안·표의 굵은 행·백엔드 추천 — 표에는 남긴다).
 * 아래쪽 경계의 까닭: 표정속도는 정차 사이를 역간격만큼 달릴 때의 값이라, 노선이 역간격보다 짧으면 그 속도가 나올 수 없다.
 * 광역급행(역간격 6.92km)이 신촌~홍대 1.2km 에서도 1위였다. 역간격은 운영 노선의 중앙값(`spacingKm`)이라 새 숫자를 만들지 않는다.
 *
 * **서버 `CostCalculator.withinLength` 와 같은 경계여야 한다** — 길이가 역간격과 정확히 같으면 통과한다.
 * 모르는 쪽(null)에는 그 방향의 경계가 없고, 수단 정보나 길이를 모르면(못 불러옴) 범위 밖으로 읽지 않는다.
 */
export function outsideLengthRange(mode, lengthKm) {
  if (!mode || lengthKm == null) return false;
  const { maxLengthKm, spacingKm } = mode;
  if (maxLengthKm != null && lengthKm > Number(maxLengthKm)) return true;
  if (spacingKm != null && lengthKm < Number(spacingKm)) return true;
  return false;
}

/**
 * 기본으로 내세울 행(지도가 그리는 안) — **서버 `CostCalculator.recommended` 와 같은 규칙**이다(11-22 코드 리뷰).
 * 이득이 없는 안(B/C 0)은 항상 뺀다. 남은 안 중 **범위 안**(`isOutside` 가 거짓)인 것의 B/C 최고안이고, **범위 안에 맞는 안이 하나도 없으면 범위를 무시한다** —
 * 짧은 노선(0.5km)은 모든 수단이 역간격보다 짧아 전부 범위 밖인데, 서버는 그때도 추천을 내므로 화면도 같은 안을 그려야 한다(안 그러면 지도가 비고 패널은 추천을 말한다).
 * B/C 를 모르는(null) 안은 맨 뒤다.
 */
export function defaultRow(results, isOutside, gainsNothing) {
  const usable = results.filter((r) => !gainsNothing(r));
  const within = usable.filter((r) => !isOutside(r));
  const pool = within.length ? within : usable;
  return [...pool].sort((a, b) => (b.bcRatio ?? -1) - (a.bcRatio ?? -1))[0];
}
