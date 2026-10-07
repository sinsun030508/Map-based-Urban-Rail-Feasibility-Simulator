"""
지하비율 추정이 틀리면 비용이 얼마나 흔들리나.

왜 재나
  `underground_ratio` 는 비용 회귀에서 **계수가 가장 큰 변수**(0.575)인데 40건이 전부
  추정치다 (`metro_seed.csv` note 에 "지하비율 추정치 (공식 미공표)" 로 적혀 있다).
  CLAUDE.md 에 "회귀에 가장 효과가 큰 변수라 위험하다" 고 적어 뒀는데, 얼마나 위험한지는
  재 본 적이 없었다. 숫자 없이 "위험하다" 고만 두면 보고서에 쓸 수 없다.

무엇을 재나
  표본의 지하비율을 흔들고 **회귀를 다시 적합한 뒤** 예측이 얼마나 달라지는지 본다.
  계수만 보는 것으로는 부족하다 — 추정이 틀리면 계수가 그 오차를 흡수하므로,
  정작 궁금한 것은 "최종 비용이 얼마나 빗나가는가" 다.

    ① 치우침 : 전 표본을 같은 방향으로 ±0.1, ±0.2 민다
    ② 잡음   : 표준편차 0.15 짜리 정규 잡음을 넣고 200번 다시 적합한다
    ③ 제거   : 변수를 아예 빼면 얼마나 나빠지나

주의
  **표본 38건 중 23건이 이미 1.00 이다.** 위로 미는 교란은 clip 에 잘려 표현되지 않으므로
  ①의 위쪽 수치는 과소평가다. ②가 더 고르게 흔든다.

    cd etl && python ug_sensitivity.py
"""

import numpy as np

import cost_model as cm

COL = 'underground_ratio'
NAME = '지하비율'
NOISE_SD = 0.15        # 추정 오차를 이 정도로 본다
TRIALS = 200
SEED = 0               # 결정적으로 재현되게 고정한다

# 비교 기준이 될 가상 노선 — 지하 20km·19역 중전철
CASE = dict(mode='HEAVY_METRO', ug=1.0, length_km=20.0, stations=19)


def fit(df):
    return cm.fit_model(df, with_ug=True, with_stations=True)


def cost(model):
    return cm.predict_cost(model, CASE['mode'], CASE['ug'],
                           CASE['length_km'], stations=CASE['stations'])


def main():
    df = cm.load_sample(grade=('A',))
    df = df[df[COL].notna()].copy()

    base = fit(df)
    i = base['names'].index(NAME)
    b0 = cost(base)
    saturated = int((df[COL] >= 1.0).sum())

    print(f'표본 {base["n"]}건 (그 중 지하비율 1.00 이 {saturated}건)')
    print(f'  계수 {base["beta"][i]:.3f} ±{base["se"][i]:.3f}'
          f'  R² {base["r2"]:.3f}  교차검증 {np.exp(base["loo"]):.2f}배')
    print(f'  기준 노선: 지하 {CASE["length_km"]:.0f}km·{CASE["stations"]}역 중전철'
          f' = {b0:,.0f}억')

    print()
    print('① 추정이 한쪽으로 치우쳤다면')
    print(f'{"교란":>7s} {"계수":>8s} {"교차검증":>10s} {"비용":>9s}')
    for d in (-0.2, -0.1, 0.1, 0.2):
        x = df.copy()
        x[COL] = (x[COL] + d).clip(0, 1)
        m = fit(x)
        print(f'{d:+7.1f} {m["beta"][i]:8.3f} {np.exp(m["loo"]):9.2f}배'
              f' {cost(m) / b0:8.1%}')
    print(f'  위로 미는 교란은 1.00 에서 잘린다({saturated}/{base["n"]}건) - 과소평가로 읽을 것')

    print()
    print(f'② 추정이 무작위로 틀렸다면 (표준편차 {NOISE_SD}, {TRIALS}회)')
    rng = np.random.default_rng(SEED)
    coefs, costs = [], []
    for _ in range(TRIALS):
        x = df.copy()
        x[COL] = (x[COL] + rng.normal(0, NOISE_SD, len(x))).clip(0, 1)
        m = fit(x)
        coefs.append(m['beta'][i])
        costs.append(cost(m) / b0)
    coefs, costs = np.array(coefs), np.array(costs)
    print(f'  계수  중앙값 {np.median(coefs):.3f}'
          f'  5~95% {np.percentile(coefs, 5):.3f}~{np.percentile(coefs, 95):.3f}')
    print(f'  비용  중앙값 {np.median(costs):.1%}'
          f'  5~95% {np.percentile(costs, 5):.1%}~{np.percentile(costs, 95):.1%}')
    print('  추정이 틀리면 계수가 그만큼 흡수해 예측은 덜 흔들린다')

    print()
    print('③ 지하비율을 아예 빼면')
    without = cm.fit_model(df, with_ug=False, with_stations=True)
    print(f'  교차검증 {np.exp(base["loo"]):.2f}배 → {np.exp(without["loo"]):.2f}배')
    print(f'  R²       {base["r2"]:.3f} → {without["r2"]:.3f}')
    print('  쓸 값어치는 있으나 없어도 치명적이지 않다')


if __name__ == '__main__':
    main()
