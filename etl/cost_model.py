"""
비용 회귀 — reference_line.csv 에서 수단·구조별 건설비 계수를 뽑아
db/seed/S5__cost_standard.sql 을 만든다.

왜 두 단계인가
  실제 사업비는 연장에 비례하지 않는다 (짧은 노선일수록 km당 단가가 튄다).
  그래서 1단계에서 log-log 회귀로 규모의 경제를 잡고,
  2단계에서 그 곡선을 5~40km 구간에서 직선으로 근사해
  cost_standard 스키마(고정비 + 연장×단가 + 역수×역당단가)에 맞춘다.

역수 처리
  선형으로 넣으면 연장과 겹쳐 계수가 음수로 튄다. log 로 넣으면 탄력성 0.312±0.128 로
  분리된다 (A등급 27건, R2 0.803 → 0.846). 표본의 역수/표준역수 비가 0.21~1.91 로
  퍼져 있어 분리가 가능하다.

한계 (보고서에 그대로 옮길 것)
  - 표본 63건, 그중 지하비율이 있는 건은 27건뿐이고 그 값도 전부 추정치다
  - 역수 탄력성은 도시철도 27건에서 뽑아 BRT·복선전철에도 그대로 쓴다
  - 기준연도가 명시된 건이 2건뿐이라 물가 환산분(total_cost_2025)도 대부분 추정 기준이다
"""

import sys
import numpy as np
import pandas as pd
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'data' / 'build'
DB_SEED = ROOT / 'db' / 'seed'

# 구조별 지하 비율 대표값 — 계수를 적용할 때 쓴다
STRUCTURE_UG = {'UNDERGROUND': 1.0, 'ELEVATED': 0.1, 'AT_GRADE': 0.0}

# 수단별로 실제로 존재하는 구조만 만든다 (트램은 노면, BRT 는 지하로 짓지 않는다)
MODE_STRUCTURES = {
    'HEAVY_METRO': ['UNDERGROUND', 'ELEVATED'],
    'LIGHT_RAIL':  ['UNDERGROUND', 'ELEVATED'],
    'TRAM':        ['AT_GRADE'],
    'BRT_HIGH':    ['AT_GRADE', 'ELEVATED'],
    'BRT_LOW':     ['AT_GRADE'],
    'DOUBLE_ELEC': ['UNDERGROUND', 'ELEVATED'],
}

FIT_RANGE_KM = np.arange(5, 41, 1.0)      # 직선 근사 구간
STATION_RATIOS = [0.6, 0.8, 1.0, 1.2, 1.4]  # 표준 역간격 대비 역수 비율

# 역간격 (km) — 도시철도는 운영현황 중앙값, 복선전철은 rail_station_seed 로 역수를 채운
# A등급 11건의 중앙값 4.98 (GTX 계열이 6~8km, 일반 광역철도가 1.7~3.6km 로 폭이 넓다)
SPACING = {'HEAVY_METRO': 1.06, 'LIGHT_RAIL': 1.06, 'TRAM': 0.86,
           'BRT_HIGH': 1.16, 'BRT_LOW': 1.16, 'DOUBLE_ELEC': 4.98}


def load_sample(grade=('A', 'B')):
    df = pd.read_csv(BUILD / 'reference_line.csv')
    m = df[(df.data_grade.isin(grade))
           & (df.cost_status == 'DISCLOSED')
           & (~df.is_outlier)
           & df.total_cost_2025.notna()
           & df.length_km.notna()
           & df.mode_type.notna()].copy()
    return m


def ols(X, y):
    """계수와 표준오차를 함께 돌려준다 — 계수를 믿을 수 있는지 봐야 하므로."""
    beta, *_ = np.linalg.lstsq(X, y, rcond=None)
    resid = y - X @ beta
    dof = len(y) - X.shape[1]
    sigma2 = resid @ resid / dof
    cov = sigma2 * np.linalg.pinv(X.T @ X)
    return beta, np.sqrt(np.diag(cov))


def loo_error(X, y):
    errs = []
    for i in range(len(y)):
        k = np.arange(len(y)) != i
        beta, *_ = np.linalg.lstsq(X[k], y[k], rcond=None)
        errs.append(abs(X[i] @ beta - y[i]))
    return float(np.mean(errs))


