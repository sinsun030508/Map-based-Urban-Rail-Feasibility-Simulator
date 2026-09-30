"""
수요 회귀 — 역 주변 인구로 일 이용객을 추정한다.

입력  data/build/station_population.csv  (sgis_population.py 산출)
출력  db/seed/S6__demand_model.sql        계수 + 진단값

모델
  log(일이용객) = a + b1·log(인구) + b2·log(종사자) + b3·log(환승노선수) + b4·도심거리
  로그를 쓰는 이유: 이용객과 인구 모두 한쪽으로 크게 치우쳐 있어
  선형으로 맞추면 강남·잠실 같은 상위 역이 계수를 끌고 간다.

  인구만 쓰면 R2 0.32, 교차검증 1.97배로 쓸 수 없었다.
  서울역·을지로입구처럼 낮에만 붐비는 역이 1/10 로 과소추정됐기 때문이다.
  종사자(주간인구 대리)와 환승 노선 수를 넣어 그 편차를 잡는다.

한계 (보고서에 그대로 옮길 것)
  - 서울 자료다. 지방은 인구 대비 이용률이 낮아 그대로 쓰면 과대추정이 난다
  - 거주 인구만 센다. 강남·종로처럼 주간인구가 많은 곳은 크게 빗나간다.
    그 편차가 이 모델의 가장 큰 오차원이다
  - 환승·급행 정차 여부, 버스 연계, 주차장 유무를 변수에 넣지 않았다
  - 교통카드 기준이라 현금 이용은 빠진다
"""

import sys
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'data' / 'build'
DB_SEED = ROOT / 'db' / 'seed'
MIN_POPULATION = 100     # 인구가 거의 없는 역(공항·차량기지 인접)은 회귀에서 뺀다


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


def load():
    df = pd.read_csv(BUILD / 'station_population.csv')
    df = df[(df.population_1km >= MIN_POPULATION) & (df.daily_total > 0)].copy()
    # 한 역이 여러 노선에 걸치면 이용객이 중복 집계된다 — 역 단위로 합친다
    df = (df.groupby(['station_name', 'latitude', 'longitude'], as_index=False)
            .agg(population_1km=('population_1km', 'max'),
                 workers_dong=('workers_dong', 'max'),
                 transfer_lines=('transfer_lines', 'max'),
                 cbd_distance_km=('cbd_distance_km', 'first'),
                 daily_total=('daily_total', 'sum'),
                 line_name=('line_name', 'first')))
    df['workers_dong'] = df.workers_dong.fillna(0)
    return df


FEATURES = [
    ('log(인구)', lambda d: np.log(d.population_1km.values)),
    ('log(종사자)', lambda d: np.log(d.workers_dong.values + 1)),
    ('log(환승노선)', lambda d: np.log(d.transfer_lines.values)),
    ('도심거리km', lambda d: d.cbd_distance_km.values),
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


def to_sql(beta, se, r2, loo, n, names, path):
    lines = [
        'SET NAMES utf8mb4;',
        '',
        '-- =====================================================',
        '-- S6: 수요 추정 계수 (자동 생성 — etl/demand_model.py)',
        '-- log(일이용객) = ' + ' + '.join(
            [f'{beta[0]:.4f}'] + [f'{b:.4f}·{n}' for n, b in zip(names[1:], beta[1:])]),
        f'-- 표본 {n}개 역, R2 {r2:.3f}, 교차검증 {np.exp(loo):.2f}배',
        '-- 서울 자료다. 지방은 이용률이 낮아 과대추정이 난다.',
        '-- 거주 인구만 세므로 주간인구가 많은 업무지구는 크게 빗나간다.',
        '-- =====================================================',
        '',
        'DELETE FROM benefit_parameter WHERE param_name LIKE \'demand_%\';',
        '',
    ]
    keys = ['demand_intercept', 'demand_pop_elasticity', 'demand_worker_elasticity',
            'demand_transfer_elasticity', 'demand_cbd_slope']
    values = [(keys[i], beta[i], '계수', f'표준오차 {se[i]:.3f}')
              for i in range(len(beta))]
    values.append(('demand_loo_error', float(np.exp(loo)), '배', '교차검증 평균 오차'))
    for name, value, unit, source in values:
        lines.append(
            'INSERT INTO benefit_parameter (param_name, value, unit, source) '
            f"VALUES ('{name}', {value:.4f}, '{unit}', '{source}');")
    Path(path).write_text('\n'.join(lines) + '\n', encoding='utf-8')


def main():
    df = load()
    print(f'표본 {len(df)}개 역')
    print()

    # 변수를 하나씩 더해 가며 무엇이 실제로 기여하는지 본다
    print('[변수를 더해 가며]')
    for upto in range(1, len(FEATURES) + 1):
        _, _, r2, loo, names = fit(df, upto)
        print(f'  {" + ".join(names[1:]):<42} R2 {r2:.3f}  교차검증 {np.exp(loo):.2f}배')

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
    print('\n[가장 과소추정 — 주간인구가 많은 곳일 것]')
    print(df.nsmallest(5, 'ratio')[
        ['station_name', 'population_1km', 'daily_total', 'predicted', 'ratio']]
        .to_string(index=False, float_format=lambda v: f'{v:,.0f}'))
    print('\n[가장 과대추정]')
    print(df.nlargest(5, 'ratio')[
        ['station_name', 'population_1km', 'daily_total', 'predicted', 'ratio']]
        .to_string(index=False, float_format=lambda v: f'{v:,.0f}'))

    to_sql(beta, se, r2, loo, len(df), names, DB_SEED / 'S6__demand_model.sql')
    print(f'\n{DB_SEED / "S6__demand_model.sql"} 생성')


if __name__ == '__main__':
    sys.stdout.reconfigure(errors='replace')
    main()
