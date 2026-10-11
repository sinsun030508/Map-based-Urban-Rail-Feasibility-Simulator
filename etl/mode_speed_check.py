"""
수단별 역간격과 표정속도의 짝이 맞는지 검사한다.

왜 재나
  `mode_capacity` 의 `spacing_km` 과 `speed_kmh` 는 **독립으로 쓰인다** —
  `CostCalculator` 가 전자로 역을 놓고 `BenefitCalculator` 가 후자로 통행시간을 잰다.
  둘이 다른 노선 집합에서 오면 모델이 "A 처럼 역을 놓고 B 처럼 달리는" 수단을 만든다.
  실제로 복선전철이 그랬다 — 역간격은 사업계획 전체 중앙값(4.30km)인데 속도는
  신분당선 하나(47.6, 그 노선의 역간격은 2.09km)였다.

  그런데 둘을 견줄 자가 없었다. **역간격이 표정속도를 거의 완전히 설명한다**는 것을
  BRT 실측에서 찾아 철도로 옮기고 나서 생겼다.

무엇을 재나
  ① `rail_speed.csv`(공표 표정속도)와 `reference_line`(역간격)을 노선별로 짝지어
     `속도 = a + b x 역간격` 을 적합한다
  ② S1 의 수단별 (spacing_km, speed_kmh) 짝이 그 직선과 얼마나 어긋나는지 본다
  ③ 복선전철 역간격 분포가 쌍봉인 것과, 급행/일반으로 가르면 어긋남이 줄어드는 것을 보인다

주의 — 두 번 틀렸던 곳
  **이름만으로 짝지으면 안 된다.** `신분당선` 33.8km 가 52.9km 행에 붙었다.
  연장이 5% 안에서 맞을 때만 받아들인다(`TOL`). 이 프로젝트가 OSM 역수를 ±2 로
  검증한 것과 같은 방식이다.

  **회귀 범위를 넘겨 쓰면 안 된다.** 적합 구간은 역간격 0.71~2.09km 다. 급행(GTX)은
  예측의 0.68배밖에 안 나온다 — 속도는 포화한다. 범위 밖 예측은 과대다.

  **그 급행을 표본에 넣으면 안 된다.** 포화를 보이려면 포화한 점이 표본 **밖**에
  있어야 한다. `load_pairs` 는 `EXPRESS_RAIL` 을 빼고, `saturation_pairs` 가 그것만
  따로 내놓는다. 전에는 급행 속도 90.14 와 역간격 7.667 이 이 스크립트에 손으로
  박혀 있었다 — csv 를 고쳐도 따라오지 않는 자리였다 (테스트로 막아 뒀다).

    cd etl && python mode_speed_check.py
"""

import csv
import io
import re
import statistics
import sys
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
TOL = 0.05            # 연장이 이 비율 안에서 맞을 때만 노선을 짝짓는다
FIT_MIN_N = 8         # 이보다 표본이 적으면 적합하지 않는다

# 급행은 적합 표본에서 빼고 포화 검증에만 쓴다 (머리말 참고).
# 수단으로 가르는 이유: 역간격에 기준선을 그으면 "2.5km" 같은 임의의 숫자가 생긴다.
SATURATED_MODES = ('EXPRESS_RAIL',)


def norm(s):
    return re.sub(r'\s+', '', str(s or ''))


def match_line(key, length, candidates):
    """이름이 겹치고 **연장이 TOL 안에서 맞는** 후보 중 연장이 가장 가까운 것.

    `(정규화한 이름, 연장, 역간격)` 목록을 받아 하나를 돌려주거나 None 을 준다.

    **연장 검증이 없으면 조용히 틀린다.** `신분당선` 33.8km 가 52.9km 행에 붙어
    역간격 1.56 으로 읽혔고(실제 2.09), 그 값으로 회귀를 돌려 결론을 낼 뻔했다.
    이름이 겹치는 것만으로는 같은 노선·같은 구간이라는 보장이 없다.
    """
    best = None
    for name, cand_len, spacing in candidates:
        if not key or not (key in name or name in key):
            continue
        if abs(cand_len - length) / length > TOL:
            continue
        if best is None or abs(cand_len - length) < abs(best[1] - length):
            best = (name, cand_len, spacing)
    return best


def load_pairs():
    """적합에 쓸 (수단, 노선, 역간격, 속도) — 급행은 뺀다.

    급행을 빼는 이유는 머리말에 있다. 포화를 보일 점이 표본 안에 있으면
    직선이 그 점을 향해 휘어서 "벗어난다"고 말할 수 없게 된다.
    """
    pairs, skipped = _all_pairs()
    return [p for p in pairs if p[0] not in SATURATED_MODES], skipped


