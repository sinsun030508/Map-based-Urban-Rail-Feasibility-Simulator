"""
ETL 파싱·판정 규칙 고정.

원본 3종은 포맷이 제각각이라 파서가 조용히 빗나가면 **데이터가 썩은 채로 회귀까지 흘러간다.**
여기서 보는 것은 "원본의 이런 표기를 이렇게 읽기로 했다"는 약속들이다.

    cd etl && python -m pytest
"""

import pandas as pd
import pytest

import etl


class TestCleanText:
    def test_중점_변형을_하나로_통일한다(self):
        # 출처마다 ․ · ㆍ 를 섞어 써서 그대로 두면 같은 노선이 다른 이름이 된다
        assert etl.clean_text('서울․경기') == '서울·경기'
        assert etl.clean_text('서울ㆍ경기') == '서울·경기'

    def test_연속_공백을_줄인다(self):
        assert etl.clean_text('신안산선   복선전철') == '신안산선 복선전철'

    def test_빈_값은_None(self):
        assert etl.clean_text('   ') is None
        assert etl.clean_text(None) is None


class TestToNumber:
    @pytest.mark.parametrize('raw, expected', [
        ('10,719', 10719.0),      # 천단위 구분
        ('43055 ', 43055.0),      # 꼬리 공백
        ('-', None),              # 값 없음 표기
        ('미정', None),
        ('N/A', None),
        ('', None),
    ])
    def test_표기_차이를_흡수한다(self, raw, expected):
        assert etl.to_number(raw) == expected

    def test_숫자가_아니면_None_이지_0_이_아니다(self):
        # 0 으로 채우면 비용 회귀에 0원짜리 사업이 들어간다
        assert etl.to_number('검토중') is None
        assert etl.to_int('검토중') is None


class TestParsePeriod:
    @pytest.mark.parametrize('raw', ['2020~2032', '2020∼2032', '2020〜2032', '2020～2032'])
    def test_물결_변형을_모두_읽는다(self, raw):
        assert etl.parse_period(raw) == (2020, 2032)

    def test_한쪽만_있으면_끝은_None(self):
        assert etl.parse_period('2025 착공') == (2025, None)

    def test_연도가_없으면_둘_다_None(self):
        assert etl.parse_period('미정') == (None, None)


class TestParseShortYear:
    @pytest.mark.parametrize('raw, expected', [
        ("'74.08.15", 1974),      # 50 이상은 1900년대
        ("'24.12.28.", 2024),     # 50 미만은 2000년대
        ('’99.06.30', 1999),
        ('‘05.11.28', 2005),
        ('1974.08.15', 1974),     # 네 자리로 와도 맞는다
        ('2024.12.28', 2024),
    ])
    def test_두_자리_연도를_세기로_나눈다(self, raw, expected):
        assert etl.parse_short_year(raw) == expected

    def test_날짜가_아니면_None(self):
        assert etl.parse_short_year('미개통') is None


class TestCountStations:
    def test_정거장구성_문자열을_센다(self):
        assert etl.count_stations('A01-서울,A02-공덕,A03-홍대') == 3

    def test_빈_조각은_세지_않는다(self):
        assert etl.count_stations('A01-서울,,A02-공덕,') == 2

    def test_비어_있으면_None(self):
        assert etl.count_stations('') is None


class TestSplitLineName:
    def test_노선명과_유형_접미사를_가른다(self):
        assert etl.split_line_name('신안산선 복선전철') == ('신안산선', '복선전철')
        assert etl.split_line_name('장항선 단선개량') == ('장항선', '단선개량')

    def test_접미사가_없으면_그대로(self):
        assert etl.split_line_name('신분당선') == ('신분당선', None)

    def test_어절_중복을_지운다(self):
        # 원본에 '경부고속철도철도' 처럼 붙어 들어온 행이 있었다
        assert etl.split_line_name('경부고속철도철도')[0] == '경부고속철도'


