/**
 * 산·물 안내가 따를 구조 — **지도에 그린 행(`shown`)이 있으면 그 행의 구조**, 없으면(계산 전) 구조 선택창의 값이다(11-15).
 * 선택창(지하)으로만 물었더니 지하철 **고가** 행을 골라도 물 안내가 「물 아래 구간 · 하저터널」 로 적히고 호수 우회 제안이 지하 기준(없음)이었다.
 * 산 안내는 11-12 에 이미 이 규칙이다(`App.jsx` 의 산 구간) — 한곳에 둬 둘이 갈라지지 않게 한다.
 */
export function drawnStructure(shown, selected) {
  return shown?.structureType || selected;
}
