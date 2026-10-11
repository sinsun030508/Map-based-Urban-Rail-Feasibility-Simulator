/**
 * 수단·구조 이름을 화면에 쓰는 말로 바꾼다.
 *
 * **전에는 이 표가 네 파일에 복사돼 있었다** (`CostTable`·`BcChart`·`ComparePage`·
 * `AdminPage`). 수단을 하나 더하려면 네 군데를 고쳐야 했고, 한 군데를 빼먹으면
 * 그 화면만 `EXPRESS_RAIL` 같은 코드를 그대로 보여 준다.
 *
 * **`mode_capacity.display_name` 과 일부러 다르다.** DB 에는 정식 명칭
 * `광역급행철도` 를 남기고(근거 기록) 화면에는 `광역급행` 을 쓴다 — 비교표의 수단
 * 칸이 **75px** 라 6자가 두 줄로 접힌다. 다른 수단은 전부 4자 이하라 한 줄이고,
 * 접힌 표를 읽기 어렵다는 것은 이 프로젝트가 이미 겪은 문제다
 * (`frontend/CLAUDE.md` 의 패널 폭 항목 — `복선전/철`, `20/분` 으로 접혔다).
 * 애초에 API 가 이름을 주지 않아 화면이 이름을 갖는다 — `scenario_result` 는
 * `modeType` 코드만 싣는다.
 */
export const MODE_LABEL = {
  HEAVY_METRO: '지하철',
  LIGHT_RAIL: '경전철',
  TRAM: '트램',
  BRT_HIGH: 'BRT 고급형',
  BRT_LOW: 'BRT 저급형',
  DOUBLE_ELEC: '복선전철',
  EXPRESS_RAIL: '광역급행',
  SINGLE_ELEC: '단선전철',
  UPGRADE: '기존선 개량',
};

/**
 * 더 짧은 이름 — `BcChart` 의 이름 칸이 **84px 고정**이라 긴 이름은 왼쪽으로 넘친다
 * (글자 10px, 오른쪽 정렬이라 잘리는 게 아니라 축 밖으로 삐져나간다).
 * `BRT 고급형 지상` 은 넘치고 `BRT 고급 지상` 은 들어간다.
 */
export const MODE_LABEL_SHORT = {
  ...MODE_LABEL,
  BRT_HIGH: 'BRT 고급',
  BRT_LOW: 'BRT 저급',
};

export const STRUCTURE_LABEL = { UNDERGROUND: '지하', ELEVATED: '고가', AT_GRADE: '지상' };

/** 모르는 코드는 코드 그대로 보여 준다 — 빈 칸보다 낫다 */
export const modeLabel = (type) => MODE_LABEL[type] || type;
export const modeLabelShort = (type) => MODE_LABEL_SHORT[type] || type;
