"""KTX 소요시간 집계 규칙 — 파일도 네트워크도 읽지 않는 순수 함수만 가린다.

왜 순수 함수로 떼어냈나. 파일이나 API 를 읽는 함수는 못으로 박을 수가 없다
(`research-log.md` 의 급행 검정에서 같은 이유로 `welch_t` 를 떼어냈다).
여기서 틀리면 비교표에 **틀린 소요시간이 공표값처럼** 박히므로 규칙을 고정한다.
"""
import pytest

from korail_travel_time import (
    build_rows, coverage, group_by_fare, minutes, remaining, resolve_ids, split_point,
    split_systems, summarise,
)


class TestRemaining:
    """659쌍을 받는 데 10분이 걸리고, 그 사이 전송 오류 하나면 통째로 날아간다
    (실제로 `WinError 10060` 으로 한 번 날렸다). 받아 둔 쌍은 다시 받지 않는다."""

    PAIRS = [('경부선', '서울', '부산'), ('경부선', '서울', '대전'), ('호남선', '용산', '목포')]

    def test_이미_받은_쌍은_건너뛴다(self):
        done = {('서울', '부산')}
        assert remaining(self.PAIRS, done) == [('경부선', '서울', '대전'), ('호남선', '용산', '목포')]

    def test_방향이_뒤집혀_있어도_받은_것으로_본다(self):
        assert remaining(self.PAIRS, {('부산', '서울')})[0] == ('경부선', '서울', '대전')

    def test_받아_둔_것이_없으면_전부_받는다(self):
        assert remaining(self.PAIRS, set()) == self.PAIRS


class TestSplitPoint:
    """**운임이 같을 때만 묶으면 계통이 다섯으로 쪼개진다.**

    실제 2026-10-20 서울→부산 운임은 47,500 / 48,200 / 48,700 / 53,600 / 54,200 이었다.
    몇백 원 차이는 정차역 차이일 뿐이고, 경로가 갈리는 자리는 48,700~53,600 의
    4,900원 절벽 하나다. 그래서 **가장 크게 벌어진 한 자리에서만** 자른다.
    """

    def test_가장_크게_벌어진_자리에서_자른다(self):
        assert split_point([47500, 48200, 48700, 53600, 54200]) == 53600

    def test_고르게_퍼져_있으면_자르지_않는다(self):
        # 간격이 1000 으로 모두 같다 — 어느 자리도 "절벽" 이 아니다
        assert split_point([10000, 11000, 12000, 13000]) is None

    def test_운임이_하나면_자르지_않는다(self):
        assert split_point([54200]) is None

    def test_빈_목록이어도_터지지_않는다(self):
        assert split_point([]) is None


class TestMinutes:
    def test_출발_도착_시각_차이를_분으로_센다(self):
        assert minutes('20261020051300', '20261020075000') == 157

    def test_자정을_넘기면_하루를_더한다(self):
        # 23:50 출발 → 01:10 도착. 그냥 빼면 -1360 분이 된다
        assert minutes('20261020235000', '20261021011000') == 80

    def test_같은_시각이면_0_이_아니라_None(self):
        # 0 분짜리 열차는 없다. 0 으로 두면 "가장 빠른 계통" 으로 뽑혀 버린다
        assert minutes('20261020051300', '20261020051300') is None


class TestGroupByFare:
    def test_운임이_같으면_한_묶음이다(self):
        trains = [
            {'adultcharge': '54200', 'depplandtime': '20261020051300', 'arrplandtime': '20261020075000'},
            {'adultcharge': '54200', 'depplandtime': '20261020052700', 'arrplandtime': '20261020081600'},
            {'adultcharge': '47500', 'depplandtime': '20261020081200', 'arrplandtime': '20261020113300'},
        ]
        assert sorted(len(v) for v in group_by_fare(trains).values()) == [1, 2]

    def test_운임이_없으면_버리지_않고_따로_묶는다(self):
        trains = [{'depplandtime': '20261020051300', 'arrplandtime': '20261020075000'}]
        assert len(group_by_fare(trains)) == 1


