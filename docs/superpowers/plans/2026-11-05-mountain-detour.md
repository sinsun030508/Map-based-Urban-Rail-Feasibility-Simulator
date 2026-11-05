# 산 구간 — 돌아가는 안과 뚫는 안 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 노선이 35‰ 보다 가파른 지형을 지나면 그 구간을 찾아, 돌아가는 안과 뚫는 안의 연장·건설비·소요시간을 나란히 보여 준다.

**Architecture:** ETL 이 AWS Terrain Tiles(z11)를 `data/cache/dem/` 에 받는다. 백엔드 `Terrain` 이 타일을 읽고, 순수 함수 `MountainProfile`(뚫는 안 — 선로 하한 포락선)과 `MountainDetour`(돌아가는 안 — 기울기 제약 A*)가 계산하며, `/api/route/mountain` 이 결과와 수단·구조별 비용 차이를 준다. 화면은 호수 안내와 같은 틀로 패널 한 칸과 지도 갈색 토막을 그린다.

**Tech Stack:** Python 3.12(ETL, Pillow 로 PNG 해석) · Spring Boot 3.3 / Java 17 · React(Vite) · MapLibre

**Spec:** `docs/superpowers/specs/2026-11-05-mountain-detour-design.md`

## Global Constraints

- 최대 기울기 `GRADE = 0.035`(35‰) — 법령(도시철도건설규칙 제15조, 철도의 건설기준에 관한 규정 제10조). 하나만 쓴다
- 덮개 두께 `COVER_M = 20`(가정값) — 이보다 얕으면 터널로 세지 않는다
- 우회 상한 `MAX_RATIO = 1.5`(가정값, 호수와 같다) · 경유점 50개 상한(호수와 같다)
- 표고 표본 간격 `STEP_KM = 0.1` · 지형 줌 `z = 11` · 해발 음수(바다 수심)는 **0 으로 자른다**
- **BRT 2종은 다루지 않는다** — 비용 차이 목록에서 뺀다
- **지하(`UNDERGROUND`) 구조에는 안내하지 않는다**(호수와 같다)
- **지형 자료가 없으면 기능이 꺼진다** — 오류 없이 `available: false`. CI 에는 캐시가 없다
- 터널비는 `T × (지하 km단가 − 그 구조 km단가)` — **지하 단가가 없는 수단(트램)은 null**(추정하지 않는다)
- 화면 문구 — 「인구 0」 과 「모름」 처럼, **「산 없음」 과 「지형 자료 없음」 을 섞지 않는다**
- 출처 표기 — 지도 귀속에 「지형 © Mapzen·SRTM(AWS Terrain Tiles)」

## Review Focus

1. **바다를 건너는 노선**(부산만·서해) — 수심이 음수라 선로가 바다 밑으로 꺼지면 해안 절벽이 터널로 잡힌다 → 0 으로 자르고 터널 없음. Task 2 테스트
2. **지형 타일이 없는 칸**(받지 않은 곳·바다 끝) — 0 으로 읽으면 「평지」 로 틀린다 → 그 표본은 「모름」, 모르는 칸이 낀 구간은 판정하지 않는다. Task 1·2 테스트
3. **산맥을 통째로 막는 능선**(소백산맥) — 돌아갈 길이 없거나 1.5배를 넘는다 → 뚫는 안만, 이유를 적는다. Task 3 테스트
4. **평지·완만한 언덕(30‰)** — 안내가 없어야 한다(지금과 같다). Task 2 테스트
5. **골짜기** — 선로가 지면 위로 뜨면(교량) 지금 모델에 교량 할증이 없어 맞지 않는다 → 포락선이 지면 아래만 지난다. Task 2 테스트

---

### Task 1: 지형 타일 내려받기와 해석 (ETL)

**Files:**
- Create: `etl/dem_tiles.py` · `etl/test_dem_tiles.py`
- Modify: `etl/requirements.txt`(`pillow>=10`), `README.md`(데이터 재생성 절에 한 줄)
- Create: `backend/src/test/resources/dem/11/{x}/{y}.png` — **실제 산이 있는 타일 2장**(추풍령 부근 z11)을 받은 것에서 복사