def express_speed():
    """급행 표정속도 중앙값 — `rail_speed.csv` 의 `EXPRESS_RAIL` 행에서 읽는다.

    **노선 단위로 짝지을 수 없다.** `rail_speed.csv` 는 운행 구간으로 적혀 있고
    (`운정중앙~서울역` 32.3km) `reference_line` 은 사업 단위다
    (`수도권광역급행철도(A노선)` 46.0km) — 이름도 연장도 맞지 않는다.
    그래서 급행만은 중앙값끼리 견준다. 역간격은 `express_spacing()` 이 준다.
    """
    rows = list(csv.DictReader((ROOT / 'data/seed/rail_speed.csv').open(encoding='utf-8')))
    v = [float(r['speed_kmh']) for r in rows
         if r['mode'] == 'EXPRESS_RAIL' and r['use_for_median'] == '1']
    return statistics.median(v) if v else None


def express_spacing():
    """급행 역간격 중앙값 — `reference_line` 의 복선전철 중 이름에 `급행` 이 든 것."""
    de = double_elec_split()
    return statistics.median(de[de.급행].avg_spacing_km.tolist())


def _all_pairs():
    """(수단, 노선, 역간격, 속도) — 연장이 맞는 것만."""
    df = pd.read_csv(ROOT / 'data/build/reference_line.csv', encoding='utf-8-sig')
    df['is_outlier'] = df['is_outlier'].astype(str).str.upper().isin(['TRUE', '1'])
    pool = df[df.avg_spacing_km.notna() & ~df.is_outlier & (df.station_count >= 2)]

    rows = list(csv.DictReader((ROOT / 'data/seed/rail_speed.csv').open(encoding='utf-8')))
    pairs, skipped = [], []
    for s in rows:
        if s['use_for_median'] != '1':      # 중복 집계·제외 사유가 있는 행
            continue
        try:
            length = float(s['length_km'])
        except (TypeError, ValueError):
            skipped.append((s['line'], '연장 없음'))
            continue
        cands = [(norm(r['line_name']), float(r['length_km']), float(r['avg_spacing_km']))
                 for _, r in pool.iterrows()]
        best = match_line(norm(s['line']), length, cands)
        if best is None:
            skipped.append((s['line'], '연장이 맞는 행 없음'))
        else:
            pairs.append((s['mode'], s['line'], best[2], float(s['speed_kmh'])))
    return pairs, skipped


def fit(points):
    """(x, y) 목록 → (절편, 기울기, R²)."""
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    mx, my = statistics.mean(xs), statistics.mean(ys)
    den = sum((x - mx) ** 2 for x in xs)
    if den == 0:
        return None
    b = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / den
    a = my - b * mx
    ss = sum((y - my) ** 2 for y in ys)
    rs = sum((y - (a + b * x)) ** 2 for x, y in zip(xs, ys))
    return a, b, (1 - rs / ss if ss else None)


def s1_modes():
    """S1 에 적힌 (코드, 이름, spacing_km, speed_kmh)."""
    text = (ROOT / 'db/seed/S1__mode_capacity.sql').read_text(encoding='utf-8')
    out = []
    for m in re.finditer(r"\('([A-Z_]+)',\s*'([^']*)',\s*[^,]+,\s*[^,]+,\s*[^,]+,"
                         r"\s*[^,]+,\s*(NULL|[0-9.]+),\s*(NULL|[0-9.]+),", text):
        spc = None if m.group(3) == 'NULL' else float(m.group(3))
        spd = None if m.group(4) == 'NULL' else float(m.group(4))
        out.append((m.group(1), m.group(2).strip(), spc, spd))
    return out


def double_elec_split():
    """복선전철 역간격을 급행/일반으로 가른다.

    노선명에 `급행` 이 들어가는지로 가른다 — 객관적이고 재현된다. 다만 `수서광주선`
    처럼 이름에 급행이 없으면서 역간격이 6.47km 인 노선은 일반으로 분류된다.
    이름 규칙의 한계다.
    """
    df = pd.read_csv(ROOT / 'data/build/reference_line.csv', encoding='utf-8-sig')
    df['is_outlier'] = df['is_outlier'].astype(str).str.upper().isin(['TRUE', '1'])
    de = df[(df.mode_type == 'DOUBLE_ELEC') & df.avg_spacing_km.notna()
            & ~df.is_outlier & (df.station_count >= 2)].copy()
    de['급행'] = de.line_name.astype(str).str.contains('급행')
    return de


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


