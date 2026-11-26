import { routeThroughStations } from '../geo/routeLine.js';
import { followsRoad } from './mountainNote.js';

/**
 * 산·물·구역을 물을 선 — **지도에 지금 그려진 선**이다(11-26 사용자 요청). 점은 [경도, 위도].
 *
 * 철도 행은 정차역이 큰길 위로 옮겨지고(11-12) 지도는 그 역들을 이어 그린다(`routeThroughStations`). 찍은 직선으로 물었더니 갈색·파란 토막이
 * 빨간 선 밖 허공에 떴다. 서버도 같은 선으로 터널비·구역 경고를 잰다(`StationLine`) — 그래서 패널의 「뚫는 비용」 이 표 건설비와 같다.
 *
 * - 계산 전(행 없음)·역이 둘 미만이면 찍은 선 그대로
 * - 도로를 따라가는 행(트램·BRT·광역급행)은 null — 새로 놓는 구간만 따로 묻는다(`newPieces`)
 */
export function drawnLine(points, row) {
  if (!row) return points;
  if (followsRoad(row)) return null;
  return routeThroughStations(points, row.stations);
}
