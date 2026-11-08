"""
수요 회귀 — 노선 둘레 인구·종사자로 노선의 일 이용객을 추정한다.

입력  data/build/station_population.csv  노선·역·일 이용객 (sgis_population.py 산출) — 수도권 노선과 전국 역 좌표
      data/build/regional_line_ridership.csv  수도권 밖 노선 일평균 승하차 (regional_ridership.py 산출)
      data/build/census_population.csv   집계구 인구
      data/build/dong_workers.csv        행정동 종사자
출력  db/seed/S6__demand_model.sql        계수 + 진단값

모델
  log(일이용객) = a + b1·log(인구) + b2·log(종사자) + b3·경전철   — 표본은 운영 노선
  인구·종사자는 **그 노선의 역 둘레 1km 안을 겹침 없이** 합친 값이다. 백엔드가 그린 선을 세는 방식
  (`PopulationIndex`)과 같아야 학습과 예측이 같은 것을 본다.

왜 노선 단위인가 (research-log 「노선 인구는 꼭짓점 둘레뿐이다」)
  역 단위로 학습해 노선에 쓰면 입력 단위가 맞지 않는다. 선 전체를 넣으면 학습 범위 밖이고, 역마다 돌려 더하면
  탄력성이 0.43 이라 같은 인구를 역 여러 개로 쪼갤수록 합이 커져 서울시청~잠실 BRT 가 B/C 23 이 됐다.
  노선 단위는 탄력성 합이 약 1 이라 그 문제가 없고, 교차검증도 역 단위 1.75배보다 낫다.
  경전철은 0/1 변수로 따로 둔다 — 같은 인구에서 경전철 이용객이 약 절반이다(표본 밖 부산 4호선도 같은 도시
  중전철보다 약 1.8배 과대, research-log 「승하차 합은 통행 두 번이다」). 넣지 않으면 경전철 두 노선이 다른 계수를 끌어당긴다.
  트램은 국내 운영 노선이 없어 보정하지 않는다.
  BRT 는 회귀에 넣지 않고 **배수 하나**로 맞춘다(`brt_factor`) — 세종 BRT 축(B1 대전역~오송역 정류장 둘레)의
  모델 예측과 행복청 2024 축 이용객(B1+B2+B4 승차 ×2)의 비. 노선별로 보면 4.8~8.5배 과대지만 같은 길을 여러 노선이
  나눠 타므로 축 단위로 잰다(2.8배, 11-08 사용자 결정). 같은 지역의 대전 1호선은 1.12배로 맞아 지역 탓이 아니다.
  도심거리는 넣지 않았다 — 종사자를 넣으면 뜻이 없고(표준오차보다 작다), 지방·장거리에서 값을 크게 깎는다.

한계 (보고서에 그대로 옮길 것)
  - 표본이 노선 수만큼(35개)이다 — 수도권 26 + 대전·부산·대구·광주 9. 장거리·다른 지역은 외삽이다
  - 일 이용객 합에 환승과 구간 밖 통행이 섞인다
  - 학습은 역 둘레, 예측은 선 전체 둘레다. 역이 드문 선은 예측 입력이 조금 크다
  - 교통카드 기준이라 현금 이용은 빠진다
"""

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'data' / 'build'
BRT_CACHE = ROOT / 'data' / 'cache' / 'brt'   # sejong_brt_speed.py 가 받아 둔 BIS 선형·정류장
DB_SEED = ROOT / 'db' / 'seed'
MIN_STATIONS = 5      # 역이 이보다 적은 노선은 자료가 잘린 것이다
RADIUS_KM = 1.0       # 백엔드 CostCalculator.BUFFER_KM 과 같다
# 세종 BRT 축 — 행복청 2024: 연간 1,203만 명 중 B1 36.6%·B2 25.0%, B4 100만 명(뉴시스 2025-02-03 보도).
# 버스 「이용객」 은 승차 집계라 ×2 해 모델의 단위(역 승하차 합)에 맞춘다. 정류장은 B1(대전역~오송역) 선형
BRT_AXIS_SHAPE = '187000003'
BRT_AXIS_BOARDINGS = (12_030_000 * (0.366 + 0.250) + 1_000_000) / 365
# 경전철 노선(운영현황의 수단 구분). 표본에 들어오는 것은 이용객 자료가 있는 우이신설선·신림선뿐이다
LIGHT_RAIL = {'우이신설선', '신림선', '부산김해경전철', '의정부경전철', '용인경전철', '에버라인', '김포골드라인',
              '부산 도시철도 4호선', '대구 도시철도 3호선'}


