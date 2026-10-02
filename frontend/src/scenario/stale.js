/**
 * 저장된 계산 결과가 낡았는지 본다.
 *
 * 관리자가 원단위를 고쳐도 이미 계산해 둔 B/C 는 그대로 남는다. 그 값을 아무 말 없이
 * 보여 주면 근거가 바뀐 숫자를 그대로 인용하게 된다 — 이 프로젝트가 가장 피하려는 일이다.
 */
export function isStale(calculatedAt, referenceUpdatedAt) {
  if (!calculatedAt || !referenceUpdatedAt) return false;
  return new Date(referenceUpdatedAt) > new Date(calculatedAt);
}

export const STALE_MESSAGE =
  '기준값이 이 결과를 계산한 뒤에 바뀌었습니다 — 다시 계산해 주세요';