class TestSummarise:
    """실제로 받은 2026-10-20 서울→부산 응답의 모양을 줄여 쓴다."""

    def _trains(self):
        def t(fare, dep, arr):
            return {'adultcharge': str(fare), 'traingradename': 'KTX',
                    'depplandtime': '20261020' + dep + '00', 'arrplandtime': '20261020' + arr + '00'}
        return [
            t(54200, '0513', '0750'),   # 157분
            t(54200, '0527', '0816'),   # 169
            t(54200, '1545', '1803'),   # 138
            t(47500, '0812', '1133'),   # 201
            t(47500, '1012', '1335'),   # 203
            t(47500, '1853', '2218'),   # 205
        ]

    def test_계통을_갈라_각각_중앙값과_범위를_낸다(self):
        rows = summarise(self._trains())
        assert len(rows) == 2
        fast, slow = rows
        assert (fast['trains'], fast['median_min'], fast['min_min'], fast['max_min']) == (3, 157, 138, 169)
        assert (slow['trains'], slow['median_min'], slow['min_min'], slow['max_min']) == (3, 203, 201, 205)

    def test_빠른_계통이_먼저다_운임_순서가_아니다(self):
        """**운임으로 고르지 않는다.** 묶기만 운임으로 하고 고르기는 시간으로 한다 —
        '비싼 쪽이 빠르다' 는 서울~부산에서만 확인했지 다른 노선에서는 모른다."""
        rows = summarise(self._trains())
        assert rows[0]['group_rank'] == 1
        assert rows[0]['median_min'] < rows[1]['median_min']
        assert rows[0]['fare_won'] > rows[1]['fare_won']   # 이번 구간은 비싼 쪽이 빨랐다

    def test_싼_쪽이_빠르면_그_쪽이_1등이다(self):
        def t(fare, dep, arr):
            return {'adultcharge': str(fare), 'depplandtime': '20261020' + dep + '00',
                    'arrplandtime': '20261020' + arr + '00'}
        rows = summarise([
            t(10000, '0900', '1000'), t(10000, '1000', '1102'), t(10000, '1100', '1201'),
            t(20000, '0900', '1200'), t(20000, '1000', '1255'), t(20000, '1100', '1358'),
        ])
        assert len(rows) == 2
        assert rows[0]['fare_won'] == 10000      # 싼 쪽이 빨랐다
        assert rows[0]['median_min'] < rows[1]['median_min']


