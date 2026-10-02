# ERD — 실제 스키마

`docs/erd.png` 은 **제안서에 낸 그림**이고, 아래가 실제로 구현된 스키마다.
둘이 다르므로 보고서를 쓸 때는 이쪽을 보고 쓸 것.

## 제안서 ERD 와 달라진 점

**제안서는 시나리오 한 행에 수단 하나를 담았다.** 그러면 이 서비스의 핵심인
"수단×구조 전부를 계산해 나란히 비교하는 표"를 저장할 수 없다. 그래서 둘로 쪼갰다.

```
제안서   scenario (노선 + 수단 + 비용 + B/C)  ← 한 행에 다 들어 있음
실제     scenario (노선만) ─1:N─ scenario_result (수단×구조별 결과) ─1:N─ station
```

`station` 이 `scenario` 가 아니라 `scenario_result` 에 매달리는 이유도 같다.
**역간격이 수단마다 달라 정차역 배치가 수단마다 다르다** (같은 11km 노선이
BRT 9역 · 경전철 10역 · 트램 13역).

## 전체

```mermaid
erDiagram
    user ||--o{ scenario : "소유"
    scenario ||--o{ route_point : "노선 좌표"
    scenario ||--o{ scenario_result : "수단×구조별 결과"
    scenario_result ||--o{ station : "정차역"
    cost_standard ||--o{ scenario_result : "적용한 단가"

    user {
        bigint user_id PK
        varchar email UK
        varchar password "소셜 로그인은 NULL"
        enum provider "LOCAL/GOOGLE/KAKAO"
        enum role "USER/ADMIN"
    }
    scenario {
        bigint scenario_id PK
        bigint user_id FK
        varchar title
        decimal total_length_km "Haversine 합"
        enum region_class
        enum preferred_structure "지하/고가/지상"
        bigint population_1km "계산 시 집계"
        enum recommended_mode "B_C 최대안"
        datetime calculated_at "기준값이 뒤에 바뀌면 결과가 낡은 것"
    }
    route_point {
        bigint point_id PK
        bigint scenario_id FK
        int sequence
        decimal latitude
        decimal longitude
        enum point_type "출발/경유/도착"
    }
    scenario_result {
        bigint result_id PK
        bigint scenario_id FK
        bigint standard_id FK
        enum mode_type
        enum structure_type
        int station_count
        bigint total_cost "억원, 명목"
        int estimated_ridership "일 승차+하차"
        int peak_pphpd "첨두 단면"
        decimal travel_time_min
        bigint benefit_total "억원, 현재가치"
        decimal bc_ratio "편익PV ÷ 비용PV"
        boolean is_feasible "수송능력 초과만 FALSE"
        varchar warning "경고는 계산을 막지 않는다"
    }
    station {
        bigint station_id PK
        bigint result_id FK
        int sequence "기점부터 0"
        varchar name "집계구의 행정동명"
        decimal latitude
        decimal longitude
        int estimated_daily_users "역세권 인구 비율로 배분"
        boolean is_recommended "TRUE=자동 배치"
    }
    cost_standard {
        bigint standard_id PK
        enum mode_type
        enum structure_type
        bigint fixed_cost "억원"
        bigint cost_per_km
        bigint cost_per_station
        boolean is_active
    }
```

## 기준값 (서비스가 참조만 한다)

ETL·시드가 채우고 관리자만 고친다. 시나리오와 FK 로 묶이지 않는다
(`cost_standard` 만 결과에 어떤 단가를 썼는지 남기려고 연결돼 있다).

```mermaid
erDiagram
    reference_line {
        bigint line_id PK
        varchar line_name
        enum mode_type
        decimal length_km
        int station_count
        bigint total_cost "억원"
        bigint total_cost_2025 "물가 환산"
        decimal underground_ratio "전부 추정치"
        decimal cost_per_km "생성 컬럼"
        decimal avg_spacing_km "생성 컬럼 = 연장÷역수"
        char data_grade "생성 컬럼 A/B/C/D"
        boolean is_outlier "지우지 않고 제외만"
    }
    mode_capacity {
        enum mode_type PK
        int pphpd_min "수요 하한 — 실제 운영 노선 최저 단면. NULL 이면 경고 안 함"
        int pphpd_max "수송능력 상한 — 넘으면 탈락"
        decimal spacing_km
        decimal speed_kmh "표정속도"
        decimal max_length_km "권장 연장"
        datetime updated_at
    }
    benefit_parameter {
        bigint param_id PK
        varchar param_name UK
        decimal value
        varchar source "값보다 중요한 것"
        datetime updated_at
    }
    restricted_zone {
        bigint zone_id PK
        varchar name
        enum zone_type
        enum severity "BLOCKED/REVIEW"
        decimal center_lat "근사치"
        decimal radius_km "근사치"
    }
    price_index {
        smallint base_year PK
        decimal deflator "World Bank GDP 디플레이터"
    }
    type_mapping {
        bigint mapping_id PK
        varchar raw_text UK "원문 표기"
        enum mode_type
    }
```

## 스키마를 바꿀 때

`db/schema/*.sql` 이 유일한 출처다 (`ddl-auto=validate` 고정 — JPA 는 만들지 않는다).
바꾸면 **엔티티·DTO·리포지토리·`etl.py` 의 `SQL_COLUMNS`·이 문서**를 함께 고치고,
`docker compose down -v` 로 볼륨을 비워야 initdb 가 다시 돈다.
