SET NAMES utf8mb4;

-- =====================================================
-- S6: 수요 추정 계수 (자동 생성 — etl/demand_model.py)
-- log(일이용객) = 2.6076 + 0.4368·log(인구) + 0.2832·log(종사자) + 1.0799·log(환승노선) + -0.0118·도심거리km
-- 표본 514개 역, R2 0.551, 교차검증 1.75배
-- 서울 자료다. 지방은 이용률이 낮아 과대추정이 난다.
-- 거주 인구만 세므로 주간인구가 많은 업무지구는 크게 빗나간다.
-- =====================================================

DELETE FROM benefit_parameter WHERE param_name LIKE 'demand_%';

INSERT INTO benefit_parameter (param_name, value, unit, source) VALUES ('demand_intercept', 2.6076, '계수', '표준오차 0.480');
INSERT INTO benefit_parameter (param_name, value, unit, source) VALUES ('demand_pop_elasticity', 0.4368, '계수', '표준오차 0.033');
INSERT INTO benefit_parameter (param_name, value, unit, source) VALUES ('demand_worker_elasticity', 0.2832, '계수', '표준오차 0.031');
INSERT INTO benefit_parameter (param_name, value, unit, source) VALUES ('demand_transfer_elasticity', 1.0799, '계수', '표준오차 0.115');
INSERT INTO benefit_parameter (param_name, value, unit, source) VALUES ('demand_cbd_slope', -0.0118, '계수', '표준오차 0.002');
INSERT INTO benefit_parameter (param_name, value, unit, source) VALUES ('demand_loo_error', 1.7454, '배', '교차검증 평균 오차');
