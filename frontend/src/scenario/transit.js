/**
 * 실제 대중교통 경로(TMAP) 표기 규칙 — `TransitBar` 가 쓴다.
 *
 * **「몇 분 빠름」 은 적지 않는다.** 신설안의 소요시간은 역에서 역까지이고 실제 경로는 걷기가 든 문앞 시간이라, 빼면
 * 신설안이 유리한 틀린 숫자가 된다. 대신 실제 경로의 「걷기 빼면」 시간을 보조로 적는다(대기가 드는지는 모른다).
 */
import { MODE_LABEL, STRUCTURE_LABEL } from '../modeLabels.js';

const GREY = '#9ca3af';
/** 3분보다 짧은 구간도 글자가 읽히게 */
const MIN_WEIGHT_SEC = 180;

const MODE_NAME = {
  WALK: '도보', BUS: '버스', SUBWAY: '지하철', EXPRESSBUS: '고속·시외버스', TRAIN: '기차', AIRPLANE: '항공', FERRY: '해운',
};

const STATUS_MESSAGE = {
  OK: '',
  DISABLED: '서버에 대중교통 API 키가 없어 비교할 수 없습니다',
  LIMIT: '오늘 대중교통 경로 조회 한도를 다 썼습니다 — 내일 다시 시도해 주세요',
  NO_ROUTE: '두 지점 사이의 대중교통 경로를 찾지 못했습니다 (너무 가깝거나 서비스 지역 밖일 수 있습니다)',
  FAILED: '대중교통 경로를 불러오지 못했습니다 — 잠시 뒤에 다시 시도해 주세요',
};

/** 노선이 바뀌었는지 가르는 키 — 첫 점·끝 점만 묻지만 가운데가 바뀌어도 다른 노선이다 */
export const routeKey = (points) => JSON.stringify(points);

/** 받은 답이 지금 그린 노선의 것인가 */
export const isCurrent = (data, key) => Boolean(data) && data.key === key;

export const minutes = (sec) => Math.round(sec / 60);

export function legName(leg) {
  if (leg.mode !== 'WALK' && leg.route) {
    // 「간선:511」 → 「511」, 「수도권2호선」 → 「2호선」
    return leg.route.replace(/^[^:]*:/, '').replace(/^수도권(?=\d)/, '');
  }
  return MODE_NAME[leg.mode] || '이동';
}

export function legColor(leg) {
  return leg.mode !== 'WALK' && /^[0-9a-f]{6}$/i.test(leg.color || '') ? `#${leg.color}` : GREY;
}

export const legWeight = (leg) => Math.max(leg.sec, MIN_WEIGHT_SEC);

/** 상자 하나에 적는 셋 — 크게 쓰는 총시간, 그 아래 한 줄, 보조로 걷기를 뺀 시간 */
export function itinerarySummary(it) {
  return {
    total: `${minutes(it.totalSec)}분`,
    facts: [
      `걷기 ${minutes(it.walkSec)}분`,
      `환승 ${it.transfers}`,
      it.fare == null ? null : `${it.fare.toLocaleString('ko-KR')}원`,
    ].filter(Boolean).join(' · '),
    ride: `걷기 빼면 ${minutes(it.totalSec - it.walkSec)}분`,
  };
}

/** 지도에 그린 신설안 한 줄. 행이 없거나 소요시간을 모르면 null */
export function ownSummary(row) {
  if (!row || !row.travelTimeMin) return null;
  const name = `신설 ${MODE_LABEL[row.modeType] || row.modeType} ${STRUCTURE_LABEL[row.structureType] || ''}`.trim();
  return { name, total: `${Math.round(row.travelTimeMin)}분`, note: '역~역 · 걷기·대기 제외' };
}

/** 기준 시각은 운행 여부만 바꾼다(경로·소요시간은 거의 같다 — 11-28 실제 호출) */
export function searchedLabel(searchedFor) {
  const m = /^\d{4}-(\d{2})-(\d{2})T(\d{2}:\d{2})/.exec(searchedFor || '');
  return m ? `운행 여부는 평일 ${m[3]}(${Number(m[1])}월 ${Number(m[2])}일) 기준` : '';
}

export const statusMessage = (status) => STATUS_MESSAGE[status] ?? STATUS_MESSAGE.FAILED;
