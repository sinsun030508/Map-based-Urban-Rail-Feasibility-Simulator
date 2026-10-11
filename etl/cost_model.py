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

import re
import sys
import numpy as np
import pandas as pd
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'data' / 'build'
DB_SEED = ROOT / 'db' / 'seed'

# 구조별 지하 비율 대표값 — 계수를 적용할 때 쓴다
STRUCTURE_UG = {'UNDERGROUND': 1.0, 'ELEVATED': 0.1, 'AT_GRADE': 0.0}

# 수단별로 실제로 존재하는 구조만 만든다 (트램·BRT 는 지상 전용 — CLAUDE.md 비용 모델 표와 일치시킨다)
MODE_STRUCTURES = {
    'HEAVY_METRO': ['UNDERGROUND', 'ELEVATED'],
    'LIGHT_RAIL':  ['UNDERGROUND', 'ELEVATED'],
    'TRAM':        ['AT_GRADE'],
    'BRT_HIGH':    ['AT_GRADE'],
    'BRT_LOW':     ['AT_GRADE'],
    'DOUBLE_ELEC': ['UNDERGROUND', 'ELEVATED'],
    'EXPRESS_RAIL': ['UNDERGROUND', 'ELEVATED'],
}

# 비용 계수를 빌려 쓰는 수단 — 표본에 없으니 회귀 더미도 없다.
#
# **급행에 더미를 따로 두지 않는 것은 검사해서 정한 것이다.** km당 사업비만 보면
# 급행 579 / 일반 1,064 억으로 두 배 갈리지만, `log(역수)` 를 넣은 뒤 잔차 차이는
# 1.02배(t=+0.12)뿐이다 — 급행은 "급행이라서" 싼 게 아니라 역이 적어서 싸다.
# 역수는 `SPACING` 이 정하므로 급행의 싼 값은 저절로 나온다.
#
# 다만 급행 n=4 라 **검출 한계가 1.50배**다. "차이가 없다"가 아니라 "그보다 작은
# 차이는 못 본다"가 맞다 — 표본 4개에 더미를 더 붙이는 것이 과적합이라 안 가른 것이다.
# 검사·재현: `cd etl && python mode_speed_check.py` (테스트가 t>2 를 감시한다)
COST_ALIAS = {'EXPRESS_RAIL': 'DOUBLE_ELEC'}

FIT_RANGE_KM = np.arange(5, 41, 1.0)      # 직선 근사 구간
STATION_RATIOS = [0.6, 0.8, 1.0, 1.2, 1.4]  # 표준 역간격 대비 역수 비율

# 역간격 (km) — **S1__mode_capacity.sql 의 spacing_km 과 같아야 한다.**
# 2단계 직선 근사가 "표준 역수"를 이 값으로 잡으므로, 어긋나면 격자의 중심이
# 실제 쓰이는 역수에서 비껴 근사 오차가 커진다. (실제로 S1 이 4.98 → 4.30 →
# 2.62 로 바뀌는 동안 여기가 4.98 로 남아 있었다 — ETL 이 아래에서 검사한다.)
#
# 복선전철을 쌍봉 분포의 두 집단으로 가른 근거는 S1 주석에 있다.
SPACING = {'HEAVY_METRO': 1.06, 'LIGHT_RAIL': 1.06, 'TRAM': 0.86,
           'BRT_HIGH': 1.16, 'BRT_LOW': 1.16,
           'DOUBLE_ELEC': 2.62, 'EXPRESS_RAIL': 6.92}


def load_sample(grade=('A', 'B')):
    df = pd.read_csv(BUILD / 'reference_line.csv')
    m = df[(df.data_grade.isin(grade))
           & (df.cost_status == 'DISCLOSED')
           & (~df.is_outlier)
           & df.total_cost_2025.notna()
           & df.length_km.notna()
           & df.mode_type.notna()].copy()
    return m


# 집단을 갈라 잔차를 견줄 때 한쪽이 이보다 적으면 t 를 믿지 않는다.
#
# **n=2 에서 실제로 속았다.** `계획 사업비` 가 붙은 두 노선(7호선 청라연장·송파하남선)의
# 잔차가 소수점 5자리까지 같았다(둘 다 1.3243배 — 사업비 15,828 / 18,356 로 서로 다른
# 숫자인데 우연히 겹쳤다). 분산이 **정확히 0** 이라 표준오차가 0 이 되고 t 가 7.75 로
# 튀었다. 두 값이 log 0.4 만 벌어져도 t 는 1.46 으로 내려간다 — 즉 "유의함"의 전부가
# 그 우연이었다.
#
# 작은 n 에서는 t 가 아니라 **검출 한계**를 봐야 한다. n=2 일 때 한계가 1.08배로 나오는데,
# 그건 정밀해서가 아니라 분산을 잴 수 없어서다.
MIN_SPLIT_N = 3


