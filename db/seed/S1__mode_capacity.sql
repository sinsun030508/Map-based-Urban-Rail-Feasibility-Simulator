-- =====================================================
-- S1: 수단별 수송능력·건설비 기준값
-- 출처: 국회예산정책처(NABIS), 국토교통부 BRT 정책자료,
--       reference_line 실사례 집계 (etl.py 산출)
-- =====================================================

TRUNCATE TABLE mode_capacity;

INSERT INTO mode_capacity
    (mode_type, display_name, pphpd_min, pphpd_max,
     cost_per_km_min, cost_per_km_max, spacing_km, source) VALUES
 ('HEAVY_METRO', '중전철(지하철)', 40000, 80000,  920, 1470, 1.06, '제안서(서울 3·7·9호선 연장·하단녹산선) + 운영현황 21개 노선 중앙값'),
 ('LIGHT_RAIL',  '경전철',          5000, 66000,  400,  700, 1.06, '제안서(용인경전철) + 운영현황 7개 노선 중앙값'),
 ('TRAM',        '트램',            5000, 20000,  380,  480, 0.86, '위례선·대전2호선 사례'),
 ('BRT_HIGH',    'BRT 고급형',     15000, 35000,   25,   64, 1.16, '국토교통부 BRT 정책자료 6건'),
 ('BRT_LOW',     'BRT 저급형',     10000, 20000,    2,   14, 1.16, '광역BRT 저비용 3건 — 역간격은 고급형 준용'),
 ('DOUBLE_ELEC', '복선전철',       30000, 60000,   72, 2379, NULL, '사업계획 18건'),
 ('SINGLE_ELEC', '단선전철',       10000, 25000,  177,  474, NULL, '사업계획 8건'),
 ('UPGRADE',     '기존선 개량',    10000, 30000,  101,  683, NULL, '사업계획 5건');


-- 원문 표기 매핑
TRUNCATE TABLE type_mapping;

INSERT INTO type_mapping (raw_text, mode_type, note) VALUES
 ('복선전철',       'DOUBLE_ELEC', NULL),
 ('복선전철화',     'DOUBLE_ELEC', '기존선 복선화'),
 ('복선전철[BTL]',  'DOUBLE_ELEC', '민자사업'),
 ('단선전철',       'SINGLE_ELEC', NULL),
 ('단선철도',       'SINGLE_ELEC', '비전철 포함'),
 ('단선개량',       'UPGRADE',     NULL),
 ('기존선개량',     'UPGRADE',     NULL),
 ('기존선 고속화',  'UPGRADE',     NULL),
 ('중전철',         'HEAVY_METRO', NULL),
 ('경전철',         'LIGHT_RAIL',  NULL),
 ('AGT',           'LIGHT_RAIL',  '고무차륜 경전철'),
 ('모노레일',       'LIGHT_RAIL',  NULL),
 ('노면전차',       'TRAM',        NULL),
 ('트램',           'TRAM',        NULL),
 ('HIGH',          'BRT_HIGH',    '전용도로·사전요금징수'),
 ('LOW',           'BRT_LOW',     '기존도로 활용 비중 높음');
