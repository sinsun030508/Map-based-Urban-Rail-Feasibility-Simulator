"""
서울교통공사 혼잡도 수집 — 역 승하차를 '최대 단면 방향 통행량'으로 바꾸는 계수 산출.

출력
  data/build/line_congestion.csv   노선별 최대 단면 통행량·환산계수
  표준출력                          S7 에 넣을 계수

왜 필요한가
  수송능력 판정식이 이랬다.
      첨두 단면 = 일(승차+하차) × 첨두율 × peak_direction_ratio
  그런데 **역 승하차 합계는 단면 통행량이 아니다** (한 통행을 승차·하차로 두 번 센다).
  `peak_direction_ratio`(가정 0.6)가 방향 비중이 아니라 이 변환을 혼자 떠맡고 있어
  `mode_capacity.pphpd_min/max` 와 단위가 맞지 않았다. 실측으로 계수를 구한다.

어떻게 구하나
  혼잡도는 **교통카드 O/D 로 산출한 탑승인원** 기준이라 역 승하차가 아니라
  열차 안 재차인원이다. 즉 우리가 없던 단면 통행량이 여기 있다.
      pphpd = 혼잡도/100 × 정원(1량 160명) × 편성당칸수 × (60 ÷ 운행시격)
  노선별로 최대값을 잡고, 같은 노선의 일 승하차와 첨두율로 나누면 변환계수가 나온다.
      K = pphpd_최대 ÷ (노선 일 승하차 × 첨두율)

주의
  - 혼잡도는 **서울교통공사 1~8호선만** 있다. 9호선·경전철·광역철도는 없어서
    중전철로 구한 계수를 다른 수단에도 쓰게 된다
  - 운행현황에 **퇴근 시격이 없다.** 첨두가 18시인데 출근 시격을 대신 쓴다
    (저녁 러시 배차가 아침과 비슷하다는 가정)
  - 2호선 지선(성수·신정)은 편성·시격이 본선과 달라 과대 추정되지만,
    노선 최대값은 본선에서 나오므로 결과에는 영향이 없다
  - 1량 160명은 혼잡도 100% 기준이다. 좌석 54석 → 54/160 = 33.75% 가
    데이터셋 정의("좌석만 차면 34%")와 맞는 것으로 교차검증했다
"""

import io
import json
import re
from pathlib import Path
from urllib.request import urlopen

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'data' / 'build'
SEED = ROOT / 'data' / 'seed'
BASE = 'http://openapi.seoul.go.kr:8088'
SERVICE = 'subwConfusion'
PAGE = 1000

CAPACITY_PER_CAR = 160      # 혼잡도 100% 기준 1량 정원 (좌석 54 + 통로 54 + 출입문 52)

# 평일 설계 첨두까지의 보정은 **노선별** 실측을 쓴다 (etl/seoul_weekday_peak.py →
# line_weekday_peak.csv). 전 노선 공통값을 쓰면 노선마다 다른 첨두 쏠림이 K 에 섞여
# 연장과의 관계를 가린다 — 실제로 공통값일 때 r=0.775 이던 것이 노선별로 0.865 가 됐다.

# K 의 이론 상한. ①승하차 이중 계산(×0.5) ②방향 쏠림(실측 중앙값 0.79)까지만 적용한 값으로,
# **모든 통행이 최대 단면을 지나야** 도달한다. K 가 이보다 크면 분모가 결손된 것이다
# (직결 노선이 그렇다). 짧은 노선에서 K=a/L 이 발산하지 않게 잡아 주는 뚜껑이기도 하다.
DIRECTION_SHARE = 0.79
K_CEILING = 0.5 * DIRECTION_SHARE

# **직결 운행 노선은 K 가 과대로 나온다.** 다른 운영기관 구간에서 승차한 승객이
# 단면에는 실리지만 분모(서울교통공사 역 승하차)에는 없다. 계수 산출에서 뺀다.
THROUGH_RUNNING = {
    '1호선': '코레일 경부·경인·경원선 직결 (서울교통공사 구간이 7.8km뿐)',
    '3호선': '코레일 일산선 직결',
    '4호선': '코레일 안산·과천선 직결',
    '7호선': '인천 부평구청~석남 직결',
}


