"""
수단별 역간격·속도 짝 검사의 규칙 고정.

이 분석은 **조용히 틀릴 수 있는 곳이 하나**다 — 노선을 이름으로만 짝지으면
다른 구간에 붙는다. 실제로 `신분당선` 33.8km 가 52.9km 행에 붙어 역간격을
1.56(실제 2.09)으로 읽었고, 그 값으로 회귀를 돌려 결론을 낼 뻔했다.

    cd etl && python -m pytest test_mode_speed_check.py
"""

import pytest

import mode_speed_check as M


class Test노선짝짓기:
    """이름이 겹치고 **연장이 맞을 때만** 짝짓는다."""

    def test_연장이_맞으면_짝짓는다(self):
        cands = [('신분당선', 33.5, 2.094)]
        hit = M.match_line('신분당선', 33.8, cands)
        assert hit is not None
        assert hit[2] == pytest.approx(2.094)

    def test_이름이_겹쳐도_연장이_다르면_버린다(self):
        """실제로 났던 오매칭. 신분당선 33.8km 가 52.9km 행에 붙었다."""
        cands = [('신분당선', 52.9, 1.511)]
        assert M.match_line('신분당선', 33.8, cands) is None

    def test_연장이_가장_가까운_것을_고른다(self):
        cands = [('신분당선', 34.5, 9.9), ('신분당선', 33.5, 2.094)]
        hit = M.match_line('신분당선', 33.8, cands)
        assert hit[1] == pytest.approx(33.5)

    def test_허용_범위_경계(self):
        # TOL 은 비율이다 — 33.8km 의 5% 는 1.69km
        assert M.match_line('X', 33.8, [('X', 33.8 * (1 + M.TOL * 0.9), 1.0)]) is not None
        assert M.match_line('X', 33.8, [('X', 33.8 * (1 + M.TOL * 1.1), 1.0)]) is None

    def test_전혀_다른_이름은_버린다(self):
        assert M.match_line('신분당선', 33.8, [('경춘선', 33.8, 1.5)]) is None

    def test_부분열_규칙은_느슨하다_연장이_막아_준다(self):
        """`'분당선' in '신분당선'` 이 참이라 이름만으로는 둘을 가리지 못한다.

        실제 자료에서는 분당선 52.9km / 신분당선 33.5km 로 연장이 크게 달라
        연장 검증이 막아 준다. **이름 규칙이 아니라 연장 검증이 실제로 일을 한다** —
        연장이 비슷한 두 노선이 생기면 이 규칙으로는 가릴 수 없다.
        """
        # 연장이 다르면 버린다 (실제 상황)
        assert M.match_line('신분당선', 33.8, [('분당선', 52.9, 1.511)]) is None
        # 연장까지 비슷하면 붙는다 — 규칙의 한계다
        assert M.match_line('신분당선', 33.8, [('분당선', 33.8, 1.5)]) is not None

    def test_빈_이름은_아무것과도_짝짓지_않는다(self):
        # 빈 문자열은 모든 문자열의 부분열이라 그냥 두면 전부 걸린다
        assert M.match_line('', 33.8, [('신분당선', 33.8, 2.094)]) is None


class Test회귀:
    def test_완전한_직선을_정확히_맞힌다(self):
        pts = [(1.0, 20.0), (2.0, 30.0), (3.0, 40.0)]
        a, b, r2 = M.fit(pts)
        assert a == pytest.approx(10.0)
        assert b == pytest.approx(10.0)
        assert r2 == pytest.approx(1.0)

    def test_x가_모두_같으면_적합하지_않는다(self):
        assert M.fit([(1.0, 20.0), (1.0, 30.0)]) is None

    def test_흩어지면_R2가_낮다(self):
        pts = [(1.0, 20.0), (2.0, 20.0), (3.0, 20.5), (4.0, 19.5)]
        _, _, r2 = M.fit(pts)
        assert r2 < 0.2


class Test이름정규화:
    def test_공백을_지운다(self):
        assert M.norm('수도권  도시철도 2호선') == '수도권도시철도2호선'

    def test_None_은_빈_문자열이_된다(self):
        # 빈 문자열은 match_line 이 따로 걸러낸다 (위 테스트 참고)
        assert M.norm(None) == ''
        assert M.norm('') == ''


