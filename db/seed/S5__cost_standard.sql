-- =====================================================
-- S5: 수단×구조별 건설비 계수 (자동 생성)
-- etl/cost_model.py 가 reference_line 에서 뽑는다. 직접 수정하지 말 것.
-- 도시철도: 지하비율 모델 n=27, R2 0.803, 교차검증 오차 1.29배
-- 그 외 수단: 전체 표본 모델(n=63, 교차검증 1.45배) + 구조 보정
-- 역당 단가: 추정치 -223±225 억원 — 부호가 음수이거나 오차가 커서 0 으로 두고 연장 항에 포함 (n=29)
-- 값은 2025년 환산 기준(억원). 단일 값이 아니라 범위로 제시할 것.
-- =====================================================

TRUNCATE TABLE cost_standard;

INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('HEAVY_METRO', 'UNDERGROUND', 1702, 1090, 0, 2025, TRUE, '지하비율 모델 n=13');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('HEAVY_METRO', 'ELEVATED', 980, 628, 0, 2025, TRUE, '지하비율 모델 n=13');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('LIGHT_RAIL', 'UNDERGROUND', 1218, 780, 0, 2025, TRUE, '지하비율 모델 n=12');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('LIGHT_RAIL', 'ELEVATED', 702, 449, 0, 2025, TRUE, '지하비율 모델 n=12');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('TRAM', 'AT_GRADE', 653, 418, 0, 2025, TRUE, '지하비율 모델 n=2 — 표본 부족, 참고용');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('BRT_HIGH', 'AT_GRADE', 151, 35, 0, 2025, TRUE, '전체표본 모델+구조보정 n=6');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('BRT_HIGH', 'ELEVATED', 161, 37, 0, 2025, TRUE, '전체표본 모델+구조보정 n=6');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('BRT_LOW', 'AT_GRADE', 24, 5, 0, 2025, TRUE, '전체표본 모델+구조보정 n=3 — 표본 부족, 참고용');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('DOUBLE_ELEC', 'UNDERGROUND', 4439, 1022, 0, 2025, TRUE, '전체표본 모델+구조보정 n=14');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('DOUBLE_ELEC', 'ELEVATED', 2557, 589, 0, 2025, TRUE, '전체표본 모델+구조보정 n=14');
