"""
비용 모델의 **판단 규칙**을 고정한다 — 계수 값이 아니라 "무엇을 변수로 넣을지".

계수는 자료가 바뀌면 바뀌어야 하므로 못으로 박지 않는다. 박아야 하는 것은
**근거 없이 변수를 늘리지 않는다**는 규칙이고, 그 규칙이 작은 표본에서
조용히 뒤집히는 자리를 막는다.

    cd etl && python -m pytest test_cost_model.py
"""

import numpy as np
import pytest

import cost_model as C


class TestWelch검정:
    """합성 자료로 통계 자체를 가린다 — 파일을 읽는 함수는 못으로 박을 수 없다."""

    def test_똑같은_두_묶음은_t가_0이다(self):
        t, se = C.welch_t([1.0, 2.0, 3.0], [1.0, 2.0, 3.0])
        assert t == pytest.approx(0.0)
        assert se > 0

    def test_뚜렷하게_갈리면_t가_크다(self):
        t, _ = C.welch_t([10.0, 10.1, 9.9, 10.0], [20.0, 20.1, 19.9, 20.0])
        assert abs(t) > 2

    def test_차이가_같아도_흩어지면_t가_작아진다(self):
        """표본이 작고 흩어지면 같은 차이도 안 보인다 — 검출 한계의 뜻이다."""
        좁게, _ = C.welch_t([10.0, 10.1, 9.9, 10.0], [11.0, 11.1, 10.9, 11.0])
        넓게, _ = C.welch_t([8.0, 12.0, 9.0, 11.0], [9.0, 13.0, 10.0, 12.0])
        assert abs(좁게) > 2
        assert abs(넓게) < 2

    def test_분산이_0이면_t가_터진다(self):
        """**이것이 n=2 사고의 정체다.** 두 값이 같으면 표준오차가 0 이 되어
        아무리 작은 차이도 "유의"해 보인다. 그래서 `MIN_SPLIT_N` 이 필요하다."""
        t, se = C.welch_t([1.0, 1.0], [0.0, 0.5, 1.0, 1.5, 2.0, 0.3, 0.7])
        assert se < 0.3                 # 한쪽 분산이 0 이라 표준오차가 작다
        # 두 값이 조금만 벌어져도 t 가 급격히 내려간다
        t2, se2 = C.welch_t([0.8, 1.2], [0.0, 0.5, 1.0, 1.5, 2.0, 0.3, 0.7])
        assert se2 > se
        assert abs(t2) < abs(t)


class Test후보변수검정:
    """"이 변수를 비용 모델에 넣어야 하나" 를 잔차로 가린다."""

    def test_표본이_적으면_t를_내놓지_않는다(self):
        """**실제로 속았던 자리다.** `계획 사업비` 두 노선의 잔차가 소수점 5자리까지
        같아(둘 다 1.3243배) 분산이 0 이 되고 t 가 7.75 로 튀었다. 사업비는
        15,828 / 18,356 으로 서로 다른 숫자인데 비율이 우연히 겹친 것이다.

        표본이 `MIN_SPLIT_N` 미만인 후보는 t·비율·검출한계를 **전부 None** 으로 둔다 —
        숫자를 내놓으면 누군가 그것을 읽는다.
        """
        rows = {r[0]: r for r in C.candidate_splits()}
        for name in ('저심도', '계획 사업비'):
            assert name in rows, f'{name} 후보가 사라졌다'
            _, na, nb, ratio, t, limit, verdict = rows[name]
            assert min(na, nb) < C.MIN_SPLIT_N
            assert (ratio, t, limit) == (None, None, None), '표본이 적은데 숫자를 내놓는다'
            assert '표본 부족' in verdict

    def test_공법은_표본이_없어_변수로_만들_수_없다(self):
        """다음 할 일 ⑤ 의 답이다 — 추측이 아니라 표본 수가 답이다."""
        rows = {r[0]: r for r in C.candidate_splits()}
        assert rows['저심도'][1] < C.MIN_SPLIT_N
        assert rows['지상 위주'][1] < C.MIN_SPLIT_N

    def test_표본이_넉넉한_후보는_숫자를_내놓는다(self):
        rows = {r[0]: r for r in C.candidate_splits()}
        _, na, nb, ratio, t, limit, _ = rows['기준연도가 미래']
        assert min(na, nb) >= C.MIN_SPLIT_N
        assert all(v is not None for v in (ratio, t, limit))

    def test_기준연도가_미래여도_잔차가_쏠리지_않는다(self):
        """CLAUDE.md 의 "대부분 ASSUMED 라 효과는 미미" 를 검정한 것이다.

        A등급 38건 중 19건이 기준연도 2025 초과(= 물가지수 범위 밖이라 **환산이
        아예 안 된다**)인데, 두 집단의 잔차가 갈리지 않는다. **표본이 늘어 이 테스트가
        깨지면 그 주장을 다시 봐야 한다.**
        """
        rows = {r[0]: r for r in C.candidate_splits()}
        _, _, _, ratio, t, limit, _ = rows['기준연도가 미래']
        assert abs(t) < 2, f't={t:+.2f} — 미래 기준연도가 비용을 밀어 올린다'
        assert limit > 1.1, '검출 한계가 작아졌다 — 이제 "효과 없음"을 말할 수 있다'

    def test_이미_설명되는_변수는_쏠리지_않는다(self):
        """역간격은 `log(역수)` 를 통해 이미 모델 안에 있다 — 그래서 안 쏠려야 한다.
        쏠리면 역수만으로는 부족하다는 뜻이니 그때 다시 본다."""
        rows = {r[0]: r for r in C.candidate_splits()}
        _, _, _, _, t, _, _ = rows['역간격 >= 4.5km']
        assert abs(t) < 2