class Test급행분리:
    """복선전철을 급행/일반으로 가르는 근거를 못으로 박는다.

    **가르는 근거가 속도에만 있고 비용에는 없다**는 것이 이 분리의 핵심이다.
    km당 사업비만 보면 급행 579 / 일반 1,064 로 갈리는 것처럼 보이지만,
    그 차이는 `log(역수)` 가 이미 설명한다 — 급행은 역이 적어서 싼 것이고
    "급행이라서" 싼 것이 아니다. 아래 잔차 검사가 그것을 가린다.
    """

    def test_역간격이_쌍봉이고_중앙값이_빈_틈에_떨어진다(self):
        de = M.double_elec_split()
        exp = sorted(de[de.급행].avg_spacing_km)
        nor = sorted(de[~de.급행].avg_spacing_km)
        import statistics as st
        전체 = st.median(sorted(de.avg_spacing_km))
        # 전체 중앙값이 두 집단 중앙값 **사이**에 있고 양쪽 어디에도 가깝지 않다
        assert st.median(nor) < 전체 < st.median(exp)
        assert st.median(nor) == pytest.approx(2.62, abs=0.01)
        assert st.median(exp) == pytest.approx(6.92, abs=0.01)

    def test_비용을_가를_근거를_못_본다(self):
        """|t| < 2 — 잔차가 쏠리지 않는다. 그래서 급행은 복선전철 비용 계수를 쓴다.

        **표본이 늘어 t가 2를 넘으면 이 테스트가 깨진다** — 그때는
        "이제 비용도 가를 수 있다"는 신호이니 S5 에 급행 계수를 따로 두어야 한다.
        """
        for rule in ('name', 'spacing'):
            t, _ = M.cost_residual_t(rule)
            assert abs(t) < 2, f'{rule} 기준 t={t:+.2f} — 비용을 가를 근거가 생겼다'

    def test_검출_한계를_함께_내놓는다(self):
        """**이것이 이 검정의 진짜 결론이다.**

        `|t| < 2` 를 "차이가 없다"로 읽으면 과한 주장이다. 급행 n=4 라 작은 차이는
        애초에 보이지 않는다. 한계가 1.2배(=비용 20% 차이)보다 크면, 이 검정은
        "차이 없음"의 증거가 아니라 **표본 부족의 증거**다 — 그 사실을 못으로 박는다.
        """
        t, limit = M.cost_residual_t('name')
        assert limit > 1.2, '검출 한계가 작아졌다 — 이제 "차이가 없다"고 말할 수 있다'
        assert limit == pytest.approx(1.50, abs=0.02)

    def test_급행은_속도_회귀_표본에_들어가지_않는다(self):
        """회귀 적합 구간은 역간격 0.71~2.09km 다.

        급행(6.9~7.7km)을 표본에 넣으면 포화 구간이 섞여 직선이 휘고, 그 직선으로
        "포화한다"를 보일 수 없게 된다 — 검증할 점이 표본에 들어가 버린다.

        지금은 이름이 안 맞아(`GTX-A` vs `수도권광역급행철도(A노선)`) 자연히 못
        들어오지만, **그건 가드가 아니다.** 누가 이름을 맞추면 조용히 섞인다.
        """
        fit_pairs, _ = M.load_pairs()
        assert all(p[0] not in M.SATURATED_MODES for p in fit_pairs)
        assert max(p[2] for p in fit_pairs) < M.express_spacing()

    def test_급행_속도는_두_구간_중앙값이다(self):
        """두 구간이 3.6% 안에서 맞아, 기준(시운전/운행)이 섞인 것이 값을 흔들지 않는다."""
        assert M.express_speed() == pytest.approx(91.79, abs=0.01)
        assert M.express_spacing() == pytest.approx(6.92, abs=0.01)

    def test_급행_속도는_하드코딩이_아니라_csv에서_온다(self):
        """전에 90.14 와 7.667 이 스크립트 두 곳에 박혀 있었다 — csv 를 고쳐도 안 따라온다.

        **글이 아니라 코드를 본다.** 머리말에 "전에 이 숫자가 박혀 있었다"고 적어 두는
        것은 남겨야 하므로 본문 검색으로는 가릴 수 없다. `ast` 로 실제 숫자 상수만 센다.
        """
        import ast
        tree = ast.parse((M.ROOT / 'etl' / 'mode_speed_check.py').read_text(encoding='utf-8'))
        박힌값 = {90.14, 93.43, 91.79, 7.667, 6.923}
        found = [n.value for n in ast.walk(tree)
                 if isinstance(n, ast.Constant) and isinstance(n.value, float)
                 and round(n.value, 3) in {round(v, 3) for v in 박힌값}]
        assert not found, f'급행 값이 코드에 박혀 있다: {found} — csv·reference_line 에서 읽을 것'


