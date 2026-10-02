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
PEAK_HOUR_RATIO = 0.0987    # etl/seoul_peak.py 실측 — S7 과 같은 값을 써야 한다

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
            'k_factor': round(top.pphpd / (total * PEAK_HOUR_RATIO), 4) if total else None,
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
    print(f'  6·8호선이 {res.max_pphpd.min():,}명/시로, 실제 운영 중인 중전철인데도'
          ' mode_capacity 의 pphpd_min 40,000 에 못 미친다')
    print(f'\n방향 쏠림  중앙값 {share.median():.3f}'
          f' / 범위 {share.min():.3f}~{share.max():.3f} (가정값 0.6 보다 크다)')
    print('→ line_congestion.csv')
    light_rail_floor(daily, clean.k_factor.median())


def light_rail_floor(daily, k):
    """
    경전철 하한의 근거. 혼잡도 자료가 1~8호선뿐이라 경전철은 실측할 수 없다.
    실제 운영 중인 경전철의 일 승하차에 첨두율·환산계수를 적용해 단면을 추정한다.
    추정이지만 "실제로 지어진 경전철이 이 정도"라는 근거는 된다.
    """
    print('\n경전철 추정 단면 (혼잡도 자료가 없어 K 로 환산)')
    for line in ('우이신설선', '신림선'):
        total = daily.get(line)
        if total is None:
            print(f'  {line} 승하차 자료 없음')
            continue
        section = total * PEAK_HOUR_RATIO * k
        print(f'  {line:8s} 일 승하차 {int(total):,}명 → 단면 {section:,.0f}명/시')
    print('  mode_capacity 의 경전철 pphpd_min 5,000 보다 낮다 -'
          ' 실제 경전철도 과잉 투자로 판정된다')


if __name__ == '__main__':
    main()
