/**
 * 역 이름표 겹침 피하기 — 중요한 이름표부터 놓고, 이미 놓인 것과 겹치는 이름표는 숨긴다.
 * 서울~부산은 역이 촘촘한 양 끝에서 이름이 겹쳐 하나도 읽을 수 없었다(11-08). 숨긴 역도 점은 남고
 * 마우스를 올리면 이름이 보이며, 확대해 자리가 생기면 다시 나타난다.
 */

/** 이만큼 안으로 붙으면 글자가 이어져 보여 겹친 것으로 본다(px) */
const GAP = 4;

const overlaps = (a, b) => a.x < b.x + b.w + GAP && b.x < a.x + a.w + GAP
  && a.y < b.y + b.h + GAP && b.y < a.y + a.h + GAP;

/**
 * @param items {id, x, y, w, h, priority}[] — 화면 좌표의 이름표 상자. priority 가 클수록 먼저 놓는다
 * @returns 보일 이름표 id 의 Set. 같은 우선순위는 id 가 작은(앞쪽) 것부터라 매번 같은 답이다
 */
export function visibleLabels(items) {
  const placed = [];
  const shown = new Set();
  [...items]
    .sort((a, b) => b.priority - a.priority || a.id - b.id)
    .forEach((item) => {
      if (placed.every((p) => !overlaps(p, item))) {
        placed.push(item);
        shown.add(item.id);
      }
    });
  return shown;
}
