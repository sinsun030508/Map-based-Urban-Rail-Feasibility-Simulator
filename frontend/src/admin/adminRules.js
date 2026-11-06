/**
 * 회원 관리 화면의 규칙 — 서버(`AdminUserController`)가 막는 것과 같아야 한다. 버튼이 있는데 눌렀을 때
 * 400 이 나면 "되는 줄 알았는데 안 된다" 가 되고, 막는 이유를 그 자리에 적을 수도 없다.
 */

/** @returns {{nextRole: string|null, canDelete: boolean, note: string|null}} */
export function userActions(row, meId) {
  if (row.id === meId) {
    return { nextRole: null, canDelete: false, note: '본인 계정은 다른 관리자가 관리합니다' };
  }
  if (row.role === 'ADMIN') {
    return { nextRole: 'USER', canDelete: false, note: '지우려면 일반 회원으로 내린 뒤에 지웁니다' };
  }
  return { nextRole: 'ADMIN', canDelete: true, note: null };
}

/** 지우면 되돌릴 수 없고 시나리오도 함께 사라진다 — 수를 말해야 무엇을 잃는지 안다 */
export function deleteMessage(row) {
  const lost = row.scenarioCount > 0 ? ` 시나리오 ${row.scenarioCount}개도 함께 지워집니다.` : '';
  return `${row.nickname} 회원을 지웁니다.${lost} 되돌릴 수 없습니다.`;
}

/** 계산했지만 내세울 안이 없는 시나리오(전부 B/C 0 등)가 수단별 합에서 조용히 사라지지 않게 */
export function calculatedLine({ calculated, noRecommendation }) {
  return noRecommendation > 0
    ? `계산 ${calculated}개 · 그중 추천 없음 ${noRecommendation}개`
    : `계산 ${calculated}개`;
}

export const peakOf = (rows) => rows.reduce((m, r) => Math.max(m, r.count), 0);

const isInt = (v) => v !== '' && v != null && Number.isInteger(Number(v));
/** 서버 `AdminCostController.MAX_COST` 와 같아야 한다 */
const MAX_COST = 1000000;

/**
 * 단가 입력 검사 — 서버(`AdminCostController`)가 막는 것과 같다. 지하·고가 대소는 다른 행을 봐야 해 서버가 본다.
 * @returns {string|null} 문구(저장 막음) · 빈 문자열(바뀐 것이 없어 잠그기만) · null(저장 가능)
 */
export function costDraftError(row, draft) {
  const d = { ...row, ...draft };
  if (['fixedCost', 'costPerKm', 'costPerStation'].some((k) => Number(d[k]) > MAX_COST)) {
    return `단가는 ${MAX_COST.toLocaleString()}억원을 넘을 수 없습니다`;
  }
  if (!isInt(d.fixedCost) || Number(d.fixedCost) < 0) return '고정비는 0 이상의 정수여야 합니다';
  if (!isInt(d.costPerKm) || Number(d.costPerKm) < 1) return 'km단가는 1 이상의 정수여야 합니다';
  if (!isInt(d.costPerStation) || Number(d.costPerStation) < 0) return '역 단가는 0 이상의 정수여야 합니다';
  if (!String(d.source ?? '').trim()) return '근거를 적어 주세요 — 값보다 중요한 것이 출처입니다';
  const same = ['fixedCost', 'costPerKm', 'costPerStation'].every((k) => Number(d[k]) === Number(row[k]))
    && d.source === row.source;
  return same ? '' : null;
}
