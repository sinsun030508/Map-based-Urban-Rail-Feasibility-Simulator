# 실제 대중교통 경로 비교 (하단바) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 그린 노선 양 끝의 실제 대중교통 경로(TMAP)를 지도 아래 카드로 보여 신설안과 견주게 한다.

**Architecture:** 백엔드 `com.railfeas.transit` 이 TMAP 을 대신 부르고(키 숨김·하루 상한·12시간 캐시) 화면이 쓰는 칸만 돌려준다. 프론트는 표기 규칙을 `scenario/transit.js` 순수 함수에 두고 `TransitBar.jsx` 가 지도 위에 겹쳐 그린다.

**Tech Stack:** Spring Boot 3.5(JDK `HttpClient`·Jackson·`StringRedisTemplate`), React, `node --test`.

**Spec:** `docs/superpowers/specs/2026-11-28-transit-compare-design.md`

## Global Constraints

- TMAP `POST https://apis.openapi.sk.com/transit/routes`, 헤더 `appKey`, `count` 3, 타임아웃 8초, 키는 로그·응답에 남기지 않는다
- `TMAP_APP_KEY` 가 비면 기능만 꺼진다(`DISABLED`). `TMAP_DAILY_LIMIT` 기본 10, 날짜는 `Asia/Seoul`, 캐시 적중은 안 센다, **Redis 가 죽으면 부르지 않는다**
- 캐시 12시간, 키는 좌표 소수 5자리 + 기준 시각. `OK`·`NO_ROUTE` 만 담는다
- B/C·`CalculationRules.CHANGED_AT` 을 바꾸지 않는다. 경로를 DB 에 저장하지 않는다. 「몇 분 빠름」 을 적지 않는다
- 시험 자료는 손으로 만든다(실제 응답을 저장소에 넣지 않는다). 테스트는 실제 API 를 부르지 않는다
- **커밋·푸시는 하지 않는다**(사용자가 요청할 때만)

## Review Focus

1. 요청이 가는 사이 점이 바뀜 → 옛 경로를 버린다 (Task 3 `isCurrent`)
2. 응답에 요금·`Lane`·`route`·`service` 가 없음 → 터지지 않고 비운다 (Task 1)
3. Redis 장애 → 호출 0번, `FAILED` (Task 2)
4. 「경로 없음」 응답·200 인데 모르는 모양 → `NO_ROUTE` / `FAILED` 를 가른다 (Task 2)
5. 실패 메시지·로그에 키가 섞임 → 안 섞인다 (Task 2)

---

### Task 0: `searchDttm` 적용 확인 (실제 호출 1건)

- [ ] 같은 좌표를 다음 평일 **03:00** 으로 한 번 부른다. Expected: 지하철 구간 `service` 가 0 이 되거나 경로가 달라지면 「적용됨」. 11-28 의 08:00 요청 결과(2호선·3호선 `service` 1)와 견준다
- [ ] 결과를 설계 문서 「아직 확인 못 한 것」에 적는다. 적용 안 됨이면 설계대로 시각 문구·운행 표시를 뺀다(Task 1·3 의 `running`·`searchedFor` 표시를 숨긴다)

### Task 1: 응답 변환과 기준 시각

**Files:** Create `backend/src/main/java/com/railfeas/transit/{TransitDto,TmapParser,SearchTime}.java`, Test `backend/src/test/java/com/railfeas/transit/{TmapParserTest,SearchTimeTest}.java`

**Interfaces — Produces:**
- `TransitDto.Status { OK, DISABLED, LIMIT, NO_ROUTE, FAILED }`
- `TransitDto.Response(Status status, String searchedFor, List<Itinerary> itineraries)`
- `TransitDto.Itinerary(int totalSec, int walkSec, int transfers, Integer fare, boolean running, List<Leg> legs)`
- `TransitDto.Leg(String mode, int sec, String route, String color, String from, String to, boolean running)`
- `static List<Itinerary> TmapParser.parse(JsonNode root)` — 경로가 없으면 빈 목록
- `static LocalDateTime SearchTime.next(LocalDateTime now)` — `now` 뒤 첫 평일(월~금) 08:00

- [ ] **Step 1: 실패하는 테스트** — `parse`: ① 도보+버스+지하철 경로의 칸이 옮겨진다(`fare.regular.totalFare`) ② 대표 `service` 0 + `Lane` 에 1 → 그 노선 이름·색, `running` true ③ 대안까지 0 → 구간·경로 `running` false, 그 경로는 뒤로(나머지 순서 유지) ④ 4개 이상이면 3개 ⑤ `fare`·`Lane`·`route`·`service` 가 없어도 예외 없이 null/`running` true ⑥ `metaData` 없는 JSON → 빈 목록. `SearchTime`: 금 07:00→금 08:00, 금 08:00→월 08:00, 토 23:00→월 08:00
- [ ] **Step 2:** Run `mvnw -o -q test -Dtest='TmapParserTest,SearchTimeTest'` — Expected: 컴파일 실패(클래스 없음)
- [ ] **Step 3:** 구현
- [ ] **Step 4:** 같은 명령 — Expected: 통과

### Task 2: 호출·상한·캐시·API

**Files:** Create `transit/{TmapClient,HttpTmapClient,DailyQuota,RedisDailyQuota,TransitService,TransitController}.java`; Modify `application.yml`; Test `transit/TransitServiceTest.java`