class TestWelch검정:
    """`cost_residual_t` 는 얇은 배선이고 통계는 `welch_t` 가 한다.

    파일을 읽는 함수는 합성 자료를 넣을 수 없어 **못으로 박을 수가 없다.**
    그래서 계산만 떼어내 여기서 가린다 — 답을 아는 자료로.
    """

    def test_똑같은_두_묶음은_t가_0이다(self):
        t, se = M.welch_t([1.0, 2.0, 3.0], [1.0, 2.0, 3.0])
        assert t == pytest.approx(0.0)
        assert se > 0

    def test_뚜렷하게_갈리면_t가_크다(self):
        """흩어짐이 작고 차이가 크면 잡아낸다."""
        t, _ = M.welch_t([10.0, 10.1, 9.9, 10.0], [20.0, 20.1, 19.9, 20.0])
        assert abs(t) > 2

    def test_차이가_같아도_흩어지면_t가_작아진다(self):
        """**표본이 작고 흩어지면 같은 차이도 안 보인다** — 급행 n=4 가 이 상황이다."""
        좁게, _ = M.welch_t([10.0, 10.1, 9.9, 10.0], [11.0, 11.1, 10.9, 11.0])
        넓게, _ = M.welch_t([8.0, 12.0, 9.0, 11.0], [9.0, 13.0, 10.0, 12.0])
        assert abs(좁게) > 2          # 차이 1.0 이 보인다
        assert abs(넓게) < 2          # 똑같은 차이 1.0 이 안 보인다

    def test_부호는_앞의_묶음이_클_때_양수다(self):
        t, _ = M.welch_t([5.0, 5.1, 4.9], [1.0, 1.1, 0.9])
        assert t > 0

    def test_검출_한계는_흩어짐과_함께_커진다(self):
        _, 좁은se = M.welch_t([10.0, 10.1, 9.9, 10.0], [11.0, 11.1, 10.9, 11.0])
        _, 넓은se = M.welch_t([8.0, 12.0, 9.0, 11.0], [9.0, 13.0, 10.0, 12.0])
        assert 넓은se > 좁은se


class Test자기출처역간격:
    """역간격을 **속도와 같은 출처에서** 뽑는다 — 복선전철이 틀렸던 바로 그 이유다.

    `rail_speed.csv` 에 역수가 있으면 그 노선 자기 `연장 ÷ 역수` 를 쓴다.
    운영사 페이지 하나가 연장·역수·소요시간을 함께 주면 셋이 서로 검산된다.
    """

    def test_역수가_있으면_자기_역간격을_쓴다(self):
        assert M._own_spacing({'length_km': '37.1', 'station_count': '33'}) == \
            pytest.approx(37.1 / 33)

    def test_역수가_없으면_None(self):
        assert M._own_spacing({'length_km': '37.1', 'station_count': ''}) is None
        assert M._own_spacing({'length_km': '37.1'}) is None

    def test_역이_하나면_역간격이_없다(self):
        """가덕도신공항(16.5km/1역)이 복선전철 중앙값을 밀어 올렸던 것과 같은 규칙."""
        assert M._own_spacing({'length_km': '16.5', 'station_count': '1'}) is None

    def test_인천1호선은_두_출처가_다르다(self):
        """운영사 37.1km/33역(검단연장 반영) vs reference_line 30.3km/30역(연장 전).

        **이걸 못 보면 노선이 조용히 자에서 빠진다** — 연장이 18% 달라 이름으로도
        안 붙어서, 전에는 "연장이 맞는 행 없음" 으로 버려지고 있었다.
        """
        diff = dict((d[0], d) for d in M.crosscheck_spacing())
        assert '인천 1호선' in diff, '두 출처 차이를 못 잡는다'
        name, own, ref, own_len, ref_len = diff['인천 1호선']
        assert own == pytest.approx(37.1 / 33, abs=0.001)
        assert ref == pytest.approx(1.010, abs=0.001)

    def test_김포가_들어와_적합_구간이_넓어졌다(self):
        """김포 골드라인 역간격 2.36 — 전에는 못 붙어 상한이 2.09 였다."""
        pairs, _ = M.load_pairs()
        names = {p[1] for p in pairs}
        assert '김포 골드라인' in names
        assert '인천 1호선' in names
        assert max(p[2] for p in pairs) > 2.3


class Test자는실측으로만:
    """설계값·시운전값은 자를 만드는 데 쓰지 않는다."""

    def test_대전2호선은_자에서_빠진다(self):
        """미개통 설계값이고, 하필 그 값이 S1 의 TRAM 속도 그 자체라 완전 순환이다."""
        pairs, skipped = M.load_pairs()
        assert '대전 2호선' not in {p[1] for p in pairs}
        why = dict(skipped)['대전 2호선']
        assert '설계' in why

    def test_빠진_것에는_이유가_붙는다(self):
        """`못 붙인 것: A, B, C` 만 찍으면 왜 빠졌는지 알 수 없다 —
        연장이 안 맞은 것과 설계값이라 뺀 것은 전혀 다른 일이다."""
        _, skipped = M.load_pairs()
        assert skipped
        assert all(isinstance(w, str) and w for _, w in skipped)