def design(df, modes, with_ug, with_stations=False):
    """절편 + log(연장) + (지하비율) + (log 역수) + 수단 더미"""
    cols = [np.ones(len(df)), np.log(df.length_km.values)]
    names = ['절편', 'log(연장)']
    if with_ug:
        cols.append(df.underground_ratio.values)
        names.append('지하비율')
    if with_stations:
        cols.append(np.log(df.station_count.values.astype(float)))
        names.append('log(역수)')
    for mode in modes[1:]:                      # 첫 수단은 기준으로 흡수
        cols.append((df.mode_type == mode).astype(float).values)
        names.append(f'수단={mode}')
    return np.column_stack(cols), names


def fit_model(df, with_ug, with_stations=False):
    modes = sorted(df.mode_type.unique())
    X, names = design(df, modes, with_ug, with_stations)
    y = np.log(df.total_cost_2025.values.astype(float))
    beta, se = ols(X, y)
    pred = X @ beta
    r2 = 1 - ((y - pred) ** 2).sum() / ((y - y.mean()) ** 2).sum()
    return {'modes': modes, 'names': names, 'beta': beta, 'se': se,
            'r2': r2, 'loo': loo_error(X, y), 'n': len(df),
            'with_ug': with_ug, 'with_stations': with_stations}


# 지하비율이 있는 표본은 도시철도(지하철·경전철·트램)뿐이다.
# BRT·복선전철은 전체 표본 모델로 예측하고, 그 예측이 아래 구조에 해당한다고 본다.
BASE_UG = {'BRT_HIGH': 0.0, 'BRT_LOW': 0.0, 'DOUBLE_ELEC': 0.1, 'SINGLE_ELEC': 0.1}


def predict_cost(model, mode, ug, length_km, stations=None,
                 ug_coef=None, station_coef=None):
    """
    log-log 모델로 총사업비(억원) 예측.
    모델에 없는 항(지하비율·역수)은 밖에서 계수를 받아 곱해 준다.
    """
    beta, modes = model['beta'], model['modes']
    log_cost = beta[0] + beta[1] * np.log(length_km)
    i = 2
    if model['with_ug']:
        log_cost = log_cost + beta[i] * ug
        i += 1
    elif ug_coef is not None:
        log_cost = log_cost + ug_coef * (ug - BASE_UG.get(mode, 0.0))
    if model['with_stations']:
        log_cost = log_cost + beta[i] * np.log(stations)
        i += 1
    elif station_coef is not None and stations is not None:
        # 표준 역간격일 때를 기준으로, 역수가 그보다 많고 적은 만큼만 보정
        log_cost = log_cost + station_coef * np.log(stations / (length_km / SPACING[mode]))
    for m in modes[1:]:
        if m == mode:
            log_cost = log_cost + beta[i]
        i += 1
    return np.exp(log_cost)


def fit_additive(model, mode, ug, ug_coef=None, station_coef=None):
    """
    log-log 예측면을 '고정비 + 연장×단가 + 역수×역당단가' 로 근사한다.
    연장만 훑으면 역수가 연장에 묶여 두 계수가 분리되지 않으므로,
    표준 역간격의 0.6~1.4배까지 역수를 흔든 격자에서 맞춘다.
    """
    spacing = SPACING[mode]
    L, N, cost = [], [], []
    for length in FIT_RANGE_KM:
        for ratio in STATION_RATIOS:
            stations = max(2.0, round(length / spacing * ratio))
            L.append(length)
            N.append(stations)
            cost.append(float(predict_cost(model, mode, ug, length, stations,
                                           ug_coef, station_coef)))
    L, N, cost = np.array(L), np.array(N), np.array(cost)
    X = np.column_stack([np.ones(len(L)), L, N])
    beta, _ = ols(X, cost)
    fit_r2 = 1 - ((cost - X @ beta) ** 2).sum() / ((cost - cost.mean()) ** 2).sum()
    return round(float(beta[0])), round(float(beta[1])), round(float(beta[2])), fit_r2


def report(model, label):
    print(f'\n[{label}] n={model["n"]}  R2={model["r2"]:.3f}  '
          f'LOO={model["loo"]:.3f} → 배수 오차 {np.exp(model["loo"]):.2f}배')
    for name, b, s in zip(model['names'], model['beta'], model['se']):
        mark = '' if abs(b) > 2 * s else '   (오차 큼)'
        print(f'  {name:<22} {b:8.3f} ± {s:.3f}{mark}')


