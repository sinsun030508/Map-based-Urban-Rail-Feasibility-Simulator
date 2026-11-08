"""노선 단위 수요 회귀 — 학습이 백엔드와 같은 방식으로 세는가."""

import numpy as np
import pandas as pd

import demand_model as D


def cells(rows):
    return pd.DataFrame(rows, columns=['latitude', 'longitude', 'value'])


def test_겹치는_역이_같은_집계구를_두_번_세지_않는다():
    c = cells([(37.5, 127.0, 100.0)])
    # 두 역 모두 1km 안 — 한 번만 센다. 역 단위로 더하면 200 이 되어 노선 수요가 부푼다
    assert D.covered(c, [(37.5, 127.0), (37.5005, 127.0)]) == 100


def test_1km_밖은_세지_않는다():
    c = cells([(37.5, 127.0, 100.0), (37.52, 127.0, 7.0)])
    assert D.covered(c, [(37.5, 127.0)]) == 100


def test_역이_적은_노선은_표본에서_뺀다():
    st = pd.DataFrame({'line_name': ['가'] * D.MIN_STATIONS + ['나'] * (D.MIN_STATIONS - 1),
                       'latitude': 37.5, 'longitude': 127.0, 'daily_total': 1000.0})
    c = cells([(37.5, 127.0, 100.0)])
    assert D.lines(st, c, c).line_name.tolist() == ['가']


def test_이용객이_없는_역은_세지_않는다():
    st = pd.DataFrame({'line_name': ['가'] * (D.MIN_STATIONS + 1), 'latitude': 37.5, 'longitude': 127.0,
                       'daily_total': [1000.0] * D.MIN_STATIONS + [0.0]})
    c = cells([(37.5, 127.0, 100.0)])
    row = D.lines(st, c, c).iloc[0]
    assert row.daily_total == 1000.0 * D.MIN_STATIONS
    assert row.stations == D.MIN_STATIONS


def test_회귀는_탄력성과_경전철_배수를_되찾는다():
    rng = np.random.default_rng(1)
    pop, wk = rng.uniform(1e5, 2e6, 40), rng.uniform(5e4, 2e6, 40)
    light = np.arange(40) % 8 == 0
    df = pd.DataFrame({'population': pop, 'workers': wk, 'light_rail': light,
                       'daily_total': np.exp(-2 + 0.5 * np.log(pop) + 0.7 * np.log(wk + 1) - 0.8 * light)})
    beta, _, r2, loo, _ = D.fit(df)
    assert np.allclose(beta, [-2, 0.5, 0.7, -0.8], atol=1e-6)
    assert r2 > 0.999 and loo < 1e-6


def test_경전철_노선을_가른다():
    st = pd.DataFrame({'line_name': ['우이신설선'] * D.MIN_STATIONS + ['2호선'] * D.MIN_STATIONS,
                       'latitude': 37.5, 'longitude': 127.0, 'daily_total': 1000.0})
    c = cells([(37.5, 127.0, 100.0)])
    got = D.lines(st, c, c).set_index('line_name').light_rail.to_dict()
    assert got == {'우이신설선': True, '2호선': False}
