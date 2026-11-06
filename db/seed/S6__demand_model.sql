SET NAMES utf8mb4;

-- =====================================================
-- S6: 수요 추정 계수 (자동 생성 — etl/demand_model.py)
-- log(일이용객) = -2.4994 + 0.4709·log(인구) + 0.6913·log(종사자)
-- 표본 26개 노선, R2 0.834, 교차검증 1.44배
-- 인구·종사자는 노선 둘레 1km 를 겹침 없이 합친 값이다.
-- 수도권 노선 자료다. 지방·장거리는 외삽이다.
-- =====================================================

DELETE FROM benefit_parameter WHERE param_name LIKE 'demand_%';

INSERT INTO benefit_parameter (param_name, value, unit, source) VALUES ('demand_intercept', -2.4994, '계수', '표준오차 1.559');
INSERT INTO benefit_parameter (param_name, value, unit, source) VALUES ('demand_pop_elasticity', 0.4709, '계수', '표준오차 0.202');
INSERT INTO benefit_parameter (param_name, value, unit, source) VALUES ('demand_worker_elasticity', 0.6913, '계수', '표준오차 0.166');
INSERT INTO benefit_parameter (param_name, value, unit, source) VALUES ('demand_loo_error', 1.4404, '배', '교차검증 평균 오차');