class TestClassify:
    @pytest.mark.parametrize('text, expected', [
        ('복선전철', 'DOUBLE_ELEC'),
        ('단선전철', 'SINGLE_ELEC'),
        ('노면전차', 'TRAM'),
        ('모노레일', 'LIGHT_RAIL'),
        ('AGT', 'LIGHT_RAIL'),
    ])
    def test_원문_표기를_수단으로_옮긴다(self, text, expected):
        assert etl.classify_type(text) == expected

    def test_개량이_복선보다_먼저다(self):
        # '복선전철화 개량' 같은 표기는 개량으로 봐야 한다 — 규칙 순서가 뒤집히면 깨진다
        assert etl.classify_type('기존선 개량') == 'UPGRADE'
        assert etl.classify_type('복선전철 개량') == 'UPGRADE'

    def test_모르면_fallback(self):
        assert etl.classify_type('알 수 없음', fallback='HEAVY_METRO') == 'HEAVY_METRO'

    def test_경전철은_노선명으로_안다(self):
        # 운영현황·xlsx 모두 유형 표기가 없어 이름으로 판정한다
        assert etl.is_light_rail('우이신설선') is True
        assert etl.is_light_rail('용인 경전철') is True
        assert etl.is_light_rail('서울 2호선') is False


class TestGrade:
    @pytest.mark.parametrize('cost, length, stations, expected', [
        (1000, 10, 5, 'A'),      # 연장+역수+금액 → 비용 회귀 + 역간격
        (1000, 10, None, 'B'),   # 금액은 있는데 역수 없음 → 비용 회귀만
        (None, 10, 5, 'C'),      # 금액 없음 → 역간격만
        (1000, None, None, 'D'), # 연장이 없으면 쓸 데가 없다
        (None, None, None, 'D'),
    ])
    def test_등급은_가진_열로_정해진다(self, cost, length, stations, expected):
        row = pd.Series({'total_cost': cost, 'length_km': length,
                         'station_count': stations})
        assert etl.grade(row) == expected


class TestDropSameProjects:
    """출처 간 같은 사업은 등급 낮은 행을 지운다. 어긋나면 **조용히 넘어가지 않고 멈춘다.**"""

    def _frame(self, rows):
        return pd.DataFrame(rows)

    def test_매칭이_어긋나면_멈춘다(self, monkeypatch):
        monkeypatch.setattr(etl, 'SAME_PROJECT',
                            [('없는노선', '없는출처', '남길노선')])
        df = self._frame([
            {'line_name': '남길노선', 'source_name': 'X', 'data_grade': 'A', 'note': None},
        ])
        with pytest.raises(ValueError, match='매칭 실패'):
            etl.drop_same_projects(df)

    def test_등급이_역전되면_멈춘다(self, monkeypatch):
        # 지울 행이 남길 행보다 등급이 높으면 지우는 쪽이 잘못된 것이다
        monkeypatch.setattr(etl, 'SAME_PROJECT', [('지울노선', '출처B', '남길노선')])
        df = self._frame([
            {'line_name': '지울노선', 'source_name': '출처B', 'data_grade': 'A', 'note': None},
            {'line_name': '남길노선', 'source_name': '출처A', 'data_grade': 'C', 'note': None},
        ])
        with pytest.raises(ValueError, match='등급 역전'):
            etl.drop_same_projects(df)

    def test_지운_행을_note_에_남긴다(self, monkeypatch):
        monkeypatch.setattr(etl, 'SAME_PROJECT', [('지울노선', '출처B', '남길노선')])
        df = self._frame([
            {'line_name': '지울노선', 'source_name': '출처B', 'data_grade': 'C', 'note': None},
            {'line_name': '남길노선', 'source_name': '출처A', 'data_grade': 'A', 'note': None},
        ])
        out = etl.drop_same_projects(df)

        assert len(out) == 1
        assert out.iloc[0]['line_name'] == '남길노선'
        # 보고서에 "왜 지웠나"를 쓸 수 있어야 한다
        assert '지울노선' in out.iloc[0]['note']


