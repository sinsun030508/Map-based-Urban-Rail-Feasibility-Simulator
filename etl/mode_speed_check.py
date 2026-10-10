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

  **회귀 범위를 넘겨 쓰면 안 된다.** 적합 구간은 역간격 0.71~2.09km 다. GTX(7.67km)는
  예측 132 vs 실측 90 으로 0.68배다 — 속도는 포화한다. 범위 밖 예측은 과대다.

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
    print('  속도는 포화한다 (GTX 7.67km: 예측 %.0f vs 실측 90.14 = 0.68배).'
          % (a + b * 7.667))
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
        'DOUBLE_ELEC': '**역간격과 속도가 다른 노선 집합에서 왔다** — 아래 참고',
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
    print('가르면 짝이 맞아 간다 (속도 출처를 그대로 두고 역간격만 바꿔 본다)')
    print('  %-16s %8s %8s %10s %8s' % ('', '역간격', '속도', '예측', '차이'))
    for label, spc, spd, src in [
            ('현재 S1', statistics.median(allv), 47.6, '신분당선(역간격 2.09)'),
            ('일반 광역', statistics.median(nor), 47.6, '신분당선(역간격 2.09)'),
            ('급행', statistics.median(exp), 90.14, 'GTX-A(역간격 7.67)')]:
        pred = a + b * spc
        print('  %-16s %8.2f %8.2f %10.1f %7.0f%%   속도 출처 %s'
              % (label, spc, spd, pred, (spd / pred - 1) * 100, src))
    print()
    print('일반 광역으로 가르면 어긋남이 크게 줄어든다. 급행은 적합 구간 밖이라')
    print('예측 자체가 과대하므로 그 차이를 어긋남으로 읽지 말 것.')
    print()
    print('**이 스크립트는 값을 고치지 않는다.** 수단을 쪼개는 것은 S1·S5·백엔드 enum·')
    print('비용 회귀 더미를 모두 건드리는 모델링 결정이다 — CLAUDE.md 다음 할 일 ②·⑧.')


if __name__ == '__main__':
    main()
