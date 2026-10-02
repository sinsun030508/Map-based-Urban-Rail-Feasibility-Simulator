/**
 * 비교표의 한 행을 가리키는 키 — 수단×구조가 한 행이다.
 *
 * `CostTable.jsx` 안에 두면 React Fast Refresh 가 "컴포넌트 파일이 컴포넌트만
 * 내보내야 한다"며 갱신을 포기하고 전체 리로드로 떨어진다(개발 중 상태가 날아간다).
 */
export const resultKey = (r) => `${r.modeType}-${r.structureType}`;
