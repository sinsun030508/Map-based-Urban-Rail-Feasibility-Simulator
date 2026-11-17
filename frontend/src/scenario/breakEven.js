/**
 * 추천안이 **B/C 1.0 미만**인가 — 편익이 비용을 못 덮는 안이다(11-17).
 *
 * 추천은 「이 중 1등」 이지 「지을 만하다」 가 아니다. 백엔드 추천(`CostCalculator.recommended`)은 B/C 가 **0 보다 크기만 하면** 1등을
 * 내놓는다 — 0.3 짜리도 「추천 수단」 으로 뜬다. 추천 규칙은 그대로 두고(네 곳이 같은 규칙이어야 한다 — `gainsNothing.js`),
 * 화면이 그 사실을 말해 준다.
 *
 * - **0 은 여기서 다루지 않는다** — 「절감 시간 없음」 이고 문구가 따로 있다(`gainsNothing`).
 * - **정확히 1.0 은 본전**이라 경고하지 않는다.
 * - **모르는 값(null)은 1 미만이 아니다** — 수요를 못 구했으면 B/C 가 비어 오고, 그건 「모름」 이다.
 */
export const BREAK_EVEN = 1;

export function belowBreakEven(row) {
  if (row?.bcRatio == null) return false;
  const bc = Number(row.bcRatio);
  return bc > 0 && bc < BREAK_EVEN;
}