class Test역간격이_S1과_맞는지:
    def test_어긋나면_멈춘다(self):
        """같은 값이 두 곳에 있으면 언젠가 어긋난다 — 실제로 4.98 vs 4.30 으로 어긋났다."""
        원래 = C.SPACING.copy()
        try:
            C.SPACING['DOUBLE_ELEC'] = 9.99
            with pytest.raises(SystemExit) as e:
                C.check_spacing_matches_s1()
            assert 'DOUBLE_ELEC' in str(e.value)
        finally:
            C.SPACING.clear()
            C.SPACING.update(원래)

    def test_지금은_맞는다(self):
        C.check_spacing_matches_s1()

    def test_S1에_없는_수단이_있으면_멈춘다(self):
        원래 = C.SPACING.copy()
        try:
            C.SPACING['없는수단'] = 1.0
            with pytest.raises(SystemExit) as e:
                C.check_spacing_matches_s1()
            assert '없는수단' in str(e.value)
        finally:
            C.SPACING.clear()
            C.SPACING.update(원래)


class Test급행은계수를빌린다:
    def test_급행은_복선전철_계수를_쓴다(self):
        assert C.COST_ALIAS['EXPRESS_RAIL'] == 'DOUBLE_ELEC'

    def test_역간격은_자기_것을_쓴다(self):
        """계수는 빌리되 역간격은 자기 것이라야 **역이 적은 만큼 싸진다.**"""
        assert C.SPACING['EXPRESS_RAIL'] > C.SPACING['DOUBLE_ELEC']

    def test_빌린_수단도_구조가_정의돼_있다(self):
        for mode in C.COST_ALIAS:
            assert mode in C.MODE_STRUCTURES, f'{mode} 에 구조가 없어 S5 행이 안 생긴다'

    def test_같은_입력이면_빌린_쪽과_원본이_같은_값을_낸다(self):
        """**이것이 "비용을 가르지 않았다"의 뜻이다.** 수단 이름 자체는 효과가 없다."""
        s = C.load_sample(grade=('A',))
        s = s[s.underground_ratio.notna() & s.station_count.notna()]
        m = C.fit_model(s, with_ug=True, with_stations=True)
        원본 = C.predict_cost(m, 'DOUBLE_ELEC', 1.0, 30.0, 10)
        빌림 = C.predict_cost(m, 'EXPRESS_RAIL', 1.0, 30.0, 10, dummy='DOUBLE_ELEC')
        assert float(빌림) == pytest.approx(float(원본))


class TestS5는지우지않는다:
    def test_내리고_넣는다(self):
        """`DELETE` 는 scenario_result 가 FK 로 참조해 막힌다. 저장된 B/C 가 어느
        기준값으로 계산됐는지도 남겨야 한다."""
        sql = (C.DB_SEED / 'S5__cost_standard.sql').read_text(encoding='utf-8')
        assert 'DELETE FROM cost_standard' not in sql
        assert 'UPDATE cost_standard SET is_active = FALSE' in sql
        assert sql.index('UPDATE cost_standard') < sql.index('INSERT INTO cost_standard')

    def test_한글이_깨지지_않게_SET_NAMES_가_있다(self):
        sql = (C.DB_SEED / 'S5__cost_standard.sql').read_text(encoding='utf-8')
        assert sql.splitlines()[8].strip() == 'SET NAMES utf8mb4;' or 'SET NAMES utf8mb4;' in sql

    def test_source가_칸을_넘지_않는다(self):
        """`mode_capacity.source` 가 varchar(150) 이라 넘치면 시드가 중간에 죽는다 —
        실제로 TRUNCATE 된 뒤 INSERT 가 실패해 표가 **빈 채로** 남았다."""
        import re
        sql = (C.DB_SEED / 'S1__mode_capacity.sql').read_text(encoding='utf-8')
        for m in re.finditer(r"^ \('[A-Z_]+'.*?'([^']*)'\),?$", sql, re.M):
            assert len(m.group(1)) <= 150, f'{len(m.group(1))}자: {m.group(1)[:40]}…'