**Interfaces:**
- Consumes: Task 1, `com.railfeas.calc.PlanStore`, `ScenarioDto.PointRequest`
- Produces: `TmapClient.Reply search(BigDecimal fromLat, BigDecimal fromLng, BigDecimal toLat, BigDecimal toLng, LocalDateTime at)` (`record Reply(int status, String body)`, 통신 실패는 예외); `DailyQuota.tryAcquire(LocalDate day, int limit)` → boolean(장애는 예외); `TransitService.search(from, to)` → `Response`, `boolean enabled()`; `GET /api/route/transit` → `{"enabled":bool}`, `POST /api/route/transit` `{from,to}` → `Response`
- `app.tmap.app-key: ${TMAP_APP_KEY:}`, `app.tmap.daily-limit: ${TMAP_DAILY_LIMIT:10}`

상태 규칙: 키 없음 `DISABLED` → 캐시 적중이면 그대로 → 상한 `LIMIT`(상한·Redis 예외 모두 호출 없음, 예외는 `FAILED`) → HTTP 429 `LIMIT` → 그 밖 비 2xx·예외·JSON 아님 `FAILED` → 경로 있으면 `OK` → `result.status` 가 있으면 `NO_ROUTE` → 아니면 `FAILED`

- [ ] **Step 1: 실패하는 테스트**(가짜 client·quota·store, 고정 `Clock`) — 위 상태 규칙 각 한 건, 캐시 적중 때 호출·상한 0번, `FAILED`·`LIMIT` 은 안 담김, 좌표 6자리째만 다르면 캐시 적중, client 예외 메시지에 키가 있어도 응답·로그에 없음
- [ ] **Step 2:** Run `mvnw -o -q test -Dtest=TransitServiceTest` — Expected: 컴파일 실패
- [ ] **Step 3:** 구현
- [ ] **Step 4:** 통과 확인 후 `mvnw -o package` — Expected: `Tests run:` 전체 통과, jar 시각 갱신

### Task 3: 화면 표기 규칙

**Files:** Create `frontend/src/scenario/transit.js`, `transit.test.js`; Modify `frontend/src/api/client.js`

**Interfaces — Produces:** `routeKey(points)`→string; `isCurrent(data, key)`; `minutes(sec)`→정수 분(반올림); `legName(leg)` (`WALK`→「도보」, 「간선:511」→「511」, 「수도권2호선」→「2호선」, 이름 없으면 수단 이름); `legColor(leg)` (`#RRGGBB`, 도보·모름은 회색); `legWeight(leg)`→`max(sec, 180)`; `itinerarySummary(it)`→ 「52분 (걷기 12분) · 환승 2 · 1,650원 · 걷기 빼면 40분」(요금 없으면 생략); `ownSummary(row)`→ `{name, text: '역~역 18분', note: '걷기·대기 제외'}` 또는 null; `statusMessage(status)`; `api.transitStatus()`, `api.transit(from, to)`

- [ ] **Step 1: 실패하는 테스트** — 위 예시 값 그대로, `ownSummary(undefined)`·`travelTimeMin` 없음 → null, 어떤 요약에도 「빠」 가 없음, `isCurrent(null,k)` false·키 다르면 false
- [ ] **Step 2:** Run `npm test` — Expected: `transit.js` 없음으로 실패
- [ ] **Step 3:** 구현
- [ ] **Step 4:** Run `npm test` — Expected: 전부 통과

### Task 4: 하단바와 설정·문서

**Files:** Create `frontend/src/scenario/TransitBar.jsx`; Modify `App.jsx`(지도 영역을 `.map-area` 로 감쌈), `styles.css`, `.env.example`, `docker-compose.deploy.yml`, `docs/deploy.md`, `CLAUDE.md`, `frontend/CLAUDE.md`, `README.md`(테스트 수); Test `frontend/src/scenario/TransitBar.test.js`

**Interfaces — Consumes:** Task 3. `<TransitBar points={points} row={shown} ready={results.length > 0} />`

- [ ] **Step 1: 실패하는 테스트**(소스 모양) — `App.jsx` 가 `TransitBar` 를 `.map-area` 안에 위 속성으로 둔다 / `TransitBar` 는 `api.transit(` 를 `useEffect` 안에서 부르지 않는다(누를 때만) / `isCurrent(` 로 거른다
- [ ] **Step 2:** `npm test` — Expected: 실패
- [ ] **Step 3:** 구현 — `ready`·키 있음·점 2개일 때만 버튼. 접기·닫기, 부르는 동안 버튼 잠금, 바는 지도 아래 28px 위(축척·출처 표시를 가리지 않게), 높이 최대 40%·안에서 스크롤
- [ ] **Step 4:** `npm test` 통과, `cd etl && python -m pytest -q` — Expected: 통과(테스트 수를 문서에 맞춘 뒤)

### Task 5: 실제 화면 확인

- [ ] 백엔드·프론트를 다시 켜고, 지도에서 노선을 그려 계산한 뒤 버튼을 눌러 카드가 뜨는지, 점을 바꾸면 비는지, 좁은 화면, 콘솔 오류를 본다. **실제 호출은 1~2건만.** 로그인은 로컬 DB 의 시험 계정을 쓴다
- [ ] 전체 diff 를 처음부터 읽어 스스로 검토한다(서브에이전트 없음 — 보고에 적는다)