def to_sql(rows, model, path):
    lines = [
        '-- =====================================================',
        '-- S5: 수단×구조별 건설비 계수 (자동 생성)',
        '-- etl/cost_model.py 가 reference_line 에서 뽑는다. 직접 수정하지 말 것.',
        f'-- 주력 모델: A등급 n={model["n"]}, R2 {model["r2"]:.3f}, 교차검증 오차 {np.exp(model["loo"]):.2f}배',
        '-- 역수 표본이 없는 수단(단선전철·개량·BRT 저급형)은 전체 표본 모델 + 구조·역수 보정',
        f'-- 역수 탄력성 {model["beta"][model["names"].index("log(역수)")]:.3f} — 역수가 표준 역간격보다 많으면 비용이 오른다',
        '-- 값은 2025년 환산 기준(억원). 단일 값이 아니라 범위로 제시할 것.',
        '-- =====================================================',
        'SET NAMES utf8mb4;',
        '',
        '-- scenario_result 가 FK 로 참조해 TRUNCATE 가 막힌다. DELETE 로 비운다',
        'DELETE FROM cost_standard;',
        '',
    ]
    for mode, structure, fixed, per_km, per_st, note in rows:
        lines.append(
            'INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, '
            'cost_per_station, base_year, is_active, source) VALUES '
            f"('{mode}', '{structure}', {fixed}, {per_km}, {per_st}, 2025, TRUE, '{note}');")
    Path(path).write_text('\n'.join(lines) + '\n', encoding='utf-8')


def main():
    # 전체 표본 — 역수가 없는 건까지 포함. 역수가 없는 수단(단선전철·개량·BRT 저급형)용
    sample = load_sample()
    m1 = fit_model(sample, with_ug=False)
    report(m1, '전체 표본 — 연장 + 수단')

    # 주력 모델 — 역수·지하비율까지 갖춘 A등급. 도시철도와 광역철도를 함께 담는다
    a_sample = load_sample(grade=('A',))
    a_sample = a_sample[a_sample.underground_ratio.notna() & a_sample.station_count.notna()]
    m2 = fit_model(a_sample, with_ug=True, with_stations=True)
    report(m2, 'A등급 — 연장 + 역수 + 지하비율 + 수단')

    ug_coef = float(m2['beta'][m2['names'].index('지하비율')])
    station_coef = float(m2['beta'][m2['names'].index('log(역수)')])
    print()
    print(f'→ 주력 모델 n={m2["n"]} (교차검증 {np.exp(m2["loo"]):.2f}배). 이 모델에 없는 수단만')
    print(f'   전체 표본 모델에 구조 보정({np.exp(ug_coef):.2f}배)과 역수 탄력성({station_coef:.3f})을 얹는다')

    rows = []
    print()
    print('[수단×구조별 계수 — 2025년 환산 억원]')
    for mode, structures in MODE_STRUCTURES.items():
        n = int((a_sample.mode_type == mode).sum())
        n_all = int((sample.mode_type == mode).sum())
        if n_all == 0:
            print(f'  {mode:<12} 표본 없음 — 건너뜀')
            continue
        use_m2 = mode in m2['modes'] and n >= 2
        model = m2 if use_m2 else m1
        for structure in structures:
            fixed, per_km, per_st, fit_r2 = fit_additive(
                model, mode, STRUCTURE_UG[structure],
                None if use_m2 else ug_coef,
                None if use_m2 else station_coef)
            note = (f'A등급 모델 n={n}' if use_m2
                    else f'전체표본 모델+보정 n={n_all} (역수 표본 없음)')
            if max(n, n_all) < 5:
                note += ' — 표본 부족, 참고용'
            rows.append((mode, structure, fixed, per_km, per_st, note))
            warn = ' (표본부족)' if max(n, n_all) < 5 else ''
            print(f'  {mode:<12} {structure:<12} 고정비 {fixed:>7,} + km당 {per_km:>6,}'
                  f' + 역당 {per_st:>5,}  (근사 R2 {fit_r2:.3f}, n={n or n_all}){warn}')

    to_sql(rows, m2, DB_SEED / 'S5__cost_standard.sql')
    print()
    print(f'{DB_SEED / "S5__cost_standard.sql"} 생성 — {len(rows)}행')


if __name__ == '__main__':
    sys.stdout.reconfigure(errors='replace')
    main()
