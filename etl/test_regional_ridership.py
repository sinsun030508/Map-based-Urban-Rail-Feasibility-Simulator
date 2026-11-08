"""수도권 밖 도시철도 승하차 — 세 공사의 파일 모양이 다 달라 파서가 노선을 바르게 가르는가."""

import pandas as pd
import pytest

import regional_ridership as R


def test_대전은_월별_승차_하차를_모두_더해_기간_일수로_나눈다():
    df = pd.DataFrame({'역번호': [1, 2], '역명': ['판암', '신흥'],
                       '1월 승차': [310, 31], '1월 하차': [0, 31], '2월 승차': [0, 0], '2월 하차': [28, 28]})
    assert R.daejeon(df, days=59) == {'대전 도시철도 1호선': pytest.approx((310 + 31 + 31 + 28 + 28) / 59)}


def test_부산은_호선별로_승차_하차_일평균을_더한다():
    df = pd.DataFrame({'호선': [1, 1, 4], '역명': ['a', 'b', 'c'],
                       '승차 일평균(명)': [10, 20, 5], '하차 일평균(명)': [11, 19, 6]})
    assert R.busan(df) == {'부산 도시철도 1호선': 60, '부산 도시철도 4호선': 11}


def test_대구는_역_열을_노선_구간으로_가르고_최근_12개월만_쓴다():
    cols = ['설화명곡', '하양', '문양', '영남대', '칠곡경대병원', '용지']
    rows = []
    for ym in range(1, 14):          # 13개월 — 첫 달은 빠져야 한다
        y, m = 2025 + (ym - 1) // 12, (ym - 1) % 12 + 1
        rows.append([y, m] + [100 if ym > 1 else 99999] * 6)
    on = pd.DataFrame(rows, columns=['년', '월'] + cols)
    off = on.copy()
    got = R.daegu(on, off)
    # 12개월 × 역 2개 × (승차 100 + 하차 100) ÷ 365
    want = 12 * 2 * 200 / 365
    assert got == {'대구 도시철도 1호선': pytest.approx(want), '대구 도시철도 2호선': pytest.approx(want),
                   '대구 도시철도 3호선': pytest.approx(want)}


def test_대구_승차와_하차의_기간이_다르면_겹치는_달만_쓴다():
    cols = ['설화명곡', '하양', '문양', '영남대', '칠곡경대병원', '용지']
    on = pd.DataFrame([[2026, m] + [10] * 6 for m in range(1, 13)], columns=['년', '월'] + cols)
    off = pd.DataFrame([[2026, m] + [10] * 6 for m in range(1, 12)], columns=['년', '월'] + cols)   # 12월 없음
    with pytest.raises(ValueError, match='12개월'):
        R.daegu(on, off)