def load_key():
    for line in (ROOT / '.env').read_text(encoding='utf-8').splitlines():
        if line.startswith('SEOUL_OPENAPI_KEY='):
            return line.split('=', 1)[1].strip()
    raise SystemExit('SEOUL_OPENAPI_KEY 가 없습니다 (.env 확인)')


def fetch_all(key):
    rows = []
    while True:
        url = f'{BASE}/{key}/json/{SERVICE}/{len(rows) + 1}/{len(rows) + PAGE}/'
        with urlopen(url, timeout=60) as res:
            body = json.load(res)
        if SERVICE not in body:
            raise RuntimeError(f'응답 오류: {body.get("RESULT")}')
        payload = body[SERVICE]
        code = payload['RESULT']['CODE']
        if code != 'INFO-000':
            raise RuntimeError(f'{code}: {payload["RESULT"]["MESSAGE"]}')
        got = payload.get('row', [])
        rows.extend(got)
        if not got or len(rows) >= payload['list_total_count']:
            return rows


def load_line_peak():
    """노선별 평일 보정·첨두율. 없으면 멈춘다 — 공통값으로 조용히 되돌아가면 안 된다."""
    path = BUILD / 'line_weekday_peak.csv'
    if not path.exists():
        raise SystemExit(f'{path.name} 이 없습니다 - etl/seoul_weekday_peak.py 를 먼저 실행하세요')
    return pd.read_csv(path, encoding='utf-8-sig').set_index('line_name')


def load_operation():
    df = pd.read_csv(SEED / 'train_operation.csv')
    # 보유칸수 ÷ 편성수 = 편성당칸수 가 맞는지 본다. 어긋나면 베껴 적다 틀린 것이다
    check = (df.cars_owned / df.trains).round(0)
    bad = df[(check - df.cars_per_train).abs() > 0.5]
    if len(bad):
        raise SystemExit(f'train_operation.csv 편성당칸수 불일치:\n{bad}')
    return df.set_index('line')


def time_columns(rows):
    return [c for c in rows[0] if re.fullmatch(r'TIME\d{4}', c)]


def to_long(rows, ops):
    """역×방향×시간대 혼잡도를 pphpd 로 바꾼다. 평일만 쓴다."""
    cols = time_columns(rows)
    recs = []
    skipped = set()
    for r in rows:
        if r.get('DOW_SE') != '평일':
            continue
        line = r.get('LINE')
        if line not in ops.index:
            skipped.add(line)
            continue
        op = ops.loc[line]
        trains_per_hour = 60.0 / float(op.headway_peak_min)
        train_capacity = CAPACITY_PER_CAR * int(op.cars_per_train)
        for c in cols:
            raw = r.get(c)
            if raw in (None, '', '-'):
                continue
            try:
                pct = float(raw)
            except ValueError:
                continue
            if pct <= 0:
                continue
            recs.append({
                'line_name': line,
                'station_name': r.get('DPTRE_STTN'),
                'direction': r.get('UP_DOWN_SE'),
                'slot': c[4:6] + ':' + c[6:8],
                'congestion': pct,
                'pphpd': pct / 100 * train_capacity * trains_per_hour,
            })
    if skipped:
        print(f'  운행현황에 없는 호선 제외: {sorted(skipped)}')
    return pd.DataFrame(recs)


