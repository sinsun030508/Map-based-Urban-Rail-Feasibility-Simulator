/**
 * 지도 위에 띄우는 상세 창(`FloatingWindow.jsx`)의 위치 계산 — 화면을 그리지 않는 순수 함수라 `npm test` 로 잰다.
 *
 * 창은 **지도를 가리지 않는 것이 요점**이다. 표에서 수단 행을 누르면 지도가 그 수단의 정차역으로 바뀌는데,
 * 창이 지도를 덮고 있으면 바뀐 것을 볼 수 없다 — 그래서 처음 자리는 결과 패널 바로 왼쪽이고, 끌어서 옮긴다.
 */

/** 위쪽 막대(RailFeas 머리글)의 높이 — 창이 그 밑으로 올라가지 않게 한다 */
export const HEADER_H = 52;
/** 어디로 끌어도 제목줄이 이만큼은 화면에 남는다 — 다 나가면 되찾을 수 없다 */
export const MIN_VISIBLE = 80;
export const GAP = 12;
// 처음 크기는 작게 — 620 폭·화면 끝까지 높이였을 때 창이 지도를 반 넘게 덮었다(11-26 사용자 「표 화면이 너무 크다」). 더 보려면 모서리를 끌어 키운다
export const DEFAULT_W = 480;
export const MAX_H = 560;
export const MIN_W = 320;

/**
 * 처음 자리와 크기. 결과 패널 왼쪽 가장자리(`panelLeft`) 바로 옆에 붙인다.
 * 지도가 좁아 자리가 모자라면 폭을 줄이되 `MIN_W` 밑으로는 안 줄인다(그땐 지도를 가리지만 읽을 수는 있어야 한다).
 */
export function initialRect({ vw, vh, panelLeft }) {
  const room = panelLeft - 2 * GAP;
  const w = Math.min(DEFAULT_W, Math.max(MIN_W, room), Math.max(MIN_W, vw - 2 * GAP));
  const x = Math.max(GAP, panelLeft - GAP - w);
  const y = HEADER_H + GAP;
  const h = Math.min(MAX_H, Math.max(240, vh - y - GAP));
  return { x, y, w, h };
}

/**
 * 끌어서 옮긴 자리를 화면 안으로 되돌린다.
 * 가로는 제목줄이 `MIN_VISIBLE` 만큼 남게(창이 반쯤 나가는 것은 허용 — 지도를 더 보려는 것이다),
 * 세로는 머리글 밑 ~ 화면 아래 40px 위로 묶는다.
 */
export function clampPosition({ x, y }, { w }, { vw, vh }) {
  return {
    x: Math.min(Math.max(x, MIN_VISIBLE - w), vw - MIN_VISIBLE),
    y: Math.min(Math.max(y, HEADER_H), vh - 40),
  };
}
