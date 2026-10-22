/**
 * 화면에서 쓰는 숫자 표기 — **한 벌만 둔다.**
 *
 * `CostTable` 과 `ComparePage` 에 똑같은 `money`·`people` 이 각각 복사돼 있었다.
 * 한쪽만 고치면 같은 값이 두 화면에서 다르게 보인다 — 수단 이름을 `modeLabels.js`
 * 한 곳으로 모은 것과 같은 이유다.
 */

/** 억원 → 읽기 쉬운 단위. 1조가 넘으면 조 단위로 끊는다 */
export function money(eok) {
  if (eok == null) return '—';
  if (eok >= 10000) {
    const jo = Math.floor(eok / 10000);
    const rest = Math.round(eok % 10000);
    return rest ? `${jo}조 ${rest.toLocaleString()}억` : `${jo}조`;
  }
  return `${Math.round(eok).toLocaleString()}억`;
}

/** 사람 수 — 만 단위가 넘으면 소수 첫째 자리까지 */
export function people(n) {
  if (n == null) return '—';
  return n >= 10000 ? `${(n / 10000).toFixed(1)}만` : n.toLocaleString();
}

/**
 * B/C — 소수 둘째 자리. 단 **0 과 작은 양수를 섞지 않는다.**
 * 0.00 은 "전환 전 수단보다 느려 아끼는 시간이 없다" 는 뜻으로 쓰는데(흐리게 찍는다),
 * 광명-화성 복선전철 지하 0.002 같은 작은 양수도 0.00 으로 찍혀 둘을 구별할 수 없었다.
 */
export function bc(value) {
  if (value == null) return '—';
  const v = Number(value);
  if (v > 0 && v < 0.005) return '<0.01';
  return v.toFixed(2);
}
