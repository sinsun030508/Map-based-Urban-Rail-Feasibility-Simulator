"""
비용 회귀 — reference_line.csv 에서 수단·구조별 건설비 계수를 뽑아
db/seed/S5__cost_standard.sql 을 만든다.

왜 두 단계인가
  실제 사업비는 연장에 비례하지 않는다 (짧은 노선일수록 km당 단가가 튄다).
  그래서 1단계에서 log-log 회귀로 규모의 경제를 잡고,
  2단계에서 그 곡선을 5~40km 구간에서 직선으로 근사해
  cost_standard 스키마(고정비 + 연장×단가 + 역수×역당단가)에 맞춘다.

한계 (보고서에 그대로 옮길 것)
  - 표본 63건, 그중 지하비율이 있는 건은 27건뿐이고 그 값도 전부 추정치다
  - 역당 단가는 연장과 거의 같은 정보를 담아(역수 ≈ 연장/역간격) 따로 분리되지 않는다.
    분리 시도 결과가 불안정하면 0으로 두고 연장 항에 포함시킨다
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

FIT_RANGE_KM = np.arange(5, 41, 1.0)   # 직선 근사 구간


def load_sample():
    df = pd.read_csv(BUILD / 'reference_line.csv')
    m = df[(df.data_grade.isin(['A', 'B']))
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


def design(df, modes, with_ug):
    """절편 + log(연장) + (지하비율) + 수단 더미"""
    cols = [np.ones(len(df)), np.log(df.length_km.values)]
    names = ['절편', 'log(연장)']
    if with_ug:
        cols.append(df.underground_ratio.values)
        names.append('지하비율')
    for mode in modes[1:]:                      # 첫 수단은 기준으로 흡수
        cols.append((df.mode_type == mode).astype(float).values)
        names.append(f'수단={mode}')
    return np.column_stack(cols), names


def fit_model(df, with_ug):
    modes = sorted(df.mode_type.unique())
    X, names = design(df, modes, with_ug)
    y = np.log(df.total_cost_2025.values.astype(float))
    beta, se = ols(X, y)
    pred = X @ beta
    r2 = 1 - ((y - pred) ** 2).sum() / ((y - y.mean()) ** 2).sum()
    return {'modes': modes, 'names': names, 'beta': beta, 'se': se,
            'r2': r2, 'loo': loo_error(X, y), 'n': len(df), 'with_ug': with_ug}


# 지하비율이 있는 표본은 도시철도(지하철·경전철·트램)뿐이다.
# BRT·복선전철은 전체 표본 모델로 예측하고, 그 예측이 아래 구조에 해당한다고 본다.
BASE_UG = {'BRT_HIGH': 0.0, 'BRT_LOW': 0.0, 'DOUBLE_ELEC': 0.1, 'SINGLE_ELEC': 0.1}


def predict_cost(model, mode, ug, length_km, ug_coef=None):
    """
    log-log 모델로 총사업비(억원) 예측.
    ug_coef 를 주면 모델에 지하비율 항이 없어도 구조 차이를 곱해서 보정한다.
    """
    beta, modes = model['beta'], model['modes']
    log_cost = beta[0] + beta[1] * np.log(length_km)
    i = 2
    if model['with_ug']:
        log_cost = log_cost + beta[i] * ug
        i += 1
    elif ug_coef is not None:
        log_cost = log_cost + ug_coef * (ug - BASE_UG.get(mode, 0.0))
    for m in modes[1:]:
        if m == mode:
            log_cost = log_cost + beta[i]
        i += 1
    return np.exp(log_cost)


def station_term(df):
    """
    역당 단가를 분리할 수 있는지 확인한다.
    역수는 연장과 거의 같은 정보라(역수 ≈ 연장/역간격) 계수가 불안정하면 0으로 둔다.
    """
    a = df[df.station_count.notna()]
    if len(a) < 15:
        return 0.0, f'표본 {len(a)}건으로 추정 불가 — 0 으로 두고 연장 항에 포함'
    X = np.column_stack([np.ones(len(a)), a.length_km.values, a.station_count.values])
    y = a.total_cost_2025.values.astype(float)
    beta, se = ols(X, y)
    coef, err = beta[2], se[2]
    if coef <= 0 or abs(coef) < 2 * err:
        return 0.0, (f'추정치 {coef:.0f}±{err:.0f} 억원 — 부호가 음수이거나 오차가 커서 '
                     f'0 으로 두고 연장 항에 포함 (n={len(a)})')
    return float(coef), f'연장·역수 동시 회귀 (n={len(a)}), 표준오차 {err:.0f}'


def linearize(model, mode, ug, station_cost, spacing_km, ug_coef=None):
    """
    log-log 예측 곡선을 5~40km 에서 '고정비 + 연장×단가' 직선으로 근사한다.
    역당 단가가 0 이 아니면 그만큼을 미리 빼고 남은 부분을 직선으로 맞춘다.
    """
    cost = predict_cost(model, mode, ug, FIT_RANGE_KM, ug_coef)
    if station_cost and spacing_km:
        cost = cost - station_cost * (FIT_RANGE_KM / spacing_km)
    X = np.column_stack([np.ones(len(FIT_RANGE_KM)), FIT_RANGE_KM])
    beta, _ = ols(X, cost)
    fixed, per_km = beta[0], beta[1]
    fit_r2 = 1 - ((cost - X @ beta) ** 2).sum() / ((cost - cost.mean()) ** 2).sum()
    return round(float(fixed)), round(float(per_km)), fit_r2


def report(model, label):
    print(f'\n[{label}] n={model["n"]}  R2={model["r2"]:.3f}  '
          f'LOO={model["loo"]:.3f} → 배수 오차 {np.exp(model["loo"]):.2f}배')
    for name, b, s in zip(model['names'], model['beta'], model['se']):
        mark = '' if abs(b) > 2 * s else '   (오차 큼)'
        print(f'  {name:<22} {b:8.3f} ± {s:.3f}{mark}')


def to_sql(rows, station_cost, station_note, model, path):
    lines = [
        '-- =====================================================',
        '-- S5: 수단×구조별 건설비 계수 (자동 생성)',
        '-- etl/cost_model.py 가 reference_line 에서 뽑는다. 직접 수정하지 말 것.',
        f'-- 도시철도: 지하비율 모델 n={model["n"]}, R2 {model["r2"]:.3f}, 교차검증 오차 {np.exp(model["loo"]):.2f}배',
        '-- 그 외 수단: 전체 표본 모델(n=63, 교차검증 1.45배) + 구조 보정',
        f'-- 역당 단가: {station_note}',
        '-- 값은 2025년 환산 기준(억원). 단일 값이 아니라 범위로 제시할 것.',
        '-- =====================================================',
        '',
        'TRUNCATE TABLE cost_standard;',
        '',
    ]
    for mode, structure, fixed, per_km, note in rows:
        lines.append(
            'INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, '
            'cost_per_station, base_year, is_active, source) VALUES '
            f"('{mode}', '{structure}', {fixed}, {per_km}, {round(station_cost)}, 2025, TRUE, "
            f"'{note}');")
    Path(path).write_text('\n'.join(lines) + '\n', encoding='utf-8')


def main():
    sample = load_sample()
    m1 = fit_model(sample, with_ug=False)
    report(m1, '전체 표본 — 지하비율 없이')

    ug_sample = sample[sample.underground_ratio.notna()]
    m2 = fit_model(ug_sample, with_ug=True)
    report(m2, '지하비율 포함')

    # 지하비율 모델은 도시철도만 담고 있어서, 나머지 수단은 전체 표본 모델로 예측하고
    # 구조 차이만 지하비율 계수로 보정한다
    ug_coef = float(m2['beta'][m2['names'].index('지하비율')])
    print(f'\n→ 도시철도는 지하비율 모델, 나머지 수단은 전체 표본 모델 + 구조 보정')
    print(f'   지하비율 계수 {ug_coef:.3f} → 전 구간 지하는 지상 대비 {np.exp(ug_coef):.2f}배')

    station_cost, station_note = station_term(sample)
    print(f'역당 단가: {station_cost:.0f} 억원 — {station_note}')

    spacing = {'HEAVY_METRO': 1.06, 'LIGHT_RAIL': 1.06, 'TRAM': 0.86,
               'BRT_HIGH': 1.16, 'BRT_LOW': 1.16, 'DOUBLE_ELEC': None}

    rows = []
    print('\n[수단×구조별 계수 — 2025년 환산 억원]')
    for mode, structures in MODE_STRUCTURES.items():
        n = int((sample.mode_type == mode).sum())
        if n == 0:
            print(f'  {mode:<12} 표본 없음 — 건너뜀')
            continue
        # 도시철도는 지하비율 모델, 나머지는 전체 표본 모델 + 구조 보정
        use_m2 = mode in m2['modes']
        model = m2 if use_m2 else m1
        coef = None if use_m2 else ug_coef
        for structure in structures:
            fixed, per_km, fit_r2 = linearize(model, mode, STRUCTURE_UG[structure],
                                              station_cost, spacing.get(mode), coef)
            note = ('지하비율 모델 n=' if use_m2 else '전체표본 모델+구조보정 n=') + str(n)
            if n < 5:
                note += ' — 표본 부족, 참고용'
            rows.append((mode, structure, fixed, per_km, note))
            warn = ' ⚠표본부족' if n < 5 else ''
            print(f'  {mode:<12} {structure:<12} 고정비 {fixed:>7,} + km당 {per_km:>6,}'
                  f'  (직선근사 R2 {fit_r2:.3f}, n={n}){warn}')

    to_sql(rows, station_cost, station_note, m2, DB_SEED / 'S5__cost_standard.sql')
    print(f'\n{DB_SEED / "S5__cost_standard.sql"} 생성 — {len(rows)}행')


if __name__ == '__main__':
    sys.stdout.reconfigure(errors='replace')
    main()
