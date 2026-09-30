"""
서울 열린데이터광장 수집 — 지하철 역별 승하차 + 역 좌표.

출력
  data/build/station_master.csv     역 좌표 (BLDN_NM, ROUTE, LAT, LOT)
  data/build/station_ridership.csv  역별 일평균 승하차 (수집 기간 평균)

왜 일평균인가
  하루치만 쓰면 그날의 날씨·행사에 끌려간다. 요일 구성이 고르도록
  연속 N일(기본 28일)을 받아 역별로 평균한다.

주의
  - 승하차는 교통카드 기준이라 현금 이용은 빠진다
  - 서울 자료라 지방에 그대로 쓰면 과대추정이 난다. 지방은 KRIC 엑셀로 보강할 것
  - 역명이 출처마다 다르다 (승하차 '서울역' vs 좌표 '서울역'은 같지만
    '이수'/'총신대입구' 같은 별칭이 있다). 매칭 실패 건은 그대로 남겨 보고한다
"""

import os
import re
import sys
import time
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path
from urllib.request import urlopen
import json

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'data' / 'build'
BASE = 'http://openapi.seoul.go.kr:8088'
PAGE = 1000          # API 1회 최대 건수
DEFAULT_DAYS = 28


# 역명이 바뀐 건 — 승하차 자료는 새 이름, 좌표 자료는 옛 이름을 쓴다.
# 같은 역임을 확인한 것만 넣는다. 추측으로 채우지 말 것.
RENAMED = {
    '평택지제': '지제',        # 2021년 개명, 경부선 평택
}


def normalize(name):
    """
    '낙성대(강감찬)' → '낙성대'.
    승하차 자료에는 부역명이 붙고 좌표 자료에는 안 붙어 매칭이 깨진다.
    '잠실(송파구청)' 처럼 괄호가 역명의 일부인 경우도 없어 일괄 제거해도 안전하다.
    """
    base = re.sub(r'\s*\(.*?\)', '', str(name)).strip()
    return RENAMED.get(base, base)


def load_key():
    key = os.environ.get('SEOUL_OPENAPI_KEY')
    if not key:
        env = ROOT / '.env'
        if env.exists():
            for line in env.read_text(encoding='utf-8').splitlines():
                if line.startswith('SEOUL_OPENAPI_KEY='):
                    key = line.split('=', 1)[1].strip()
    if not key:
        raise SystemExit('SEOUL_OPENAPI_KEY 가 없습니다 (.env 확인)')
    return key


def fetch(key, service, start, end, suffix=''):
    url = f'{BASE}/{key}/json/{service}/{start}/{end}/{suffix}'
    with urlopen(url, timeout=30) as res:
        body = json.load(res)
    if service not in body:
        raise RuntimeError(f'{service} 응답 오류: {body.get("RESULT")}')
    payload = body[service]
    code = payload['RESULT']['CODE']
    if code != 'INFO-000':
        raise RuntimeError(f'{service} 오류 {code}: {payload["RESULT"]["MESSAGE"]}')
    return payload.get('row', []), payload['list_total_count']


def fetch_all(key, service, suffix=''):
    rows, total = fetch(key, service, 1, PAGE, suffix)
    while len(rows) < total:
        more, _ = fetch(key, service, len(rows) + 1, len(rows) + PAGE, suffix)
        if not more:
            break
        rows.extend(more)
    return rows


def collect_master(key):
    rows = fetch_all(key, 'subwayStationMaster')
    df = pd.DataFrame(rows).rename(columns={
        'BLDN_ID': 'station_code', 'BLDN_NM': 'station_name',
        'ROUTE': 'line_name', 'LAT': 'latitude', 'LOT': 'longitude'})
    df['latitude'] = pd.to_numeric(df.latitude, errors='coerce')
    df['longitude'] = pd.to_numeric(df.longitude, errors='coerce')
    df['station_key'] = df.station_name.map(normalize)
    return df.dropna(subset=['latitude', 'longitude'])


def collect_ridership(key, days):
    """최근 days 일. 오늘 자료는 아직 없으므로 5일 전부터 거슬러 올라간다."""
    totals = defaultdict(lambda: {'on': 0, 'off': 0, 'days': 0})
    collected = 0
    day = date.today() - timedelta(days=5)
    while collected < days:
        ymd = day.strftime('%Y%m%d')
        try:
            rows = fetch_all(key, 'CardSubwayStatsNew', ymd)
        except RuntimeError as e:
            print(f'  {ymd} 건너뜀 — {e}')
            day -= timedelta(days=1)
            continue
        if not rows:
            day -= timedelta(days=1)
            continue
        for r in rows:
            key_ = (r['SBWY_ROUT_LN_NM'], r['SBWY_STNS_NM'])
            totals[key_]['on'] += int(float(r['GTON_TNOPE']))
            totals[key_]['off'] += int(float(r['GTOFF_TNOPE']))
            totals[key_]['days'] += 1
        collected += 1
        print(f'  {ymd} {len(rows)}역')
        day -= timedelta(days=1)
        time.sleep(0.2)          # 연속 호출 간격

    rows = [{'line_name': line, 'station_name': name,
             'daily_boarding': round(v['on'] / v['days']),
             'daily_alighting': round(v['off'] / v['days']),
             'sample_days': v['days']}
            for (line, name), v in totals.items()]
    df = pd.DataFrame(rows)
    df['station_key'] = df.station_name.map(normalize)
    df['daily_total'] = df.daily_boarding + df.daily_alighting
    return df.sort_values('daily_total', ascending=False)


def main():
    days = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_DAYS
    key = load_key()
    BUILD.mkdir(parents=True, exist_ok=True)

    print('[역 좌표]')
    master = collect_master(key)
    master.to_csv(BUILD / 'station_master.csv', index=False, encoding='utf-8-sig')
    print(f'  {len(master)}건 → station_master.csv')

    print(f'[승하차 — 최근 {days}일]')
    ridership = collect_ridership(key, days)

    # 좌표를 붙인다. 붙지 않은 역은 수요 회귀에서 빠지므로 그대로 보고한다
    coords = (master.groupby('station_key')[['latitude', 'longitude']]
              .first().reset_index())
    merged = ridership.merge(coords, on='station_key', how='left')
    merged.to_csv(BUILD / 'station_ridership.csv', index=False, encoding='utf-8-sig')
    print(f'  {len(merged)}건 → station_ridership.csv')

    # 좌표가 없는 역은 수요 회귀에서 빠진다. 서울시 좌표 자료가 서울 시계 안쪽
    # 위주라 경기권 일부와 최근 신설역(자양·한국항공대)이 비어 있다.
    # 국가철도공단 역 좌표는 공공데이터포털 로그인이 필요해 아직 보강하지 못했다.
    missing = merged[merged.latitude.isna()]
    print(f'\n좌표 없음 {len(missing)}건 / 전체 {len(merged)}건'
          f' (일 이용객 합계 {int(missing.daily_total.sum()):,}명)')
    if len(missing):
        print(missing[['line_name', 'station_name', 'daily_total']].to_string(index=False))
    print()
    print(merged.head(5)[['line_name', 'station_name', 'daily_total',
                          'latitude', 'longitude']].to_string(index=False))


if __name__ == '__main__':
    sys.stdout.reconfigure(errors='replace')
    main()