def welch_t(a, b):
    """두 묶음의 평균 차이를 표준오차로 나눈 값과, 그 표준오차.

    분산이 다를 수 있으므로 합치지 않고 각자 센다(Welch). 표본이 작을 때
    **검출 한계**(2×표준오차)를 함께 돌려주는 것이 중요하다 — `|t| < 2` 를
    "차이가 없다"로 읽으면 안 되고 "이보다 작은 차이는 안 보인다"로 읽어야 한다.
    """
    import statistics as st
    sa, sb = st.variance(a) / len(a), st.variance(b) / len(b)
    se = (sa + sb) ** 0.5
    return (st.mean(a) - st.mean(b)) / se, se


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
                 ug_coef=None, station_coef=None, dummy=None):
    """
    log-log 모델로 총사업비(억원) 예측.
    모델에 없는 항(지하비율·역수)은 밖에서 계수를 받아 곱해 준다.

    `dummy` 는 **어느 수단의 계수를 빌려 쓸지**다 (기본은 `mode` 자신).
    급행처럼 표본에 없는 수단은 `COST_ALIAS` 가 가리키는 수단의 계수를 쓰면서
    역간격은 자기 것(`SPACING[mode]`)을 쓴다 — 그래야 역이 적은 만큼 싸진다.
    """
    dummy = dummy or mode
    beta, modes = model['beta'], model['modes']
    log_cost = beta[0] + beta[1] * np.log(length_km)
    i = 2
    if model['with_ug']:
        log_cost = log_cost + beta[i] * ug
        i += 1
    elif ug_coef is not None:
        log_cost = log_cost + ug_coef * (ug - BASE_UG.get(dummy, 0.0))
    if model['with_stations']:
        log_cost = log_cost + beta[i] * np.log(stations)
        i += 1
    elif station_coef is not None and stations is not None:
        # 표준 역간격일 때를 기준으로, 역수가 그보다 많고 적은 만큼만 보정
        log_cost = log_cost + station_coef * np.log(stations / (length_km / SPACING[mode]))
    for m in modes[1:]:
        if m == dummy:
            log_cost = log_cost + beta[i]
        i += 1
    return np.exp(log_cost)


def fit_additive(model, mode, ug, ug_coef=None, station_coef=None, dummy=None):
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
                                           ug_coef, station_coef, dummy)))
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
        '-- 지우지 않고 **내린다** (is_active=FALSE). 이유가 둘이다:',
        '--  ① scenario_result 가 standard_id 를 FK 로 참조해 DELETE 가 막힌다.',
        '--     TRUNCATE 는 참조 행이 없어도 막히고, DELETE 는 있으면 막힌다 —',
        '--     저장된 비교표가 하나라도 있으면 시드를 다시 넣을 수 없었다.',
        '--  ② 저장해 둔 B/C 가 **어느 기준값으로 계산됐는지** 남는다. 지워 버리면',
        '--     과거 결과의 근거가 사라져 재현할 수 없다. 이 프로젝트에서 값보다',
        '--     중요한 것이 출처다.',
        '-- 백엔드는 findByActiveTrue() 로 읽으므로 내린 행은 계산에 쓰이지 않는다.',
        '-- 기준값이 바뀌면 화면이 "다시 계산해 주세요" 를 띄운다 (낡은 결과 알림).',
        'UPDATE cost_standard SET is_active = FALSE WHERE is_active = TRUE;',
        '',
    ]
    for mode, structure, fixed, per_km, per_st, note in rows:
        lines.append(
            'INSERT INTO cost_standard (mode_type, structure_type, fixed_cost, cost_per_km, '
            'cost_per_station, base_year, is_active, source) VALUES '
            f"('{mode}', '{structure}', {fixed}, {per_km}, {per_st}, 2025, TRUE, '{note}');")
    Path(path).write_text('\n'.join(lines) + '\n', encoding='utf-8')