class TestSplitSystems:
    """**가르는 것이 공짜가 아니다.** 표 아래에 "경로가 다른 N편" 이 붙고, 빠른 쪽의
    중앙값이 화면의 칸을 차지한다. 실제 자료로 가려 보니 운임 절벽만으로는 멀쩡한
    구간까지 갈라졌다 — 그래서 조건 둘을 더 걸었다."""

    @staticmethod
    def t(fare, dep, arr):
        return {'adultcharge': str(fare), 'depplandtime': '20261020' + dep + '00',
                'arrplandtime': '20261020' + arr + '00'}

    def test_한두_편짜리는_계통이_아니다(self):
        """`광명~물금` 이 **1편 143분 / 3편 149~160분** 으로 갈렸다. 범위는 겹치지도
        않아서 가드 ②로는 안 걸린다 — 그런데 1편을 계통이라 부르면 **그 한 열차의 시각이
        그대로 화면의 대표값**이 된다. 계통을 가른 목적이 바로 그걸 피하는 것이었다.

        그 1편은 **버리지 않는다.** 함께 세어 범위에 들어간다 — 빠른 열차가 있다는
        사실 자체는 참이고, 다만 그것 하나로 구간을 대표하지 않는다.
        """
        trains = [self.t(9000, '0900', '1123')] + [          # 143분
            self.t(10000, '1000', '1229'),                   # 149
            self.t(10000, '1100', '1335'),                   # 155
            self.t(10000, '1200', '1440'),                   # 160
        ]
        assert len(split_systems(trains)) == 1
        row = summarise(trains)[0]
        assert (row['trains'], row['min_min'], row['max_min']) == (4, 143, 160)

    def test_시간_범위가_겹치면_가르지_않는다(self):
        """`경주~대전` 32편 59~69분 / 3편 68~73분 — 갈라 적어도 읽을 게 없다."""
        trains = [self.t(9000, '0900', '0959'), self.t(9000, '1000', '1105'),
                  self.t(9000, '1100', '1209'),
                  self.t(20000, '1200', '1308'), self.t(20000, '1300', '1411'),
                  self.t(20000, '1400', '1513')]
        assert len(split_systems(trains)) == 1

    def test_범위가_떨어져_있고_양쪽이_넉넉하면_가른다(self):
        """`부산~서울` 44편 143~176분 / 11편 180~205분 — 이건 진짜 두 계통이다."""
        trains = [self.t(9000, '0900', '1120'), self.t(9000, '1000', '1225'),
                  self.t(9000, '1100', '1316'),
                  self.t(20000, '1200', '1500'), self.t(20000, '1300', '1603'),
                  self.t(20000, '1400', '1705')]
        assert len(split_systems(trains)) == 2

    def test_운임_절벽이_없으면_운임이_여럿이어도_한_묶음이다(self):
        """전에는 `by_fare` 를 그대로 흘려보내 **운임 종류만큼 계통이 생겼다.**
        실제 자료에서 안 터진 것은 규칙이 아니라 운이었다(절벽이 늘 있었다)."""
        trains = [self.t(10000, '0900', '1000'), self.t(11000, '1000', '1101'),
                  self.t(12000, '1100', '1202'), self.t(13000, '1200', '1303')]
        assert split_point([10000, 11000, 12000, 13000]) is None
        assert len(split_systems(trains)) == 1
        assert summarise(trains)[0]['trains'] == 4

    def test_짝수_편수의_중앙값은_가운데_둘의_평균이다(self):
        def t(dep, arr):
            return {'adultcharge': '1000', 'depplandtime': '20261020' + dep + '00',
                    'arrplandtime': '20261020' + arr + '00'}
        # 60, 70, 80, 90 → 75
        rows = summarise([t('0900', '1000'), t('0900', '1010'), t('0900', '1020'), t('0900', '1030')])
        assert rows[0]['median_min'] == 75

    def test_운임이_조금씩_달라도_한_계통이다(self):
        """실제 서울→부산 모양. 운임 5종이지만 계통은 둘이다."""
        def t(fare, dep, arr):
            return {'adultcharge': str(fare), 'depplandtime': '20261020' + dep + '00',
                    'arrplandtime': '20261020' + arr + '00'}
        rows = summarise([
            t(54200, '0513', '0750'), t(54200, '0527', '0816'), t(53600, '0558', '0843'),
            t(48700, '0812', '1133'), t(48200, '1012', '1335'), t(47500, '1853', '2214'),
        ])
        assert len(rows) == 2
        assert (rows[0]['trains'], rows[1]['trains']) == (3, 3)

    def test_열차가_없으면_빈_목록이다(self):
        assert summarise([]) == []

    def test_KTX_가_아닌_열차는_세지_않는다(self):
        trains = [
            {'adultcharge': '1000', 'traingradename': '무궁화호',
             'depplandtime': '20261020090000', 'arrplandtime': '20261020100000'},
        ]
        assert summarise(trains) == []

    def test_KTX_계열은_모두_센다(self):
        """**등급 코드 `00` 만 받았더니 강릉선이 통째로 빠졌다.**

        `GetVhcleKndList` 를 보면 KTX 계열이 여섯이다 — 00 KTX · 07·0A·10 KTX-산천 ·
        16 KTX-이음 · 19 KTX-청룡. 서울→진부는 전부 KTX-이음이라 `00` 으로는 0편이
        왔고, 서울~부산도 KTX-산천이 빠진 채 받았다. 그래서 **요청에 등급을 걸지 않고
        이름으로 거른다** — 등급이 하나 늘어도 코드를 안 고쳐도 된다.

        SRT 는 세지 않는다. 운영사가 다르고 출발역도 수서라, 우리 역 파일(KTX 역)에
        없는 역의 시각표를 "기존 KTX" 로 적는 셈이 된다.
        """
        def t(grade, dep, arr):
            return {'adultcharge': '1000', 'traingradename': grade,
                    'depplandtime': '20261020' + dep + '00',
                    'arrplandtime': '20261020' + arr + '00'}
        trains = [t('KTX', '0900', '1000'), t('KTX-산천', '0900', '1000'),
                  t('KTX-이음', '0900', '1000'), t('KTX-청룡', '0900', '1000'),
                  t('SRT', '0900', '1100'), t('ITX-마음', '0900', '1200')]
        rows = summarise(trains)
        assert len(rows) == 1
        assert rows[0]['trains'] == 4          # KTX 계열 넷만
        assert rows[0]['median_min'] == 60     # SRT·ITX 가 섞이면 늘어난다


