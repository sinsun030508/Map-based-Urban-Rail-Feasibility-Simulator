-- =====================================================
-- S5: 수단×구조별 건설비 계수 (자동 생성)
-- etl/cost_model.py 가 reference_line 에서 뽑는다. 직접 수정하지 말 것.
-- 주력 모델: A등급 n=38, R2 0.889, 교차검증 오차 1.25배
-- 역수 표본이 없는 수단(단선전철·개량·BRT 저급형)은 전체 표본 모델 + 구조·역수 보정
-- 역수 탄력성 0.272 — 역수가 표준 역간격보다 많으면 비용이 오른다
-- 값은 2025년 환산 기준(억원). 단일 값이 아니라 범위로 제시할 것.
-- =====================================================
SET NAMES utf8mb4;

-- scenario_result 가 FK 로 참조해 TRUNCATE 가 막힌다. DELETE 로 비운다
DELETE FROM cost_standard;

INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('HEAVY_METRO', 'UNDERGROUND', 1869, 859, 388, 2025, TRUE, 'A등급 모델 n=13');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('HEAVY_METRO', 'ELEVATED', 1114, 512, 231, 2025, TRUE, 'A등급 모델 n=13');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('LIGHT_RAIL', 'UNDERGROUND', 1180, 543, 245, 2025, TRUE, 'A등급 모델 n=12');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('LIGHT_RAIL', 'ELEVATED', 703, 323, 146, 2025, TRUE, 'A등급 모델 n=12');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('TRAM', 'AT_GRADE', 579, 267, 98, 2025, TRUE, 'A등급 모델 n=2 — 표본 부족, 참고용');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('BRT_HIGH', 'AT_GRADE', 150, 23, 13, 2025, TRUE, '전체표본 모델+보정 n=6 (역수 표본 없음)');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('BRT_HIGH', 'ELEVATED', 159, 25, 14, 2025, TRUE, '전체표본 모델+보정 n=6 (역수 표본 없음)');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('BRT_LOW', 'AT_GRADE', 23, 4, 2, 2025, TRUE, '전체표본 모델+보정 n=3 (역수 표본 없음) — 표본 부족, 참고용');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('DOUBLE_ELEC', 'UNDERGROUND', 1229, 561, 1176, 2025, TRUE, 'A등급 모델 n=11');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('DOUBLE_ELEC', 'ELEVATED', 732, 335, 701, 2025, TRUE, 'A등급 모델 n=11');