def check_spacing_matches_s1():
    """`SPACING` 이 S1 의 `spacing_km` 과 어긋나면 멈춘다.

    **실제로 어긋나 있었다.** S1 의 복선전철 역간격이 4.98 → 4.30 으로 바뀌었는데
    여기가 4.98 로 남았다.

    **다만 그게 숫자를 망치지는 않았다 — 재 봤다.** 직선 근사의 격자 중심이
    실제 쓰이는 역수에서 비껴도, log-log 면이 그 구간에서 거의 직선이라
    근사 오차는 0.79%(어긋난 상태) vs 0.95%(맞춘 상태)로 둘 다 1% 안이다.
    모델 자체의 교차검증 오차가 1.25배인데 비하면 없는 셈이다.

    그래도 검사로 막는 이유는 **같은 값이 두 파일에서 다르면 어느 쪽이 맞는지
    알 수 없게 되기 때문**이다. 재현성 문제이지 정확도 문제가 아니다 —
    오차가 작다는 것을 확인하기 전까지는 그걸 알 수도 없었다.
    """
    text = (DB_SEED / 'S1__mode_capacity.sql').read_text(encoding='utf-8')
    s1 = {}
    for m in re.finditer(r"\('([A-Z_]+)'\s*,\s*'[^']*'\s*,"
                         r"(?:\s*(?:NULL|[0-9.]+)\s*,){4}"
                         r"\s*(NULL|[0-9.]+)\s*,", text):
        if m.group(2) != 'NULL':
            s1[m.group(1)] = float(m.group(2))

    missing = [k for k in SPACING if k not in s1]
    if missing:
        raise SystemExit(f'S1 에 역간격이 없는 수단: {missing} — S1 을 먼저 고칠 것')
    bad = {k: (SPACING[k], s1[k]) for k in SPACING if abs(SPACING[k] - s1[k]) > 1e-9}
    if bad:
        lines = '\n'.join(f'  {k}: cost_model {v[0]} vs S1 {v[1]}' for k, v in bad.items())
        raise SystemExit('역간격이 S1 과 어긋납니다 — 같은 값이 두 곳에서 달라 어느 쪽이 '
                         f'맞는지 알 수 없습니다 (근사 오차 자체는 둘 다 1% 안입니다):\n{lines}')
    print(f'역간격 S1 일치 확인 — {len(SPACING)}개 수단')


def residuals(sample=None):
    """A등급 표본에 지금 모델을 적합하고 `resid`(log 잔차)를 붙여 돌려준다."""
    s = load_sample(grade=('A',)) if sample is None else sample
    s = s[s.underground_ratio.notna() & s.station_count.notna()]
    m = fit_model(s, with_ug=True, with_stations=True)
    X, _ = design(s, m['modes'], True, True)
    return s.assign(resid=np.log(s.total_cost_2025.values.astype(float)) - X @ m['beta']), m


def candidate_splits():
    """"이 변수를 비용 모델에 넣어야 하나" 를 잔차로 가린다.

    `(이름, n참, n거짓, 비율, t, 검출한계, 판정)` 목록.

    **쓰는 법.** 어떤 변수를 넣고 싶으면 먼저 여기 넣어 본다. 모델이 이미 설명하는
    것이면 잔차가 쏠리지 않는다 — 급행이 그랬다(km당 사업비는 두 배 갈리는데
    `log(역수)` 를 넣고 나면 차이가 1.02배였다).

    **판정을 t 로만 읽지 말 것.** 한쪽 n 이 `MIN_SPLIT_N` 보다 작으면 t 를 아예
    내놓지 않는다(위 주석의 n=2 사고). 그보다 커도 n 이 작으면 검출 한계가 커서,
    `|t| < 2` 는 "차이가 없다"가 아니라 "그보다 작은 차이는 못 본다"는 뜻이다.
    """
    s, _ = residuals()
    seed = pd.read_csv(ROOT / 'data/seed/metro_seed.csv')
    note = dict(zip(seed.line_name.astype(str), seed.note.fillna('').astype(str)))
    s = s.assign(note=s.line_name.astype(str).map(lambda n: note.get(n, '')))

    cands = {
        # 공법 — 다음 할 일 ⑤ 가 묻던 것. 표본이 없어 못 만든다 (아래 출력이 보여 준다)
        '저심도':          s.note.str.contains('저심도'),
        '지상 위주':        s.note.str.contains('지상'),
        '고무차륜·모노레일':   s.note.str.contains('고무차륜|모노레일'),
        # 사업 성격
        '차량기지 포함':      s.note.str.contains('차량기지'),
        '민자 사업':         s.note.str.contains('민자'),
        '계획 사업비':        s.note.str.contains('계획 사업비'),
        # 이미 변수로 있는 것 — 잔차가 쏠리면 그 변수가 부족하다는 뜻이다
        '수도권':           s.region_class == 'CAPITAL',
        '역간격 >= 4.5km':   s.avg_spacing_km >= 4.5,
        '기준연도가 미래':      s.base_year > 2025,
    }
    out = []
    for name, mask in cands.items():
        a, b = list(s.resid[mask].values), list(s.resid[~mask].values)
        if min(len(a), len(b)) < MIN_SPLIT_N:
            out.append((name, len(a), len(b), None, None, None,
                        f'표본 부족 (한쪽 {min(len(a), len(b))}개 < {MIN_SPLIT_N}) — 잴 수 없다'))
            continue
        t, se = welch_t(a, b)
        ratio = float(np.exp(np.mean(a) - np.mean(b)))
        limit = float(np.exp(2 * se))
        verdict = '**쏠린다 — 변수로 검토**' if abs(t) >= 2 else '쏠리지 않는다'
        out.append((name, len(a), len(b), ratio, float(t), limit, verdict))
    return out