**Interfaces:**
- Produces: 타일 경로 규칙 `data/cache/dem/11/{x}/{y}.png`, 해석식 `elev = R·256 + G + B/256 − 32768`(Terrarium)

- [ ] **Step 1: 실패하는 테스트** — `test_tile_of_seoul`: `tile_of(37.5665, 126.9780, 11) == (1746, 793)`; `test_korea_tiles_count`: `len(korea_tiles(11)) == 1312`; `test_decode_terrarium`: `decode(128, 0, 0) == 0.0`, `decode(129, 0, 0) == 256.0`, `decode(127, 255, 128) == -0.5`
- [ ] **Step 2:** `cd etl && python -m pytest test_dem_tiles.py -q` → FAIL(모듈 없음)
- [ ] **Step 3: 구현** — `tile_of(lat, lng, z) -> tuple[int, int]`, `korea_tiles(z) -> list[tuple[int,int]]`(위도 33.0~38.7, 경도 124.5~130.0), `decode(r, g, b) -> float`, `main()` 이 없는 타일만 받는다(칸마다 캐시, 실패는 건너뛰고 끝에 개수 출력, 요청 사이 짧게 쉰다)
- [ ] **Step 4:** 테스트 PASS. `python etl/dem_tiles.py` 로 실제 다운로드 — 1,312장 근처, 약 80MB 확인
- [ ] **Step 5:** 테스트용 타일 2장 복사 · 커밋(`data/cache` 는 Git 제외 — 타일 자체는 올리지 않는다)

### Task 2: 표고 읽기와 뚫는 안 (백엔드)

**Files:**
- Create: `backend/src/main/java/com/railfeas/calc/Terrain.java` · `MountainProfile.java`
- Test: `backend/src/test/java/com/railfeas/calc/TerrainTest.java` · `MountainProfileTest.java`
- Modify: `backend/src/main/resources/application.yml`(`app.dem-dir: ${DEM_DIR:../data/cache/dem}`)

**Interfaces:**
- Produces:
  - `interface Elevation { OptionalDouble at(double lat, double lng); }`
  - `@Component Terrain implements Elevation` — `boolean available()`(폴더에 타일이 있나), 타일 PNG 를 LRU(256장)로 읽고 쌍선형 보간, **음수는 0**, 타일이 없으면 `OptionalDouble.empty()`
  - `MountainProfile.sections(List<double[]> route, Elevation e) -> List<Section>`
  - `record Section(double startKm, double endKm, double tunnelKm, double maxCoverM, List<double[]> pieces)` — `pieces` 는 지도에 그릴 터널 토막 좌표
  - 상수 `GRADE = 0.035`, `COVER_M = 20`, `STEP_KM = 0.1`

- [ ] **Step 1: 실패하는 테스트**
  - `TerrainTest.읽는다` — 테스트 타일 폴더로 `at(추풍령 부근 좌표)` 가 100m 이상, 타일 없는 좌표는 `isEmpty()`, 폴더가 없으면 `available() == false`
  - `MountainProfileTest`(가짜 `Elevation` 람다)
    - `평지면_구간이_없다` — 높이 50m 일정 → `isEmpty()`
    - `완만한_언덕은_구간이_없다` — 30‰ 오르막 → `isEmpty()`
    - `원뿔_산은_터널이_된다` — 10km 노선 가운데 높이 400m·밑지름 4km 산 → 구간 1개, `tunnelKm` 이 2~4km, `maxCoverM > 100`
    - `골짜기는_터널이_아니다` — 가운데가 깊은 골짜기 → `isEmpty()`(선로가 지면 위로 뜨지 않으므로 교량도 없다)
    - `바다는_터널이_아니다` — 가운데 수심 −80m(=0 으로 잘림) 양쪽 해안 10m → `isEmpty()`
    - `모르는_표본이_낀_곳은_판정하지_않는다` — 가운데 1km 가 `empty()` 인 산 → 구간 없음
