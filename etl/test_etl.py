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
            {'mode_type': 'HEAVY_METRO', 'avg_spacing_km': 1.0,
             'source_name': etl.SPACING_SOURCE, 'is_outlier': False},
            {'mode_type': 'HEAVY_METRO', 'avg_spacing_km': 5.0,
             'source_name': '다른출처', 'is_outlier': False},
        ])
        out = etl.spacing_stats(df)

        assert out.loc['HEAVY_METRO', 'n'] == 1
        assert out.loc['HEAVY_METRO', '중앙값'] == 1.0

    def test_그_출처에_없는_수단은_전체에서_가져온다(self):
        # BRT 처럼 운영현황에 없는 수단까지 버리면 기준값이 사라진다
        df = pd.DataFrame([
            {'mode_type': 'HEAVY_METRO', 'avg_spacing_km': 1.0,
             'source_name': etl.SPACING_SOURCE, 'is_outlier': False},
            {'mode_type': 'BRT_HIGH', 'avg_spacing_km': 1.16,
             'source_name': '다른출처', 'is_outlier': False},
        ])
        out = etl.spacing_stats(df)

        assert out.loc['BRT_HIGH', '중앙값'] == 1.16

    def test_이상치는_빼고_센다(self):
        df = pd.DataFrame([
            {'mode_type': 'LIGHT_RAIL', 'avg_spacing_km': 1.0,
             'source_name': etl.SPACING_SOURCE, 'is_outlier': False},
            {'mode_type': 'LIGHT_RAIL', 'avg_spacing_km': 99.0,
             'source_name': etl.SPACING_SOURCE, 'is_outlier': True},
        ])
        out = etl.spacing_stats(df)

        assert out.loc['LIGHT_RAIL', 'n'] == 1