def cost_residual_t(rule='name'):
    """비용 회귀 잔차가 급행/일반에서 쏠리는지 — `(t, 검출 한계 배수)`.

    **왜 이걸 재나.** km당 사업비만 보면 급행 579 / 일반 1,064 억으로 두 배 갈린다.
    그걸 보고 "급행은 비용 계수를 따로 둬야 한다"고 결론내면 틀린다 — 회귀에 이미
    `log(역수)` 가 있어서, 역수를 알고 나면 그 차이가 남는지를 봐야 한다.
    남는 몫이 잔차다. 관측된 잔차 차이는 1.02배로 거의 없다.

    **다만 "없다"고 말하면 과한 주장이다.** 급행 n=4 · 일반 n=6 이고 급행 쪽
    잔차 표준편차가 0.385(=1.47배)나 되어, **1.50배 미만의 차이는 이 표본에서
    보이지 않는다.** `log(역수)` 를 아예 빼고 돌려 봐도 t가 +0.12 → −0.79 로
    움직일 뿐 2를 넘지 않았다 — 즉 이 검정은 메커니즘을 확인해 준 것이 아니라
    **어느 쪽도 가리지 못한다.**

    그래도 결론은 같다: 표본 4개에 더미를 하나 더 붙이는 것은 과적합이고,
    1.5배 미만일 수도 있는 차이를 쫓을 근거가 없다. 급행은 복선전철의 비용 계수를
    그대로 쓰고 역이 적어서 저절로 싸진다. **"차이가 없음을 보였다"가 아니라
    "볼 힘이 없다"가 이 숫자의 뜻이다** — 보고서에 그렇게 쓸 것.

    `rule` 은 가르는 기준이다 — `'name'` 은 노선명의 `급행`(S1 과 같은 규칙),
    `'spacing'` 은 역간격 4.5km. 어느 쪽으로 갈라도 결론이 같아야 한다.
    """
    import numpy as np
    import cost_model

    s = cost_model.load_sample(grade=('A',))
    s = s[s.underground_ratio.notna() & s.station_count.notna()]
    m = cost_model.fit_model(s, with_ug=True, with_stations=True)
    X, _ = cost_model.design(s, m['modes'], True, True)
    resid = np.log(s.total_cost_2025.values.astype(float)) - X @ m['beta']

    de = (s.mode_type == 'DOUBLE_ELEC') & (s.station_count >= 2)
    r = resid[de.values]
    sub = s[de]
    grp = (sub.line_name.astype(str).str.contains('급행').values if rule == 'name'
           else (sub.avg_spacing_km >= 4.5).values)
    t, se = welch_t(list(r[grp]), list(r[~grp]))
    import math
    return float(t), math.exp(2 * se)