class TestSpacingStats:
    """역간격은 **운영현황 한 출처만** 쓴다 — 같은 노선이 여러 출처에 중복돼 있다."""

    def test_한_출처만_집계한다(self):
        df = pd.DataFrame([
            {'mode_type': 'HEAVY_METRO', 'avg_spacing_km': 1.0, 'station_count': 10,
             'source_name': etl.SPACING_SOURCE, 'is_outlier': False},
            {'mode_type': 'HEAVY_METRO', 'avg_spacing_km': 5.0, 'station_count': 10,
             'source_name': '다른출처', 'is_outlier': False},
        ])
        out = etl.spacing_stats(df)

        assert out.loc['HEAVY_METRO', 'n'] == 1
        assert out.loc['HEAVY_METRO', '중앙값'] == 1.0

    def test_그_출처에_없는_수단은_전체에서_가져온다(self):
        # BRT 처럼 운영현황에 없는 수단까지 버리면 기준값이 사라진다
        df = pd.DataFrame([
            {'mode_type': 'HEAVY_METRO', 'avg_spacing_km': 1.0, 'station_count': 10,
             'source_name': etl.SPACING_SOURCE, 'is_outlier': False},
            {'mode_type': 'BRT_HIGH', 'avg_spacing_km': 1.16, 'station_count': 10,
             'source_name': '다른출처', 'is_outlier': False},
        ])
        out = etl.spacing_stats(df)

        assert out.loc['BRT_HIGH', '중앙값'] == 1.16

    def test_이상치는_빼고_센다(self):
        df = pd.DataFrame([
            {'mode_type': 'LIGHT_RAIL', 'avg_spacing_km': 1.0, 'station_count': 10,
             'source_name': etl.SPACING_SOURCE, 'is_outlier': False},
            {'mode_type': 'LIGHT_RAIL', 'avg_spacing_km': 99.0, 'station_count': 10,
             'source_name': etl.SPACING_SOURCE, 'is_outlier': True},
        ])
        out = etl.spacing_stats(df)

        assert out.loc['LIGHT_RAIL', 'n'] == 1


class TestOsmRegionalStations:
    """
    지방 역 좌표 수집(`osm_regional_stations.py`)의 판정 규칙.

    OSM 은 집단 편집 자료라 누락이 흔하다. 실제로 첫 수집본은 10개 노선 **전부**가
    공식 역수에 미달했는데(99역 / 271역), 관계 멤버만 긁어서 생긴 일이었다.
    여기서 고정하는 것은 "그런 자료를 어떻게 걸러내기로 했는가"다.
    """

    def test_거리는_미터로_재고_가까운_역만_남긴다(self):
        import osm_regional_stations as osm
        # 위도 0.001도 ≈ 111m — 반경 150m 안이다
        assert osm.meters(37.5000, 127.0, 37.5010, 127.0) == pytest.approx(111, abs=2)
        # 0.01도 ≈ 1.1km — 밖이다
        assert osm.meters(37.5000, 127.0, 37.5100, 127.0) > osm.STATION_RADIUS_M

    def test_bbox_는_노선_이름의_도시로_고른다(self):
        import osm_regional_stations as osm
        # bbox 가 없으면 Overpass 가 전 세계를 훑어 504 가 난다
        assert osm.bbox_of('부산 도시철도 1호선') == osm.CITY_BBOX['부산']
        # 부산김해경전철은 김해까지 가지만 부산 상자 안에 든다
        assert osm.bbox_of('부산김해경전철') == osm.CITY_BBOX['부산']
        with pytest.raises(SystemExit):
            osm.bbox_of('없는도시 1호선')

    def test_채택_명단은_운영현황이다(self):
        import osm_regional_stations as osm
        # OSM 에는 미개통 노선도 운영 노선처럼 올라와 있다 (광주 2호선은 공사 중).
        # 없는 노선을 "기존 노선 활용 검토" 로 안내하면 잘못된 권고가 된다
        assert '광주 도시철도 1호선' in osm.OFFICIAL_STATIONS
        assert '광주 도시철도 2호선' not in osm.OFFICIAL_STATIONS

    def test_역수가_어긋나면_그_노선은_쓰지_않는다(self):
        import osm_regional_stations as osm
        official = osm.OFFICIAL_STATIONS['부산 도시철도 1호선']
        # 반쪽짜리 좌표가 들어가면 "기존 노선 활용" 판정이 조용히 빗나간다
        assert abs(19 - official) > osm.TOLERANCE        # 첫 수집본이 19역이었다
        assert abs(40 - official) <= osm.TOLERANCE       # 고친 뒤 40역

    def test_같은_장소의_역은_하나로_묶는다(self):
        import osm_regional_stations as osm
        # 선로 주변을 훑으면 나란히 있는 일반철도 역까지 딸려 온다.
        # 대구 1호선에서 코레일 '대구' 와 지하철 '대구역' 이 101m 거리로 둘 다 잡혀
        # 역수가 하나 많았다 (36 vs 공식 35)
        found = {
            '대구역': (35.8760, 128.5960),
            '대구': (35.8769, 128.5955),      # 약 100m
            '중앙로': (35.8690, 128.5960),    # 약 780m - 다른 역이다
        }
        kept = osm.dedupe(found)
        assert len(kept) == 2
        assert '중앙로' in kept