- [ ] **Step 2:** `bash ./mvnw -o -q test -Dtest='TerrainTest,MountainProfileTest'` → 컴파일 실패
- [ ] **Step 3: 구현** — 선로 높이는 하한 포락선:
  ```
  rail[i] = min_j ( ground[j] + GRADE·|s_i − s_j| )   // 앞으로 한 번, 뒤로 한 번 훑어 O(n)
  tunnel = ground − rail > COVER_M 인 표본이 이어진 토막
  ```
  모르는 표본이 있으면 그 표본과 양옆 연속 구간을 판정하지 않는다
- [ ] **Step 4:** 테스트 PASS · 전체 `mvn test` PASS(새 보고서만 센다)
- [ ] **Step 5:** 커밋

### Task 3: 돌아가는 안 (백엔드)

**Files:**
- Create: `backend/src/main/java/com/railfeas/calc/MountainDetour.java`
- Test: `backend/src/test/java/com/railfeas/calc/MountainDetourTest.java`

**Interfaces:**
- Consumes: `Elevation`, `MountainProfile.sections`, `Section`
- Produces:
  - `MountainDetour.plan(List<double[]> route, Elevation e) -> Plan`
  - `record Plan(List<double[]> points, List<Mountain> mountains)`
  - `record Mountain(int index, double tunnelKm, boolean detoured, double addedKm, String reason, List<double[]> pieces)` — `index` 는 1부터(지도 토막·패널 「구간 1」)

- [ ] **Step 1: 실패하는 테스트**
  - `원뿔_산은_돌아간다` — 원뿔 산 하나 → `detoured`, `addedKm > 0`, 새 선형 전체가 `MountainProfile.sections(...).isEmpty()`(돌아간 길에 다시 산이 없다), 길이 비율 ≤ 1.5
  - `산맥은_못_돌아간다` — 격자 전체를 가로지르는 능선 → `detoured == false`, `reason` 에 「돌아갈 길」, 점은 원래 그대로
  - `너무_멀면_제안하지_않는다` — 돌아가면 1.5배를 넘는 넓은 산 → `detoured == false`, `reason` 에 「배」
  - `산이_없으면_빈_목록` — 평지 → `mountains.isEmpty()`, 점 그대로
- [ ] **Step 2:** 실패 확인
- [ ] **Step 3: 구현** — `WaterDetour` 의 틀(100m 격자, 600칸 상한, 여백 1km, A*, 단순화)을 따르되 **이웃 칸 사이 기울기 > GRADE 면 못 간다**. 단순화는 잇는 선분을 100m 마다 재서 기울기를 넘지 않을 때만 곧게 잇는다. 구간 시작·끝 앞뒤 1km 를 붙여 격자를 잡는다
- [ ] **Step 4:** 테스트 PASS · 전체 PASS
- [ ] **Step 5:** 커밋

### Task 4: API 와 비용 차이

**Files:**
- Create: `backend/src/main/java/com/railfeas/calc/MountainCost.java`
- Modify: `backend/src/main/java/com/railfeas/route/RouteController.java`
- Test: `backend/src/test/java/com/railfeas/calc/MountainCostTest.java`, `route/RouteControllerTest.java`

**Interfaces:**
- Consumes: `Terrain`, `MountainDetour.plan`, `CostStandardRepository.findByActiveTrue()`, `ModeCapacity`(속도)
- Produces:
  - `POST /api/route/mountain` 요청은 `DetourRequest` 그대로, 응답 `MountainResponse(boolean available, List<Point> points, List<MountainDetour.Mountain> mountains, List<MountainCost.Delta> deltas)`
  - `record Delta(ModeType mode, StructureType structure, Long tunnelExtraCost, Long detourExtraCost, Double detourExtraMin)` — 억원·분. 터널비 `T × (지하 km단가 − 그 구조 km단가)`, 지하 단가가 없으면 `null`. 우회비 `ΔL × 그 구조 km단가`, 시간 `ΔL ÷ 표정속도 × 60`
  - `MountainCost.deltas(double tunnelKm, double addedKm, List<CostStandard>, Map<ModeType, ModeCapacity>) -> List<Delta>` — **BRT 2종 제외**, 지하 구조 행 제외