def ols(X, y):
    beta, *_ = np.linalg.lstsq(X, y, rcond=None)
    resid = y - X @ beta
    dof = len(y) - X.shape[1]
    cov = (resid @ resid / dof) * np.linalg.pinv(X.T @ X)
    return beta, np.sqrt(np.diag(cov))


def loo_error(X, y):
    errs = []
    for i in range(len(y)):
        keep = np.arange(len(y)) != i
        beta, *_ = np.linalg.lstsq(X[keep], y[keep], rcond=None)
        errs.append(abs(X[i] @ beta - y[i]))
    return float(np.mean(errs))


def covered(cells, points):
    """점들 둘레 1km 안의 값을 겹침 없이 합친다. 같은 칸은 역이 몇 개든 한 번만 센다"""
    lat, lng = cells.latitude.values, cells.longitude.values
    seen = np.zeros(len(cells), bool)
    for a, b in points:
        seen |= np.hypot((lat - a) * 111.0, (lng - b) * 111.0 * np.cos(np.radians(a))) <= RADIUS_KM
    return float(cells.value.values[seen].sum())


def lines(stations, census, workers):
    rows = []
    for name, g in stations[stations.daily_total > 0].groupby('line_name'):
        if len(g) < MIN_STATIONS:
            continue
        points = list(zip(g.latitude, g.longitude))
        rows.append({'line_name': name, 'stations': len(g), 'population': covered(census, points),
                     'workers': covered(workers, points), 'daily_total': float(g.daily_total.sum()),
                     'light_rail': name in LIGHT_RAIL})
    df = pd.DataFrame(rows)
    return df[df.population > 0].reset_index(drop=True)


def regional_lines(stations, census, workers, totals):
    """수도권 밖 노선 — 역 좌표는 역 목록에서, 일평균 승하차 합은 공사 자료 표에서 가져온다"""
    rows = []
    for _, t in totals.iterrows():
        g = stations[stations.line_name == t.line_name]
        if len(g) < MIN_STATIONS:
            continue
        points = list(zip(g.latitude, g.longitude))
        rows.append({'line_name': t.line_name, 'stations': len(g), 'population': covered(census, points),
                     'workers': covered(workers, points), 'daily_total': float(t.daily_total),
                     'light_rail': t.line_name in LIGHT_RAIL})
    return pd.DataFrame(rows, columns=['line_name', 'stations', 'population', 'workers', 'daily_total', 'light_rail'])


def cells_of():
    census = pd.read_csv(BUILD / 'census_population.csv', encoding='utf-8-sig')
    census = census.dropna(subset=['latitude', 'longitude', 'population']).rename(columns={'population': 'value'})
    workers = pd.read_csv(BUILD / 'dong_workers.csv', encoding='utf-8-sig')
    workers = workers.dropna(subset=['latitude', 'longitude', 'workers']).rename(columns={'workers': 'value'})
    return census, workers


def load():
    census, workers = cells_of()
    stations = pd.read_csv(BUILD / 'station_population.csv')
    df = lines(stations, census, workers)
    regional = BUILD / 'regional_line_ridership.csv'
    if not regional.exists():
        print(f'[주의] {regional.name} 이 없다 — regional_ridership.py 를 먼저. 수도권 노선만으로 배운다')
        return df
    more = regional_lines(stations, census, workers, pd.read_csv(regional))
    return pd.concat([df, more[more.population > 0]], ignore_index=True)


FEATURES = [
    ('log(인구)', lambda d: np.log(d.population.values)),
    ('log(종사자)', lambda d: np.log(d.workers.values + 1)),
    ('경전철', lambda d: d.light_rail.values.astype(float)),
]


def design(df, upto):
    cols = [np.ones(len(df))] + [f(df) for _, f in FEATURES[:upto]]
    names = ['절편'] + [n for n, _ in FEATURES[:upto]]
    return np.column_stack(cols), names


def fit(df, upto=len(FEATURES)):
    X, names = design(df, upto)
    y = np.log(df.daily_total.values.astype(float))
    beta, se = ols(X, y)
    r2 = 1 - ((y - X @ beta) ** 2).sum() / ((y - y.mean()) ** 2).sum()
    return beta, se, r2, loo_error(X, y), names


def brt_factor(beta, population, workers, boardings):
    """BRT 실측 ÷ 모델 예측. 예측은 경전철 항 없는 기준값이다(BRT 는 경전철이 아니다)"""
    predicted = np.exp(beta[0] + beta[1] * np.log(population) + beta[2] * np.log(workers + 1))
    return float(2 * boardings / predicted)


def brt_axis(census, workers):
    """세종 BRT 축 정류장 둘레 인구·종사자. 캐시가 없으면 None — BRT 배수를 쓰지 않는다"""
    path = BRT_CACHE / f'shape_{BRT_AXIS_SHAPE}.json'
    if not path.exists():
        return None
    shape = json.loads(path.read_text(encoding='utf-8'))
    stops = sorted({tuple(shape['points'][s['idx']]) for s in shape['stops']})
    return covered(census, stops), covered(workers, stops), len(stops)