class TestResolveIds:
    """우리 역 파일은 공식 이름(`울산(통도사)`)을 쓰고 TAGO 는 더러 짧은 이름(`울산`)을 쓴다.

    처음 659쌍을 받을 때 **울산·진부가 통째로 빠졌다.** 자료가 없어서가 아니라
    이름이 달라서였다 — `CLAUDE.md` 의 「"자료가 없다" 와 "내가 안 받았다" 는 다른 말」.

    그런데 **괄호를 먼저 벗기면 안 된다.** TAGO 는 `판교(경기)`/`판교(충남)` 처럼
    괄호로 서로 다른 역을 가르기도 한다. 벗긴 이름부터 보면 다른 역의 시각표를
    받아 그대로 적게 된다.
    """

    def test_정확한_이름이_있으면_그걸_쓴다(self):
        ids = {'판교(경기)': 'NAT250007', '판교(충남)': 'NAT081240'}
        assert resolve_ids(['판교(경기)'], ids) == {'판교(경기)': 'NAT250007'}

    def test_정확한_이름이_없으면_괄호를_벗겨_본다(self):
        ids = {'울산': 'NATH13717'}
        assert resolve_ids(['울산(통도사)'], ids) == {'울산(통도사)': 'NATH13717'}

    def test_벗긴_이름이_겹치면_둘_다_버린다(self):
        # 둘 다 `판교` 로 줄어든다 — 어느 쪽인지 모르는 채 쓰면 안 된다
        ids = {'판교': 'NAT250007'}
        assert resolve_ids(['판교(경기)', '판교(충남)'], ids) == {}

    def test_벗겨도_없으면_안_넣는다(self):
        assert resolve_ids(['없는역(어딘가)'], {'울산': 'X'}) == {}

    def test_괄호가_없는_이름은_그대로_찾는다(self):
        assert resolve_ids(['서울'], {'서울': 'NAT010000'}) == {'서울': 'NAT010000'}


class TestCoverage:
    """**전체 숫자만 보면 노선 하나가 통째로 빈 것이 안 보인다.**

    등급 `00` 만 받았을 때 강릉선이 0/N 이었는데 "직통 KTX 가 없는 구간 238개" 라는
    한 줄에 묻혔다. 노선 단위로 세면 `KTX 강릉선 0/12` 로 바로 드러난다.
    """

    PAIRS = [('KTX 경부선', '서울', '부산'), ('KTX 경부선', '서울', '대전'),
             ('KTX 강릉선', '서울', '진부'), ('KTX 강릉선', '서울', '강릉')]

    def test_노선마다_받은_구간과_받을_구간을_센다(self):
        rows = [{'from_station': '서울', 'to_station': '부산'}]
        assert coverage(self.PAIRS, rows) == [
            ('KTX 강릉선', 0, 2), ('KTX 경부선', 1, 2),
        ]

    def test_방향이_뒤집혀_있어도_받은_것으로_센다(self):
        rows = [{'from_station': '부산', 'to_station': '서울'}]
        assert coverage(self.PAIRS, rows)[1] == ('KTX 경부선', 1, 2)

    def test_통째로_빈_노선은_0_으로_나온다(self):
        assert coverage(self.PAIRS, []) == [
            ('KTX 강릉선', 0, 2), ('KTX 경부선', 0, 2),
        ]

    def test_받을_것이_없으면_빈_목록이다(self):
        assert coverage([], [{'from_station': '서울', 'to_station': '부산'}]) == []


class TestBuildRows:
    """받아 둔 원본 → CSV 줄. **네트워크를 쓰지 않는다** — 집계 규칙을 고치면
    `--rebuild` 로 여기만 다시 돌린다(659쌍을 다시 받지 않는다)."""

    PAIRS = [('KTX 경부선', '서울', '부산'), ('KTX 경부고속선', '서울', '부산')]

    @staticmethod
    def _train(fare, dep, arr):
        return {'adultcharge': str(fare), 'traingradename': 'KTX',
                'depplandtime': '20261020' + dep + '00',
                'arrplandtime': '20261020' + arr + '00'}

    def test_한_구간이_여러_노선에_걸리면_먼저_나온_노선을_쓴다(self):
        raw = {('서울', '부산'): [self._train(50000, '0900', '1130')]}
        rows, empty = build_rows(self.PAIRS, raw, '20261020')
        assert [r['line_name'] for r in rows] == ['KTX 경부선']
        assert empty == []

    def test_열차가_없던_구간은_줄이_아니라_빈_구간으로_센다(self):
        rows, empty = build_rows(self.PAIRS, {('서울', '부산'): []}, '20261020')
        assert rows == []
        assert empty == ['서울~부산']

    def test_받은_날짜를_모든_줄에_적는다(self):
        raw = {('서울', '부산'): [self._train(50000, '0900', '1130')]}
        rows, _ = build_rows(self.PAIRS, raw, '20261020')
        assert all(r['fetched_on'] == '20261020' for r in rows)
