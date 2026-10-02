SET NAMES utf8mb4;   -- initdb 클라이언트가 latin1 로 읽어 한글이 깨지는 것 방지

-- =====================================================
-- RailFeas — V2 서비스 스키마 (회원·시나리오·계산 결과)
-- V1 은 마스터 데이터(reference_line, mode_capacity 등), V2 는 서비스 데이터.
-- 수단 컬럼은 V1 의 mode_type ENUM 과 값을 맞춘다 (docs/erd.png 의 rail_type 대체).
-- =====================================================

CREATE TABLE IF NOT EXISTS user (
    user_id      BIGINT       AUTO_INCREMENT PRIMARY KEY,
    email        VARCHAR(150) NOT NULL UNIQUE,
    password     VARCHAR(100)          COMMENT '소셜 로그인은 NULL',
    nickname     VARCHAR(50)  NOT NULL,
    provider     ENUM('LOCAL','GOOGLE','KAKAO') NOT NULL DEFAULT 'LOCAL',
    provider_uid VARCHAR(100)          COMMENT '소셜 제공자가 주는 고유 id',
    role         ENUM('USER','ADMIN')  NOT NULL DEFAULT 'USER',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_provider (provider, provider_uid)
) COMMENT='회원';


-- 계산 시점의 기준값을 고정해 두는 표 (기준이 바뀌어도 과거 결과가 흔들리지 않게)
CREATE TABLE IF NOT EXISTS cost_standard (
    standard_id      BIGINT      AUTO_INCREMENT PRIMARY KEY,
    mode_type        VARCHAR(20) NOT NULL,
    structure_type   ENUM('UNDERGROUND','ELEVATED','AT_GRADE') NOT NULL DEFAULT 'UNDERGROUND'
                     COMMENT '같은 수단도 고가면 지하의 절반 수준 — 수단만큼 큰 비용 변수',
    fixed_cost       BIGINT      NOT NULL DEFAULT 0
                     COMMENT '고정비 (억원) — 짧은 노선일수록 km당 단가가 튀어 상수항이 필요',
    cost_per_km      BIGINT      NOT NULL COMMENT '변동단가 (억원/km)',
    cost_per_station BIGINT      NOT NULL DEFAULT 0 COMMENT '역당 단가 (억원)',
    base_year        SMALLINT             COMMENT '불변가 기준연도',
    effective_from   DATE        NOT NULL DEFAULT (CURRENT_DATE),
    is_active        BOOLEAN     NOT NULL DEFAULT TRUE
                     COMMENT '관리자가 새 기준을 만들면 이전 행은 FALSE — 삭제하지 않는다',
    source           VARCHAR(150),

    INDEX idx_active (mode_type, structure_type, is_active)
) COMMENT='총사업비 = fixed_cost + 연장×cost_per_km + 역수×cost_per_station';