class Test정거장구성구분자:
    """xlsx 의 `정거장구성` 은 보통 쉼표로 구분되는데 두 노선만 `+` 를 쓴다.

    쉼표로만 쪼개면 신분당선 16역이 **1역**으로 읽히고, 역간격이 33.5km 로 나온다.
    이상치 목록에 "정거장구성 미기재로 추정" 으로 적혀 있었지만 미기재가 아니라
    구분자가 달랐을 뿐이다 — 제외가 아니라 파싱을 고쳐야 한다.
    """

    def test_쉼표로_구분된_것을_센다(self):
        assert etl.count_stations('A01-서울,A02-공덕,A03-홍대입구') == 3

    def test_플러스로_구분된_것도_센다(self):
        s = ('D04-신사+D05-논현+D06-신논현+D07-강남+D08-양재+D09-양재시민의숲+'
             'D10-청계산입구+D11-판교+D12-정자+D13-미금+D14-동천+D15-수지구청+'
             'D16-성복+D17-상현+D18-광교중앙+D19-광교')
        assert etl.count_stations(s) == 16

    def test_두_역짜리_플러스도_센다(self):
        assert etl.count_stations('805-다산+804-별내') == 2

    def test_중점은_역_이름_안에_있으므로_쪼개지_않는다(self):
        # `전대·에버랜드` 처럼 역 이름에 · 가 들어간다. 쪼개면 역수가 부풀어 오른다
        assert etl.count_stations('Y110-기흥,Y111-전대·에버랜드') == 2

    def test_빈_값은_None(self):
        assert etl.count_stations('') is None
        assert etl.count_stations(None) is None


class Test두자리날짜:
    """xlsx 개통일자에 `YY.MM.DD` 형식이 섞여 있다.

    pandas 는 `22.05.28` 을 DD.MM.YY 로 읽어 **2028년**을 내놓는다. 이 프로젝트에는
    이미 올바른 `parse_short_year` 가 있는데 xlsx 경로에서 쓰지 않았다.
    """

    def test_두_자리_연도를_연도로_읽는다(self):
        assert etl.parse_short_year('22.05.28') == 2022
        assert etl.parse_short_year('24.12.28') == 2024

    def test_50년대_이전은_1900년대로_읽는다(self):
        assert etl.parse_short_year('74.08.15') == 1974

    def test_xlsx_개통일자_파서가_두_자리_형식을_처리한다(self):
        # 네 자리 날짜는 그대로, 두 자리 형식은 parse_short_year 로
        assert etl.parse_opened_year('2024-08-10 00:00:00') == 2024
        assert etl.parse_opened_year('22.05.28') == 2022
        assert etl.parse_opened_year(None) is None


class Test역간격은역이둘이상일때만:
    """역이 하나면 역간격이란 것이 존재하지 않는다.

    `가덕도신공항 접근철도` 는 16.5km 에 역 1개(공항역)라 역간격이 16.5km 로 계산됐고,
    복선전철 역간격 중앙값을 4.30 에서 4.97 로 밀어 올렸다. 역간격은
    `역수 = 연장 ÷ 역간격` 으로 쓰이고 복선전철 역당단가가 1,176억이라
    50km 노선에서 역 2개 차이 = 2,350억 차이가 난다.
    """

    def test_역이_하나면_역간격_통계에서_뺀다(self):
        df = pd.DataFrame([
            {'mode_type': 'DOUBLE_ELEC', 'avg_spacing_km': 16.5, 'station_count': 1,
             'is_outlier': False, 'source_name': 'X'},
            {'mode_type': 'DOUBLE_ELEC', 'avg_spacing_km': 4.0, 'station_count': 10,
             'is_outlier': False, 'source_name': 'X'},
            {'mode_type': 'DOUBLE_ELEC', 'avg_spacing_km': 5.0, 'station_count': 8,
             'is_outlier': False, 'source_name': 'X'},
        ])
        out = etl.spacing_stats(df)
        assert out.loc['DOUBLE_ELEC', 'n'] == 2
        assert out.loc['DOUBLE_ELEC', '최대'] == 5.0