def report_candidates():
    print()
    print('[후보 변수 — 잔차가 쏠리는지 본다]')
    print('  "이 변수를 넣어야 하나" 를 넣어 보기 전에 가린다. 모델이 이미 설명하면 안 쏠린다.')
    print(f'  {"변수":<18}{"n(참)":>6}{"n(거짓)":>7}{"비율":>7}{"t":>8}{"검출한계":>9}  판정')
    for name, na, nb, ratio, t, limit, verdict in candidate_splits():
        if t is None:
            print(f'  {name:<18}{na:>6}{nb:>7}{"":>7}{"":>8}{"":>9}  {verdict}')
        else:
            print(f'  {name:<18}{na:>6}{nb:>7}{ratio:>7.2f}{t:>+8.2f}{limit:>8.2f}배  {verdict}')
    print()
    print('  **t 보다 검출 한계를 먼저 볼 것.** n 이 작으면 한계가 커서 어지간한 차이는')
    print('  보이지 않는다 — |t| < 2 는 "차이가 없다"가 아니라 "그보다 작으면 못 본다"다.')


def main():
    check_spacing_matches_s1()

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
        dummy = COST_ALIAS.get(mode, mode)      # 계수를 빌려 쓸 수단
        n = int((a_sample.mode_type == dummy).sum())
        n_all = int((sample.mode_type == dummy).sum())
        if n_all == 0:
            print(f'  {mode:<12} 표본 없음 — 건너뜀')
            continue
        use_m2 = dummy in m2['modes'] and n >= 2
        model = m2 if use_m2 else m1
        for structure in structures:
            fixed, per_km, per_st, fit_r2 = fit_additive(
                model, mode, STRUCTURE_UG[structure],
                None if use_m2 else ug_coef,
                None if use_m2 else station_coef, dummy)
            note = (f'A등급 모델 n={n}' if use_m2
                    else f'전체표본 모델+보정 n={n_all} (역수 표본 없음)')
            if dummy != mode:
                note += (f' — {dummy} 계수를 빌려 씀 (잔차로 가를 근거를 못 봤다,'
                         f' 검출 한계 1.50배) / 역간격 {SPACING[mode]}km 는 자기 것')
            if max(n, n_all) < 5:
                note += ' — 표본 부족, 참고용'
            rows.append((mode, structure, fixed, per_km, per_st, note))
            warn = ' (표본부족)' if max(n, n_all) < 5 else ''
            print(f'  {mode:<12} {structure:<12} 고정비 {fixed:>7,} + km당 {per_km:>6,}'
                  f' + 역당 {per_st:>5,}  (근사 R2 {fit_r2:.3f}, n={n or n_all}){warn}')

    to_sql(rows, m2, DB_SEED / 'S5__cost_standard.sql')
    print()
    print(f'{DB_SEED / "S5__cost_standard.sql"} 생성 — {len(rows)}행')

    if '--candidates' in sys.argv:
        report_candidates()


if __name__ == '__main__':
    sys.stdout.reconfigure(errors='replace')
    main()