def to_sql(beta, se, r2, loo, n, names, path, brt=None):
    lines_ = [
        'SET NAMES utf8mb4;',
        '',
        '-- =====================================================',
        '-- S6: 수요 추정 계수 (자동 생성 — etl/demand_model.py)',
        '-- log(일이용객) = ' + f'{beta[0]:.4f}' + ''.join(
            f' {"-" if b < 0 else "+"} {abs(b):.4f}·{n}' for n, b in zip(names[1:], beta[1:])),
        f'-- 표본 {n}개 노선, R2 {r2:.3f}, 교차검증 {np.exp(loo):.2f}배',
        '-- 인구·종사자는 노선 둘레 1km 를 겹침 없이 합친 값이다. 경전철 항은 경전철 행에만 쓴다.',
        '-- 수도권 노선과 대전·부산·대구·광주 도시철도다. 장거리·다른 지역은 외삽이다.',
        '-- =====================================================',
        '',
        'DELETE FROM benefit_parameter WHERE param_name LIKE \'demand_%\';',
        '',
    ]
    keys = ['demand_intercept', 'demand_pop_elasticity', 'demand_worker_elasticity', 'demand_light_rail_coef']
    values = [(keys[i], beta[i], '계수', f'표준오차 {se[i]:.3f}')
              for i in range(len(beta))]
    values.append(('demand_loo_error', float(np.exp(loo)), '배', '교차검증 평균 오차'))
    if brt is not None:
        values.append(('demand_brt_factor', brt, '배', '세종 BRT 축 실측(행복청 2024) ÷ 모델 예측 — BRT 행에만'))
    for name, value, unit, source in values:
        lines_.append(
            'INSERT INTO benefit_parameter (param_name, value, unit, source) '
            f"VALUES ('{name}', {value:.4f}, '{unit}', '{source}');")
    Path(path).write_text('\n'.join(lines_) + '\n', encoding='utf-8')


def main():
    df = load()
    print(f'표본 {len(df)}개 노선 — 인구 {df.population.min():,.0f}~{df.population.max():,.0f}, '
          f'종사자 {df.workers.min():,.0f}~{df.workers.max():,.0f}')
    print()

    print('[변수를 더해 가며]')
    for upto in range(1, len(FEATURES) + 1):
        _, _, r2, loo, names = fit(df, upto)
        print(f'  {" + ".join(names[1:]):<30} R2 {r2:.3f}  교차검증 {np.exp(loo):.2f}배')

    beta, se, r2, loo, names = fit(df)
    print()
    print('[최종 모델]')
    for name, b, s_ in zip(names, beta, se):
        mark = '' if abs(b) > 2 * s_ else '   (오차 큼)'
        print(f'  {name:<12} {b:8.3f} ± {s_:.3f}{mark}')
    print(f'  R2 {r2:.3f} / 교차검증 {np.exp(loo):.2f}배')

    X, _ = design(df, len(FEATURES))
    df['predicted'] = np.exp(X @ beta)
    df['ratio'] = (df.predicted / df.daily_total).round(2)
    show = ['line_name', 'stations', 'population', 'workers', 'daily_total', 'predicted', 'ratio']
    print('\n[가장 과소추정]')
    print(df.nsmallest(4, 'ratio')[show].to_string(index=False, float_format=lambda v: f'{v:,.2f}'))
    print('\n[가장 과대추정]')
    print(df.nlargest(4, 'ratio')[show].to_string(index=False, float_format=lambda v: f'{v:,.2f}'))

    axis = brt_axis(*cells_of())
    brt = None
    if axis is None:
        print('\n[BRT] 세종 BRT 선형 캐시가 없다 — sejong_brt_speed.py 를 먼저. BRT 배수 없이 쓴다')
    else:
        pop, wk, n_stops = axis
        brt = brt_factor(beta, pop, wk, BRT_AXIS_BOARDINGS)
        print(f'\n[BRT] 세종 축 정류장 {n_stops}  인구 {pop:,.0f}  종사자 {wk:,.0f}  '
              f'실측 승차 {BRT_AXIS_BOARDINGS:,.0f}/일 → 배수 {brt:.4f} (예측이 {1 / brt:.2f}배 과대)')
    to_sql(beta, se, r2, loo, len(df), names, DB_SEED / 'S6__demand_model.sql', brt=brt)
    print(f'\n{DB_SEED / "S6__demand_model.sql"} 생성')


if __name__ == '__main__':
    sys.stdout.reconfigure(errors='replace')
    main()
