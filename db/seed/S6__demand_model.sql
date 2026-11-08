SET NAMES utf8mb4;

-- =====================================================
-- S6: 수요 추정 계수 (자동 생성 — etl/demand_model.py)
-- log(일이용객) = -1.9814 + 0.4869·log(인구) + 0.6392·log(종사자) - 0.7765·경전철
-- 표본 26개 노선, R2 0.872, 교차검증 1.39배
-- 인구·종사자는 노선 둘레 1km 를 겹침 없이 합친 값이다. 경전철 항은 경전철 행에만 쓴다.
-- 수도권 노선 자료다. 지방·장거리는 외삽이다.
-- =====================================================

DELETE FROM benefit_parameter WHERE param_name LIKE 'demand_%';

INSERT INTO benefit_parameter (param_name, value, unit, source) VALUES ('demand_intercept', -1.9814, '계수', '표준오차 1.413');
INSERT INTO benefit_parameter (param_name, value, unit, source) VALUES ('demand_pop_elasticity', 0.4869, '계수', '표준오차 0.181');
INSERT INTO benefit_parameter (param_name, value, unit, source) VALUES ('demand_worker_elasticity', 0.6392, '계수', '표준오차 0.151');
INSERT INTO benefit_parameter (param_name, value, unit, source) VALUES ('demand_light_rail_coef', -0.7765, '계수', '표준오차 0.303');
INSERT INTO benefit_parameter (param_name, value, unit, source) VALUES ('demand_loo_error', 1.3937, '배', '교차검증 평균 오차');