def main():
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

    pairs, skipped = load_pairs()
    print('노선별 (역간격, 표정속도) 짝 %d개 — 연장이 %.0f%% 안에서 맞는 것만'
          % (len(pairs), TOL * 100))
    if skipped:
        print('  못 붙인 것 %d개: %s' % (len(skipped), ', '.join(n for n, _ in skipped)))
    if len(pairs) < FIT_MIN_N:
        raise SystemExit('표본이 %d개뿐이라 적합하지 않습니다 (최소 %d개)'
                         % (len(pairs), FIT_MIN_N))
    print()

    pts = [(p[2], p[3]) for p in pairs]
    a, b, r2 = fit(pts)
    lo, hi = min(x for x, _ in pts), max(x for x, _ in pts)
    print('속도 = %.2f + %.2f x 역간격   (노선 %d개, R² %.3f)' % (a, b, len(pts), r2))
    print('  적합 구간은 역간격 %.2f~%.2f km 다. **이 밖으로 쓰지 말 것** —' % (lo, hi))
    x_sat, y_sat = express_spacing(), express_speed()
    print('  속도는 포화한다 (급행 %.2fkm: 예측 %.0f vs 실측 %.2f = %.2f배).'
          % (x_sat, a + b * x_sat, y_sat, y_sat / (a + b * x_sat)))
    print('  급행은 이 표본 **밖**이다 — 그래야 벗어남을 보일 수 있다.')
    print()

    print('수단별 짝이 맞는지 (S1 기준)')
    print('  %-12s %-12s %8s %8s %10s %8s  %s'
          % ('수단', '이름', '역간격', '속도', '예측', '차이', '읽기'))
    note = {
        'HEAVY_METRO': '맞는다 (적합 표본의 다수라 일부 순환적이다)',
        'LIGHT_RAIL': '조금 느리다 — 최고속도가 낮다',
        'TRAM': '느린 것이 당연하다 — 노면 주행',
        'BRT_HIGH': '가정값이다 (실측 중)',
        'BRT_LOW': '가정값이다 (실측 중)',
        'DOUBLE_ELEC': '일반 광역으로 좁혔다 — 속도 출처(신분당선 2.09km)와 같은 집단',
        'EXPRESS_RAIL': '포화 구간이다 — 예측이 과대한 것이고 값이 틀린 게 아니다',
    }
    for code, name, spc, spd in s1_modes():
        if spc is None or spd is None:
            print('  %-12s %-12s %8s %8s %10s %8s  %s'
                  % (code, name, '-' if spc is None else '%.2f' % spc,
                     '-' if spd is None else '%.1f' % spd, '-', '-', '역간격·속도 미수집'))
            continue
        pred = a + b * spc
        mark = '' if lo <= spc <= hi else '  (범위 밖 — 예측 과대)'
        print('  %-12s %-12s %8.2f %8.1f %10.1f %7.0f%%  %s%s'
              % (code, name, spc, spd, pred, (spd / pred - 1) * 100,
                 note.get(code, ''), mark))
    print()

    de = double_elec_split()
    exp = de[de.급행].avg_spacing_km.tolist()
    nor = de[~de.급행].avg_spacing_km.tolist()
    allv = de.avg_spacing_km.tolist()
    print('복선전철 역간격은 쌍봉이다 — 중앙값이 두 집단 사이 빈 틈에 떨어진다')
    print('  %-10s n=%-3d 중앙값 %5.2f  범위 %.2f~%.2f'
          % ('전체', len(allv), statistics.median(allv), min(allv), max(allv)))
    print('  %-10s n=%-3d 중앙값 %5.2f  범위 %.2f~%.2f'
          % ('급행', len(exp), statistics.median(exp), min(exp), max(exp)))
    print('  %-10s n=%-3d 중앙값 %5.2f  범위 %.2f~%.2f'
          % ('일반 광역', len(nor), statistics.median(nor), min(nor), max(nor)))
    print()
    print('가른 효과 (속도 출처를 그대로 두고 역간격만 바꿔 본다)')
    print('  %-16s %8s %8s %10s %8s' % ('', '역간격', '속도', '예측', '차이'))
    for label, spc, spd, src in [
            ('가르기 전', statistics.median(allv), 47.6, '신분당선(역간격 2.09)'),
            ('일반 광역', statistics.median(nor), 47.6, '신분당선(역간격 2.09)'),
            ('급행', statistics.median(exp), express_speed(), 'GTX-A 두 구간 중앙값')]:
        pred = a + b * spc
        print('  %-16s %8.2f %8.2f %10.1f %7.0f%%   속도 출처 %s'
              % (label, spc, spd, pred, (spd / pred - 1) * 100, src))
    print()
    print('일반 광역으로 좁히면 어긋남이 크게 줄어든다. 급행은 적합 구간 밖이라')
    print('예측 자체가 과대하므로 그 차이를 어긋남으로 읽지 말 것.')
    print()

    print('비용은 가를 근거가 있나 — 회귀 잔차가 쏠리는지 본다')
    for rule, label in (('name', '이름에 "급행"'), ('spacing', '역간격 >= 4.5km')):
        t, lim = cost_residual_t(rule)
        print('  %-16s t=%+.2f  %s   검출 한계 %.2f배'
              % (label, t, '쏠리지 않는다' if abs(t) < 2 else '**쏠린다**', lim))
    print('  km당 579 vs 1,064 억 차이는 `log(역수)` 가 이미 설명한다 —')
    print('  급행은 "급행이라서" 싼 게 아니라 역이 적어서 싸다.')
    print()
    print('  **다만 이 검정은 어느 쪽도 가리지 못한다.** 급행 n=4 라 검출 한계가')
    print('  위 배수만큼 크다. `log(역수)` 를 빼고 돌려도 t가 2를 넘지 않았다.')
    print('  "차이가 없다" 가 아니라 **"그보다 작은 차이는 못 본다"** 가 맞는 해석이다.')
    print('  결론은 같다 — 표본 4개에 더미를 더 붙이는 것은 과적합이므로 급행은')
    print('  복선전철의 비용 계수를 그대로 쓴다(S5). 표본이 늘면 테스트가 알려 준다.')
    print()
    print('**이 스크립트는 값을 고치지 않는다.** 검사만 한다 —')
    print('값은 db/seed/S1__mode_capacity.sql 과 data/seed/rail_speed.csv 가 원본이다.')


if __name__ == '__main__':
    main()
