"""노선 이용자 ÷ 역 승하차 합 — 편익의 users_per_ridership 을 서울교통공사 자료 하나로 재는가."""

import pandas as pd
import pytest

import seoul_line_users as U


def test_이용자_비율은_승차와_환승유입을_승차의_두_배로_나눈다():
    df = pd.DataFrame({'호선': ['2호선', '2호선', '8호선'], '승객유형': ['일반', '어린이', '일반'],
                       '승차인원': [90, 10, 50], '환승유입인원': [40, 10, 50]})
    got = U.line_ratios(df).set_index('호선').ratio.to_dict()
    # 2호선 (100 + 50) / 200, 8호선 (50 + 50) / 100
    assert got == {'2호선': pytest.approx(0.75), '8호선': pytest.approx(1.0)}


def test_하차_승차_비는_시간대_열을_모두_더해_잰다():
    df = pd.DataFrame({'호선': ['1호선', '1호선'], '승하차구분': ['승차', '하차'],
                       '06시이전': [10, 9], '06-07시간대': [90, 93], '24시이후': [0, 0]})
    assert U.alight_over_board(df) == {'1호선': pytest.approx(1.02)}
