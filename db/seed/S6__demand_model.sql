SET NAMES utf8mb4;

-- =====================================================
-- S6: 수요 추정 계수 (자동 생성 — etl/demand_model.py)
-- log(일이용객) = -2.4952 + 0.5554·log(인구) + 0.6085·log(종사자) - 0.6170·경전철
-- 표본 35개 노선, R2 0.885, 교차검증 1.34배
-- 인구·종사자는 노선 둘레 1km 를 겹침 없이 합친 값이다. 경전철 항은 경전철 행에만 쓴다.
-- 수도권 노선과 대전·부산·대구 도시철도다. 장거리·다른 지역은 외삽이다.
-- =====================================================

DELETE FROM benefit_parameter WHERE param_name LIKE 'demand_%';

INSERT INTO benefit_parameter (param_name, value, unit, source) VALUES ('demand_intercept', -2.4952, '계수', '표준오차 1.197');
INSERT INTO benefit_parameter (param_name, value, unit, source) VALUES ('demand_pop_elasticity', 0.5554, '계수', '표준오차 0.155');
INSERT INTO benefit_parameter (param_name, value, unit, source) VALUES ('demand_worker_elasticity', 0.6085, '계수', '표준오차 0.129');
INSERT INTO benefit_parameter (param_name, value, unit, source) VALUES ('demand_light_rail_coef', -0.6170, '계수', '표준오차 0.202');
INSERT INTO benefit_parameter (param_name, value, unit, source) VALUES ('demand_loo_error', 1.3427, '배', '교차검증 평균 오차');
INSERT INTO benefit_parameter (param_name, value, unit, source) VALUES ('demand_brt_factor', 0.3563, '배', '세종 BRT 축 실측(행복청 2024) ÷ 모델 예측 — BRT 행에만');
