"""
평일 첨두율 — 서울 역별 **일별** 시간대별 승하차로 평일과 주말을 갈라 잰다.

왜 또 재나
  `seoul_peak.py` 가 쓰는 월 단위 자료(`CardSubwayTime`)에는 날짜가 없어 주말이 섞인
  첨두율(0.0987)밖에 못 구한다. 수송능력 판정에 필요한 것은 **평일 설계 첨두**다.
  지금은 그 차이를 단면 환산계수 K 가 통째로 흡수하고 있어, K 가 무엇을 담고 있는지
  분리되지 않는다 (CLAUDE.md B/C 산출 참고).

자료
  서울 열린데이터광장 `subwSttn` — 수송일자·호선·역·승하차구분·시간대별 (1~8호선).
  2024년(1~199,423행)과 2023년(199,424~)이 각각 날짜 오름차순으로 붙어 있고
  **날짜로 거를 수 없다.** 그래서 이분 탐색으로 원하는 달의 시작 위치를 찾아 그 구간만 받는다.

주의
  - **공휴일이 없는 달을 고른다.** 공휴일이 평일로 잡히면 첨두가 뭉개진다.
    기본값 2024-11 은 법정공휴일이 없다
  - 첨두율은 수요 모델과 기준을 맞추려 (승차+하차)로 센다
"""

import io
import json
import sys
from datetime import date
from pathlib import Path
from urllib.request import urlopen

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'data' / 'build'
BASE = 'http://openapi.seoul.go.kr:8088'
SERVICE = 'subwSttn'
PAGE = 1000

TARGET_MONTH = '2024-11'      # 법정공휴일 없음
YEAR_2024_END = 199_423       # 이 뒤부터 2023년이 다시 시작한다
HOURS = ['HR06_BFR'] + [f'HR{h:02d}' for h in range(6, 25)]


def load_key():
    for line in (ROOT / '.env').read_text(encoding='utf-8').splitlines():
        if line.startswith('SEOUL_OPENAPI_KEY='):
            return line.split('=', 1)[1].strip()
    raise SystemExit('SEOUL_OPENAPI_KEY 가 없습니다 (.env 확인)')


def fetch(key, start, end):
    url = f'{BASE}/{key}/json/{SERVICE}/{start}/{end}/'
    with urlopen(url, timeout=60) as res:
        body = json.load(res)
    payload = body.get(SERVICE)
    if not payload:
        raise RuntimeError(f'응답 오류: {body.get("RESULT")}')
    code = payload['RESULT']['CODE']
    if code != 'INFO-000':
        raise RuntimeError(f'{code}: {payload["RESULT"]["MESSAGE"]}')
    return payload.get('row', [])


def date_at(key, index):
    rows = fetch(key, index, index)
    return rows[0]['MVMN_YMD'] if rows else None


def first_index_of(key, month, lo=1, hi=YEAR_2024_END):
    """그 달의 첫 행 위치를 이분 탐색한다 (2024년 블록은 날짜 오름차순)."""
    target = month + '-01'
    while lo < hi:
        mid = (lo + hi) // 2
        got = date_at(key, mid)
        if got is None or got < target:
            lo = mid + 1
        else:
            hi = mid
    return lo


def collect_month(key, month):
    start = first_index_of(key, month)
    print(f'  {month} 시작 위치 {start:,}')
    rows = []
    index = start
    while True:
        batch = fetch(key, index, index + PAGE - 1)
        if not batch:
            break
        rows.extend(r for r in batch if r['MVMN_YMD'].startswith(month))
        if batch[-1]['MVMN_YMD'][:7] > month:
            break
        index += PAGE
        print(f'    {len(rows):,}행', end='\r', flush=True)
    print(f'    {len(rows):,}행 수집      ')
    return rows


def summarize(rows):
    """날짜별로 (승차+하차)를 시간대에 모으고 첨두 비중을 낸다."""
    per_day = {}
    for r in rows:
        day = per_day.setdefault(r['MVMN_YMD'], {h: 0.0 for h in HOURS})
        for h in HOURS:
            day[h] += float(r.get(h) or 0)

    recs = []
    for ymd, hours in sorted(per_day.items()):
        total = sum(hours.values())
        if total <= 0:
            continue
        peak_hour = max(hours, key=hours.get)
        y, m, d = (int(x) for x in ymd.split('-'))
        recs.append({
            'date': ymd,
            'weekday': date(y, m, d).weekday(),        # 0=월 … 6=일
            'is_weekday': date(y, m, d).weekday() < 5,
            'day_total': round(total),
            'peak_hour': peak_hour,
            'peak_ratio': round(hours[peak_hour] / total, 4),
        })
    return pd.DataFrame(recs)


def main():
    month = sys.argv[1] if len(sys.argv) > 1 else TARGET_MONTH
    key = load_key()
    print(f'일별 시간대별 승하차 수집 ({month})')
    df = summarize(collect_month(key, month))
    if df.empty:
        raise SystemExit('수집된 자료가 없습니다')

    BUILD.mkdir(parents=True, exist_ok=True)
    df.to_csv(BUILD / 'weekday_peak.csv', index=False, encoding='utf-8-sig')

    wd = df[df.is_weekday]
    we = df[~df.is_weekday]
    print(f'\n날짜 {len(df)}일 (평일 {len(wd)} / 주말 {len(we)})')
    print(f'평일 첨두율  평균 {wd.peak_ratio.mean():.4f}  중앙값 {wd.peak_ratio.median():.4f}')
    print(f'주말 첨두율  평균 {we.peak_ratio.mean():.4f}  중앙값 {we.peak_ratio.median():.4f}')
    print(f'전체 첨두율  평균 {df.peak_ratio.mean():.4f}')
    print('\n첨두 시각 분포')
    print(df.groupby(['is_weekday', 'peak_hour']).size().to_string())
    print(f'\n평일 ÷ 전체 = {wd.peak_ratio.mean() / df.peak_ratio.mean():.3f}')
    print('  이 배율이 지금 단면 환산계수 K 가 떠안고 있는 평일 보정분이다')
    print('→ weekday_peak.csv')


if __name__ == '__main__':
    main()