-- 편익 산정 원단위 (시간가치 등). 값은 예타 지침에서 채운다
CREATE TABLE IF NOT EXISTS benefit_parameter (
    param_id   BIGINT       AUTO_INCREMENT PRIMARY KEY,
    param_name VARCHAR(80)  NOT NULL UNIQUE
               COMMENT '예: time_value_won_per_hour, peak_hour_ratio, discount_rate',
    value      DECIMAL(14,4) NOT NULL,
    unit       VARCHAR(30),
    source     VARCHAR(150),
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT='편익 계산 원단위 — 관리자 CRUD 대상';


-- 사용자가 지도에 그린 노선 1건. 수단별 결과는 scenario_result 로 분리한다
CREATE TABLE IF NOT EXISTS scenario (
    scenario_id      BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user_id          BIGINT       NOT NULL,
    title            VARCHAR(100) NOT NULL,
    total_length_km  DECIMAL(8,3) NOT NULL COMMENT 'Haversine 합계',
    region_class     ENUM('CAPITAL','METRO_C','LOCAL')
                     COMMENT '노선 중심 기준 — 비용·수요 모델 설명변수',
    population_1km   BIGINT                COMMENT '노선 1km 버퍼 내 집계구 인구 합',
    recommended_mode VARCHAR(20)           COMMENT 'B/C 최대안. 계산 전에는 NULL',
    preferred_structure ENUM('UNDERGROUND','ELEVATED','AT_GRADE')
                     COMMENT '사용자 선택. 비우면 region_class 기본값 (수도권 지하 / 그 외 고가)',
    existing_line    VARCHAR(150)
                     COMMENT '기존 철도와 겹칠 때 안내한 노선명 (신설 대신 활용 검토)',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                     ON UPDATE CURRENT_TIMESTAMP,
    calculated_at    DATETIME     NULL
                     COMMENT '마지막 계산 시각 — 기준값이 그 뒤에 바뀌면 결과가 낡은 것이다',

    CONSTRAINT fk_scenario_user FOREIGN KEY (user_id)
        REFERENCES user (user_id) ON DELETE CASCADE,
    INDEX idx_user (user_id, created_at)
) COMMENT='노선 시나리오 — 좌표는 route_point, 결과는 scenario_result';


CREATE TABLE IF NOT EXISTS route_point (
    point_id    BIGINT        AUTO_INCREMENT PRIMARY KEY,
    scenario_id BIGINT        NOT NULL,
    sequence    INT           NOT NULL COMMENT '0부터, 그린 순서',
    latitude    DECIMAL(10,7) NOT NULL,
    longitude   DECIMAL(10,7) NOT NULL,
    point_type  ENUM('START','VIA','END') NOT NULL,

    CONSTRAINT fk_point_scenario FOREIGN KEY (scenario_id)
        REFERENCES scenario (scenario_id) ON DELETE CASCADE,
    UNIQUE KEY uk_point_seq (scenario_id, sequence)
) COMMENT='노선을 이루는 좌표점';


-- 같은 노선을 수단별로 계산한 결과. 시나리오 1건에 수단 수만큼 행이 생긴다
CREATE TABLE IF NOT EXISTS scenario_result (
    result_id          BIGINT      AUTO_INCREMENT PRIMARY KEY,
    scenario_id        BIGINT      NOT NULL,
    mode_type          VARCHAR(20) NOT NULL,
    structure_type     ENUM('UNDERGROUND','ELEVATED','AT_GRADE') NOT NULL,
    standard_id        BIGINT               COMMENT '계산에 쓴 cost_standard 고정',

    station_count      INT         NOT NULL,
    total_cost         BIGINT      NOT NULL COMMENT '억원',
    estimated_ridership INT                 COMMENT '일 이용객',
    peak_pphpd         INT                  COMMENT '첨두시 한 방향 최대 이용객',
    travel_time_min    DECIMAL(6,1)         COMMENT '끝에서 끝까지 소요시간 (표정속도 기준)',
    benefit_total      BIGINT               COMMENT '억원',
    bc_ratio           DECIMAL(6,3),

    -- 추천 판정
    is_feasible        BOOLEAN     NOT NULL DEFAULT TRUE
                       COMMENT '첨두 수요가 수송능력 상한을 넘으면 FALSE',
    warning            VARCHAR(200)
                       COMMENT '안내 문구. 계산을 막지 않는다 — 예: 권장 연장 초과,
                                수송능력 대비 과잉 투자, 규제구역 인접(확인 필요),
                                지하 선택 시 고가 대비 +N억',

    CONSTRAINT fk_result_scenario FOREIGN KEY (scenario_id)
        REFERENCES scenario (scenario_id) ON DELETE CASCADE,
    CONSTRAINT fk_result_standard FOREIGN KEY (standard_id)
        REFERENCES cost_standard (standard_id),
    UNIQUE KEY uk_result_mode (scenario_id, mode_type, structure_type),
    INDEX idx_bc (scenario_id, bc_ratio)
) COMMENT='수단별 비교표 한 줄 = 이 표 한 행';


-- 정차역은 수단마다 간격이 달라 결과별로 배치된다
CREATE TABLE IF NOT EXISTS station (
    station_id           BIGINT        AUTO_INCREMENT PRIMARY KEY,
    result_id            BIGINT        NOT NULL,
    sequence             INT           NOT NULL COMMENT '기점부터 0',
    name                 VARCHAR(100)           COMMENT '집계구가 속한 행정동 이름에서 생성',
    latitude             DECIMAL(10,7) NOT NULL,
    longitude            DECIMAL(10,7) NOT NULL,
    estimated_daily_users INT,
    is_recommended       BOOLEAN       NOT NULL DEFAULT TRUE
                         COMMENT 'TRUE=시스템 배치, FALSE=사용자가 직접 지정',

    CONSTRAINT fk_station_result FOREIGN KEY (result_id)
        REFERENCES scenario_result (result_id) ON DELETE CASCADE,
    UNIQUE KEY uk_station_seq (result_id, sequence)
) COMMENT='수단별 정차역 배치';


-- 지하 시공이 막히거나 심의를 받아야 하는 구역.
-- **경계가 아니라 중심점+반경의 근사값이다.** 통과 여부를 단정하지 말고 "확인 필요"로만 안내한다.
-- 정식 경계는 국가문화유산포털·물환경정보시스템·국토정보플랫폼에서 받아 교체할 것.
CREATE TABLE IF NOT EXISTS restricted_zone (
    zone_id     BIGINT        AUTO_INCREMENT PRIMARY KEY,
    zone_type   ENUM('CULTURAL','WATER_SOURCE','MILITARY','RAIL_BUFFER') NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    region      VARCHAR(50),
    center_lat  DECIMAL(10,7) NOT NULL,
    center_lng  DECIMAL(10,7) NOT NULL,
    radius_km   DECIMAL(6,2)  NOT NULL COMMENT '근사 반경',
    severity    ENUM('REVIEW','BLOCKED') NOT NULL DEFAULT 'REVIEW'
                COMMENT 'REVIEW=심의·협의 필요, BLOCKED=사실상 불가',
    note        VARCHAR(300),
    source      VARCHAR(150),

    INDEX idx_zone_type (zone_type)
) COMMENT='지하 노선 설계 시 확인이 필요한 구역 (근사 좌표)';
