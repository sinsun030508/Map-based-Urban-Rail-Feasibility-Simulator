/**
 * 수단마다 따로 온 경고를 **한 줄로 합친다.**
 *
 * 백엔드는 결과 행마다 경고를 만들고 조각을 ` / ` 로 잇는다. 화면은 전에 **문장 전체**로
 * 중복을 걸렀는데, 조각 안에 숫자가 들어 있어 거의 같은 말이 그대로 쌓였다 —
 * 서울~부산에서 실제로 이렇게 나왔다.
 *
 *   ⚠ 97곳은 … 역을 놓지 않았습니다 / 권장 연장 초과 — 복선전철 대안 참고 / 과잉 투자 …
 *   ⚠ 97곳은 … 역을 놓지 않았습니다 / 권장 연장 초과 — 복선전철 대안 참고 / 과잉 투자 …
 *   ⚠ 124곳은 … 역을 놓지 않았습니다 / 권장 연장 초과 — 복선전철 대안 참고
 *   … 여섯 줄
 *
 * 그래서 **조각 단위로** 거르고, 숫자만 다른 조각은 **범위로 묶는다.**
 *
 *   ⚠ 14~124곳은 … 역을 놓지 않았습니다
 *   ⚠ 권장 연장 초과 — 복선전철 대안 참고
 *   ⚠ 과잉 투자 검토 — 실제 운영 노선 최저 수준(4,700~20,000명/시)보다 낮다
 *
 * **값을 지어내지 않는다.** 범위의 양 끝은 실제로 온 값이고, 그 수단의 정확한 값은
 * 표의 행에 마우스를 올리면(`title`) 그대로 나온다. 합치는 것은 표시뿐이다.
 */

/** `97`, `20,000`, `500` — 천 단위 쉼표를 한 덩어리로 본다 */
const NUMBER = /\d[\d,]*/g;

const value = (s) => Number(s.replace(/,/g, ''));

/** 한 자리에 모인 숫자들을 `최솟값~최댓값` 으로. 하나뿐이면 그대로 둔다 */
function span(seen) {
  if (seen.size === 1) return [...seen][0];
  const sorted = [...seen].sort((a, b) => value(a) - value(b));
  return `${sorted[0]}~${sorted[sorted.length - 1]}`;
}

/**
 * @param {string[]} warnings 결과 행마다의 경고 문자열(비어 있을 수 있다)
 * @returns {string[]} 합쳐진 조각들. **처음 나온 순서**를 지킨다 —
 *                     정렬하면 경고의 경중이 아니라 글자 순으로 섞인다
 */
export function mergeWarnings(warnings) {
  // 숫자를 뺀 모양 → 그 자리에 나온 숫자들
  const groups = new Map();
  for (const warning of warnings) {
    if (!warning) continue;
    for (const raw of warning.split(' / ')) {
      const clause = raw.trim();
      if (!clause) continue;
      // 모양이 같으면 숫자 개수도 같다 — 같은 틀에서 만든 문장이기 때문이다
      const parts = clause.split(NUMBER);
      const numbers = clause.match(NUMBER) || [];
      const shape = parts.join('#');
      const group = groups.get(shape) || { parts, slots: numbers.map(() => new Set()) };
      numbers.forEach((n, i) => group.slots[i].add(n));
      groups.set(shape, group);
    }
  }

  return [...groups.values()].map(({ parts, slots }) =>
    parts.reduce((text, part, i) => (i === 0 ? part : text + span(slots[i - 1]) + part), ''));
}