- [ ] **Step 1: 실패하는 테스트**
  - `MountainCostTest.터널비는_지하와의_단가차` — 지하 km단가 1,000·고가 400 → 터널 2km 의 고가 행 `tunnelExtraCost == 1200`
  - `트램은_터널비를_모른다` — 트램 지상만 있음 → `tunnelExtraCost == null`, 우회비는 값이 있다
  - `BRT_는_빠진다` · `지하_행은_빠진다`
  - `RouteControllerTest.지형이_없으면_꺼진다` — `available()==false` → `available false`, 목록 비어 있음, 오류 없음
  - `RouteControllerTest.지하면_안내하지_않는다` — `UNDERGROUND` → `mountains` 비어 있음
- [ ] **Step 2:** 실패 확인
- [ ] **Step 3: 구현** — 위 Interfaces 대로. 비용 차이는 **가장 큰 산 하나가 아니라 구간 합**(터널 km 합·우회 km 합)으로 계산한다
- [ ] **Step 4:** 테스트 PASS · 전체 PASS · jar 빌드 후 실제 서버에서 서울~부산을 `curl` 로 불러 구간·터널 km 확인
- [ ] **Step 5:** 커밋

### Task 5: 화면

**Files:**
- Modify: `frontend/src/api/client.js`(`mountain(points, structure)`), `frontend/src/App.jsx`(호수와 같은 0.6초 지연·`key` 패턴 — **훅은 이른 return 위**), `frontend/src/map/MapView.jsx`(터널 토막 갈색 `#8b5a2b` 실선, 돌아가는 안 점선 미리보기), `frontend/src/scenario/ScenarioPanel.jsx`, `frontend/src/styles.css`, `frontend/src/map/mapStyle.js`(귀속 문구), `frontend/CLAUDE.md`
- Create: `frontend/src/scenario/mountainNote.js` · `mountainNote.test.js`

**Interfaces:**
- Consumes: `MountainResponse`, `Delta`, 비교표의 고른 행(`selectedResult`, 없으면 추천 행)
- Produces: `mountainLines(response, selectedRow) -> { lengths: string, cost: string|null }` — 화면 문구를 만드는 순수 함수

- [ ] **Step 1: 실패하는 테스트**(`node --test`)
  - 계산 전(`selectedRow == null`) → `cost == null`, `lengths` 에 「터널 3.2km」·「돌아가면 +5.1km」
  - 고른 행이 트램이면 터널비 자리에 「지하 단가가 없어 터널비를 모릅니다」
  - 고른 행이 BRT 면 「BRT 는 도로 기준이라 산 판정을 하지 않습니다」
  - `available == false` → 「지형 자료가 없어 산 구간을 보지 않았습니다」(「산 없음」 과 다르다)
- [ ] **Step 2:** 실패 확인
- [ ] **Step 3: 구현** — 패널 칸은 호수 안내(`.water-note`) 아래, 같은 차분한 색. 「돌아가는 선형으로 바꾸기」 는 `points` 를 응답 점으로 바꾼다(호수와 같다)
- [ ] **Step 4:** `npm test` PASS · **화면 확인**(헤드리스 촬영 스크립트) — 서울~부산 고가로 갈색 토막·패널 문구·바꾸기 버튼, 지하로 바꾸면 안내가 사라짐, 강남~잠실 같은 평지는 안내 없음
- [ ] **Step 5:** 커밋

### Task 6: 문서와 마무리

**Files:** `CLAUDE.md`(지켜야 할 규칙에 한 줄 — 35‰·덮개 20m·1.5배·지하·BRT 제외·외삽), `docs/engineering-notes.md`(근거와 함정), `README.md`(테스트 수·지형 자료), 설계 문서 상태를 「구현됨」 으로

- [ ] **Step 1:** 문서 갱신, `python -m pytest`(문서 일치) PASS
- [ ] **Step 2:** 세 묶음 전체 테스트 PASS · 깨끗한 복제본에서 백엔드 테스트(캐시 없이 테스트 타일만으로) PASS
- [ ] **Step 3:** 커밋·푸시 · GitHub Actions 결과 확인
