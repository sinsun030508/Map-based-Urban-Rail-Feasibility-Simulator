/**
 * 수요는 구했는데 **절감 시간이 없는 안** — B/C 0. 수요를 모르는(null) 안은 아니다.
 *
 * 내세우지 않는다. 기존 KTX 보다 느린 서울~부산 신설안은 전부 0 인데 복선전철이
 * 추천됐었다 — 아무 시간도 아끼지 못하는 안이다. 백엔드 추천(`CostCalculator.recommended`)·
 * 비교표의 굵은 행(`CostTable`)·지도 그림(`App.jsx` 의 `shown`)·시나리오 비교의
 * 추천안(`ComparePage` 의 `bestOf`) **네 곳이 같은 규칙**이어야 해서 한 곳에 둔다.
 */
export const gainsNothing = (r) => r.bcRatio != null && Number(r.bcRatio) <= 0;
