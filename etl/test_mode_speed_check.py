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
