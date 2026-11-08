"""노선 단위 수요 회귀 — 학습이 백엔드와 같은 방식으로 세는가."""

import numpy as np
import pytest
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


def test_BRT_배수는_실측_승차_두_배를_예측으로_나눈_값이다():
    # 예측 = exp(0 + 1·ln 1000 + 0·ln(1)) = 1000. 승차 175명/일 → 승하차 환산 350 → 0.35
    beta = np.array([0.0, 1.0, 0.0, -0.8])   # 경전철 항은 BRT 예측에 쓰지 않는다
    assert D.brt_factor(beta, population=1000, workers=0, boardings=175) == pytest.approx(0.35)


def test_S6_에_BRT_배수가_들어간다(tmp_path):
    out = tmp_path / 'S6.sql'
    D.to_sql(np.array([-2.0, 0.5, 0.7, -0.8]), np.array([1, 0.1, 0.1, 0.3]), 0.9, 0.3, 26,
             ['절편', 'log(인구)', 'log(종사자)', '경전철'], out, brt=0.3521)
    assert "('demand_brt_factor', 0.3521, '배'" in out.read_text(encoding='utf-8')


def test_수도권_밖_노선은_역_좌표는_역_목록에서_승하차_합은_표에서_가져온다():
    st = pd.DataFrame({'line_name': ['부산 도시철도 4호선'] * D.MIN_STATIONS + ['짧은선'] * 2,
                       'latitude': 35.2, 'longitude': 129.1, 'daily_total': 0.0})
    totals = pd.DataFrame({'line_name': ['부산 도시철도 4호선', '짧은선', '좌표없는선'], 'daily_total': [54988, 10, 10]})
    c = cells([(35.2, 129.1, 100.0)])
    got = D.regional_lines(st, c, c, totals)
    assert got.line_name.tolist() == ['부산 도시철도 4호선']      # 역이 모자라거나 좌표가 없는 노선은 뺀다
    assert got.daily_total.tolist() == [54988] and got.light_rail.tolist() == [True]
    assert got.population.tolist() == [100]


def test_학습용_역_목록에_없는_노선은_역_마스터에서_좌표를_가져온다():
    st = pd.DataFrame({'line_name': ['2호선'], 'latitude': [37.5], 'longitude': [127.0], 'daily_total': [1.0]})
    master = pd.DataFrame({'line_name': ['2호선', '의정부선', '의정부선'], 'latitude': [1.0, 37.7, 37.8],
                           'longitude': [1.0, 127.0, 127.1]})
    got = D.with_master_coords(st, master)
    assert sorted(got[got.line_name == '의정부선'].latitude) == [37.7, 37.8]
    assert got[got.line_name == '2호선'].latitude.tolist() == [37.5]       # 이미 있는 노선은 안 덮는다


def test_역_마스터의_연장_구간은_본선_이름으로_합친다():
    st = pd.DataFrame({'line_name': ['2호선'], 'latitude': [37.5], 'longitude': [127.0], 'daily_total': [1.0]})
    master = pd.DataFrame({'line_name': ['신분당선', '신분당선(연장)', '신분당선(연장2)'], 'latitude': [37.4, 37.3, 37.5],
                           'longitude': [127.1, 127.1, 127.0]})
    got = D.with_master_coords(st, master)
    # 연장(미금~광교)·연장2(신사~신논현) 모두 KRIC 에 승하차가 있어(경기철도·새서울철도) 한 노선으로 센다
    assert sorted(got[got.line_name == '신분당선'].latitude) == [37.3, 37.4, 37.5]