def main():
    ops = load_operation()
    print(f'운행현황 {len(ops)}개 호선 (편성당칸수·출근 시격 확인됨)')
    rows = fetch_all(load_key())
    print(f'혼잡도 {len(rows)}행 수신')

    long = to_long(rows, ops)
    print(f'평일 {long.line_name.nunique()}개 호선 × {len(long):,}개 (역·방향·시간대)')

    ride = pd.read_csv(BUILD / 'station_ridership.csv')
    daily = ride.groupby('line_name').daily_total.sum()
    peak = load_line_peak()

    out = []
    for line, g in long.groupby('line_name'):
        top = g.loc[g.pphpd.idxmax()]
        # 같은 역·같은 시간대의 반대 방향 — 진짜 방향 쏠림을 본다
        pair = g[(g.station_name == top.station_name) & (g.slot == top.slot)]
        share = top.congestion / pair.congestion.sum() if len(pair) > 1 else None
        total = daily.get(line)
        out.append({
            'line_name': line,
            'max_station': top.station_name,
            'direction': top.direction,
            'slot': top.slot,
            'congestion_pct': round(top.congestion, 1),
            'max_pphpd': round(top.pphpd),
            'direction_share': round(share, 3) if share else None,
            'daily_total': int(total) if total else None,
            'k_factor': round(top.pphpd / (total * peak.weekday_factor[line]
                                           * peak.peak_hour_ratio[line]), 4)
            if total and line in peak.index else None,
        })
    res = pd.DataFrame(out).sort_values('max_pphpd', ascending=False)

    BUILD.mkdir(parents=True, exist_ok=True)
    res.to_csv(BUILD / 'line_congestion.csv', index=False, encoding='utf-8-sig')
    print('\n' + res.to_string(index=False))

    res = res.assign(
        length_km=res.line_name.map(ops.length_km),
        through=res.line_name.map(THROUGH_RUNNING).fillna(''))
    clean = res[res.through == ''].dropna(subset=['k_factor'])
    k = res.k_factor.dropna()
    share = res.direction_share.dropna()
    print(f'\n환산계수 K  전체 8개 중앙값 {k.median():.4f}'
          f' / 범위 {k.min():.4f}~{k.max():.4f}')
    print(f'직결 제외 {len(clean)}개 ({", ".join(clean.line_name)})'
          f'  K 중앙값 {clean.k_factor.median():.4f}'
          f' / 범위 {clean.k_factor.min():.4f}~{clean.k_factor.max():.4f}')
    print('  직결 노선은 분모에 없는 승객이 단면에 실려 K 가 과대 - 계수에서 뺀다')
    corr = np.corrcoef(clean.k_factor, 1 / clean.length_km)[0, 1]
    print(f'  K 는 연장에 반비례한다 (r={corr:.2f}) - 단거리 노선은 과소 추정된다')
    print(f'\n실측 최대 단면 {res.max_pphpd.min():,}~{res.max_pphpd.max():,}명/시')
    print(f'  가장 낮은 6·8호선이 {res.max_pphpd.min():,}명/시다 -'
          ' mode_capacity 의 중전철 pphpd_min 은 이 실측에서 가져왔다')
    print(f'\n방향 쏠림  중앙값 {share.median():.3f}'
          f' / 범위 {share.min():.3f}~{share.max():.3f} (가정값 0.6 보다 크다)')
    print('→ line_congestion.csv')
    a = length_fit(clean)
    light_rail_floor(daily, a, clean.length_km.median())


