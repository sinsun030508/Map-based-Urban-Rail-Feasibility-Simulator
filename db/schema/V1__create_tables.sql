-- =====================================================
-- RailFeas — V1 스키마
-- db/seed/S2__reference_line.sql 과 컬럼이 1:1 대응한다.
-- 변경 시 etl.py 의 SQL_COLUMNS 도 함께 수정할 것.
-- =====================================================

CREATE TABLE IF NOT EXISTS reference_line (
    line_id           BIGINT       AUTO_INCREMENT PRIMARY KEY,

    -- 식별
    line_name         VARCHAR(150) NOT NULL COMMENT '노선명 (예: 인덕원동탄선)',
    section_name      VARCHAR(200)          COMMENT '사업구간 (예: 인덕원~동탄)',
    operator          VARCHAR(100)          COMMENT '운영/시행 기관',

    -- 표준 분류
    rail_class        ENUM('METRO','REGIONAL','GENERAL','BRT') NOT NULL
                      COMMENT '운영 위계',
    mode_type         ENUM('HEAVY_METRO','LIGHT_RAIL','TRAM',
                           'BRT_HIGH','BRT_LOW',
                           'DOUBLE_ELEC','SINGLE_ELEC','UPGRADE')
                      COMMENT '수단/구조 유형 — 비용 모델 주 설명변수',
    region_class      ENUM('CAPITAL','METRO_C','LOCAL') NOT NULL
                      COMMENT '지가 대리변수',
    raw_type_text     VARCHAR(100)          COMMENT '원문 표기 보존 (예: 복선전철[BTL])',

    -- 제원 (출처마다 결측이 다름 → 모두 NULL 허용)
    length_km         DECIMAL(8,3)          COMMENT '노선 연장 (km로 통일)',
    station_count     INT                   COMMENT '정거장 수',
    underground_ratio DECIMAL(4,3)          COMMENT '지하구간 비율 0.000~1.000',

    -- 비용 (억원으로 통일)
    total_cost        BIGINT                COMMENT '총사업비 (억원)',
    base_year         SMALLINT              COMMENT '불변가 기준연도',
    total_cost_2025   BIGINT                COMMENT '2025년 환산액 (억원)',
    cost_status       ENUM('DISCLOSED','ESTIMATED','UNDISCLOSED')
                      NOT NULL DEFAULT 'UNDISCLOSED'
                      COMMENT '회귀 학습에는 DISCLOSED 만 사용',

    -- 이력
    period_start      SMALLINT,
    period_end        SMALLINT,
    opened_year       SMALLINT,
    bc_ratio_official DECIMAL(4,3)          COMMENT '공식 예타 B/C (모델 검증용)',

    -- 관리
    source_name       VARCHAR(150) NOT NULL,
    source_url        VARCHAR(300),
    is_outlier        BOOLEAN      NOT NULL DEFAULT FALSE
                      COMMENT '회귀 학습 제외 — 삭제하지 말고 사유를 note 에 남길 것',
    note              VARCHAR(300),
    collected_at      DATE         DEFAULT (CURRENT_DATE),

    -- 파생값: 직접 INSERT 금지, 계산으로만 채운다
    cost_per_km       DECIMAL(12,2)
        AS (total_cost / NULLIF(length_km, 0)) STORED
        COMMENT '원가 기준 km당 단가',
    cost_per_km_2025  DECIMAL(12,2)
        AS (total_cost_2025 / NULLIF(length_km, 0)) STORED,
    avg_spacing_km    DECIMAL(6,3)
        AS (length_km / NULLIF(station_count, 0)) STORED
        COMMENT '평균 역간격',
    data_grade        CHAR(1)
        AS (CASE
              WHEN total_cost IS NOT NULL
               AND length_km  IS NOT NULL
               AND station_count IS NOT NULL THEN 'A'
              WHEN total_cost IS NOT NULL
               AND length_km  IS NOT NULL    THEN 'B'
              WHEN length_km  IS NOT NULL
               AND station_count IS NOT NULL THEN 'C'
              ELSE 'D'
            END) STORED
        COMMENT 'A:완전 B:비용회귀용 C:역간격용 D:보류',

    UNIQUE KEY uk_line (line_name, section_name),
    INDEX idx_model (mode_type, region_class),
    INDEX idx_grade (data_grade),
    INDEX idx_cost  (cost_status, is_outlier)
) COMMENT='비용 모델 학습용 실제 노선·BRT 사례';


-- 원문 표기 → 표준 코드 매핑 (수집 자동화용)
CREATE TABLE IF NOT EXISTS type_mapping (
    mapping_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    raw_text   VARCHAR(100) NOT NULL UNIQUE,
    mode_type  VARCHAR(20)  NOT NULL,
    note       VARCHAR(150)
) COMMENT='출처별 표기 차이 흡수';


-- 물가 환산 계수 (기준연도 통일용, 값은 추후 한국은행 GDP 디플레이터로 채움)
CREATE TABLE IF NOT EXISTS price_index (
    base_year      SMALLINT     PRIMARY KEY,
    deflator       DECIMAL(6,3) NOT NULL,
    factor_to_2025 DECIMAL(6,4) NOT NULL,
    source         VARCHAR(150)
) COMMENT='total_cost_2025 = total_cost * factor_to_2025';


-- 수단별 수송능력·단가 기준 (추천 알고리즘 제약조건)
CREATE TABLE IF NOT EXISTS mode_capacity (
    mode_type       VARCHAR(20) PRIMARY KEY,
    display_name    VARCHAR(50) NOT NULL,
    pphpd_min       INT         COMMENT '시간당 최대수송인원 하한',
    pphpd_max       INT         COMMENT '시간당 최대수송인원 상한',
    cost_per_km_min INT         COMMENT '억원',
    cost_per_km_max INT,
    spacing_km      DECIMAL(4,2) COMMENT '표준 역간격 (중앙값)',
    source          VARCHAR(150)
) COMMENT='수단 추천 시 후보 필터링에 사용';
