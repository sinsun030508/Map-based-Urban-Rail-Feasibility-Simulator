-- =====================================================
-- S5: 수단×구조별 건설비 계수 (자동 생성)
-- etl/cost_model.py 가 reference_line 에서 뽑는다. 직접 수정하지 말 것.
-- 주력 모델: A등급 n=38, R2 0.889, 교차검증 오차 1.25배
-- 역수 표본이 없는 수단(단선전철·개량·BRT 저급형)은 전체 표본 모델 + 구조·역수 보정
-- 역수 탄력성 0.272 — 역수가 표준 역간격보다 많으면 비용이 오른다
-- 값은 2025년 환산 기준(억원). 단일 값이 아니라 범위로 제시할 것.
-- =====================================================
SET NAMES utf8mb4;

-- 지우지 않고 **내린다** (is_active=FALSE). 이유가 둘이다:
--  ① scenario_result 가 standard_id 를 FK 로 참조해 DELETE 가 막힌다.
--     TRUNCATE 는 참조 행이 없어도 막히고, DELETE 는 있으면 막힌다 —
--     저장된 비교표가 하나라도 있으면 시드를 다시 넣을 수 없었다.
--  ② 저장해 둔 B/C 가 **어느 기준값으로 계산됐는지** 남는다. 지워 버리면
--     과거 결과의 근거가 사라져 재현할 수 없다. 이 프로젝트에서 값보다
--     중요한 것이 출처다.
-- 백엔드는 findByActiveTrue() 로 읽으므로 내린 행은 계산에 쓰이지 않는다.
-- 기준값이 바뀌면 화면이 "다시 계산해 주세요" 를 띄운다 (낡은 결과 알림).
UPDATE cost_standard SET is_active = FALSE WHERE is_active = TRUE;

INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('HEAVY_METRO', 'UNDERGROUND', 1869, 859, 388, 2025, TRUE, 'A등급 모델 n=13');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('HEAVY_METRO', 'ELEVATED', 1114, 512, 231, 2025, TRUE, 'A등급 모델 n=13');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('LIGHT_RAIL', 'UNDERGROUND', 1180, 543, 245, 2025, TRUE, 'A등급 모델 n=12');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('LIGHT_RAIL', 'ELEVATED', 703, 323, 146, 2025, TRUE, 'A등급 모델 n=12');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('TRAM', 'AT_GRADE', 579, 267, 98, 2025, TRUE, 'A등급 모델 n=2 — 표본 부족, 참고용');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('BRT_HIGH', 'AT_GRADE', 150, 23, 13, 2025, TRUE, '전체표본 모델+보정 n=6 (역수 표본 없음)');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('BRT_LOW', 'AT_GRADE', 23, 4, 2, 2025, TRUE, '전체표본 모델+보정 n=3 (역수 표본 없음) — 표본 부족, 참고용');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('DOUBLE_ELEC', 'UNDERGROUND', 1457, 666, 746, 2025, TRUE, 'A등급 모델 n=11');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('DOUBLE_ELEC', 'ELEVATED', 868, 397, 445, 2025, TRUE, 'A등급 모델 n=11');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('EXPRESS_RAIL', 'UNDERGROUND', 1052, 520, 1467, 2025, TRUE, 'A등급 모델 n=11 — DOUBLE_ELEC 계수를 빌려 씀 (잔차로 가를 근거를 못 봤다, 검출 한계 1.50배) / 역간격 6.92km 는 자기 것');
INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, cost_per_station, base_year, is_active, source) VALUES ('EXPRESS_RAIL', 'ELEVATED', 627, 310, 875, 2025, TRUE, 'A등급 모델 n=11 — DOUBLE_ELEC 계수를 빌려 씀 (잔차로 가를 근거를 못 봤다, 검출 한계 1.50배) / 역간격 6.92km 는 자기 것');