def length_fit(clean):
    """
    K 를 연장의 함수로 세운다.  K = min(K_CEILING, a / 연장)

    왜 상수로 두면 안 되나
      이 계수는 30~60km 노선 4개로 재는데, 정작 서비스가 다루는 노선은 5~35km 다.
      긴 노선에서 잰 값을 짧은 노선에 그대로 쓰면 **단면을 과소 추정**한다.

    왜 1/L 인가
      통행이 노선보다 짧으면 한 지점을 지나는 통행의 비율은 대략 (평균 통행거리 / 연장)
      이다. 계수 둘짜리(a + b/L)도 재 봤지만 표본 4개에 과적합이라
      교차검증이 오히려 나빠졌다 (아래 표).

    상한
      모든 통행이 최대 단면을 지나도 K 는 0.5 × 방향쏠림을 넘을 수 없다.
      이 뚜껑이 없으면 짧은 노선에서 1/L 이 발산한다.
    """
    L, K = clean.length_km.values, clean.k_factor.values
    a = float(np.mean(K * L))

    def mae(predict, idx=None):
        rows = range(len(L)) if idx is None else idx
        return float(np.mean([abs(predict(L[i]) - K[i]) for i in rows]))

    candidates = {
        '상수 (중앙값)': lambda tr_L, tr_K: (lambda l: float(np.median(tr_K))),
        'K = a/L (채택)': lambda tr_L, tr_K: (
            lambda l: min(K_CEILING, float(np.mean(tr_K * tr_L)) / l)),
        'K = a + b/L': lambda tr_L, tr_K: (
            lambda p: (lambda l: min(K_CEILING, p[0] / l + p[1])))(np.polyfit(1 / tr_L, tr_K, 1)),
    }
    print()
    print(f'환산계수를 연장 함수로  (표본 {len(L)}개, 연장 {L.min():.1f}~{L.max():.1f}km)')
    print(f'{"모델":18s} {"적합 MAE":>9s} {"LOO 교차검증":>12s}')
    for name, build in candidates.items():
        full = build(L, K)
        loo = []
        for i in range(len(L)):
            m = np.ones(len(L), bool)
            m[i] = False
            loo.append(abs(build(L[m], K[m])(L[i]) - K[i]))
        print(f'{name:18s} {mae(full):9.4f} {float(np.mean(loo)):12.4f}')

    print(f'  a = {a:.2f}  (K x 연장: {", ".join(f"{v:.2f}" for v in K * L)})')
    print(f'  상한 {K_CEILING:.3f} = 0.5 x {DIRECTION_SHARE} (모든 통행이 최대 단면을 지날 때)')
    print(f'  상한에 닿는 연장 = {a / K_CEILING:.1f}km 이하')
    print('  S7 에 peak_direction_coef / peak_direction_max 로 넣는다')
    return a


def light_rail_floor(daily, a, metro_median_km):
    """
    경전철 하한의 근거. 혼잡도 자료가 1~8호선뿐이라 경전철은 실측할 수 없다.
    실제 운영 중인 경전철의 일 승하차에 첨두율·환산계수를 적용해 단면을 추정한다.
    추정이지만 "실제로 지어진 경전철이 이 정도"라는 근거는 된다.

    **경전철은 짧다.** 우이신설 11.0km·신림 7.8km 로, K = a/L 이 상한에 닿는 구간이다.
    상수 K 를 쓰던 이전 추정은 30~60km 노선에서 잰 값을 그대로 적용해 단면을 낮게 봤다.
    다만 상한 자체가 **표본 밖 외삽**이라, 이 값은 실측이 아니라 추정임을 분명히 둔다.
    """
    peak = load_line_peak()
    lengths = {'우이신설선': 11.0, '신림선': 7.8}      # data/seed/rail_speed.csv
    print()
    print('경전철 추정 단면 (혼잡도 자료가 없어 K 로 환산)')
    for line, km in lengths.items():
        total = daily.get(line)
        if total is None:
            print(f'  {line} 승하차 자료 없음')
            continue
        # 경전철은 1~8호선 자료에 없어 평일 보정·첨두율은 중전철 중앙값을 빌린다
        wf = float(peak.weekday_factor.median())
        ph = float(peak.peak_hour_ratio.median())
        k = min(K_CEILING, a / km)
        section = total * wf * ph * k
        capped = ' (상한)' if a / km > K_CEILING else ''
        print(f'  {line:8s} 연장 {km:4.1f}km  K {k:.4f}{capped}'
              f'  일 승하차 {int(total):,}명 → 단면 {section:,.0f}명/시')
    print(f'  (중전철 연장 중앙값 {metro_median_km:.1f}km 에서 잰 계수를 외삽한 값이다)')
    print('  mode_capacity 의 경전철 pphpd_min 은 이 추정에서 가져온다')


if __name__ == '__main__':
    main()
