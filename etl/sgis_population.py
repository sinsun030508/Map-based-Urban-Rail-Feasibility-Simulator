"""
SGIS 집계구 인구 수집 — 역 반경 1km 인구를 구해 수요 회귀의 설명변수를 만든다.

왜 집계구인가
  SGIS 로 받을 수 있는 가장 촘촘한 단위다 (인구 약 500명). 도심은 수십~수백 m 라
  250m 격자보다 촘촘하고 농촌은 알아서 넓어진다. 인구가 적다고 값이 가려지지도 않는다.

흐름
  1) station_ridership.csv 의 역 좌표로 시군구를 알아낸다 (addr/rgeocodewgs84)
  2) 시군구별 집계구 인구를 한 번에 받는다 (stats/searchpopulation, low_search=2)
  3) 행정동별 집계구 경계를 받아 중심점을 구한다 (boundary/statsarea, 동 코드 필수)
  4) 좌표를 UTM-K(EPSG:5179) → WGS84 로 바꾸고 역 반경 1km 인구를 합산한다

출력
  data/build/census_population.csv  집계구 중심점 + 인구
  data/build/station_population.csv 역별 반경 1km 인구

주의
  - 거주 인구만 센다. 강남·종로처럼 주간인구가 많은 곳은 낮게 잡힌다
  - 응답은 data/cache/sgis 에 저장해 재실행 시 다시 부르지 않는다.
    인구 기준연도를 바꾸려면 캐시를 지울 것
"""

import json
import sys
import time
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import urlopen

import pandas as pd
from pyproj import Transformer

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'data' / 'build'
CACHE = ROOT / 'data' / 'cache' / 'sgis'
BASE = 'https://sgisapi.mods.go.kr/OpenAPI3'
YEAR = 2023                  # 집계구 인구 기준연도
RADIUS_KM = 1.0              # 노선 버퍼 반경 — CLAUDE.md 기준
CBD = (37.5665, 126.9780)    # 서울시청 — 도심 접근성 대리변수
EARTH_R = 6371.0088

# SGIS 경계는 UTM-K 로 온다
TO_WGS84 = Transformer.from_crs('EPSG:5179', 'EPSG:4326', always_xy=True)


def load_env():
    env = {}
    path = ROOT / '.env'
    if path.exists():
        for line in path.read_text(encoding='utf-8').splitlines():
            if '=' in line and not line.startswith('#'):
                k, v = line.split('=', 1)
                env[k.strip()] = v.split('#')[0].strip()
    return env


def get_token(env):
    url = (f'{BASE}/auth/authentication.json?consumer_key={env["SGIS_CONSUMER_KEY"]}'
           f'&consumer_secret={env["SGIS_CONSUMER_SECRET"]}')
    with urlopen(url, timeout=30) as res:
        body = json.load(res)
    if body.get('errCd') != 0:
        raise SystemExit(f'SGIS 인증 실패: {body.get("errMsg")}')
    return body['result']['accessToken']


def call(token, path, params, cache_key):
    """응답을 파일에 캐싱한다 — 역 619개면 호출이 수백 번이라 재실행 비용이 크다."""
    cached = CACHE / f'{cache_key}.json'
    if cached.exists():
        return json.loads(cached.read_text(encoding='utf-8'))

    url = f'{BASE}/{path}?' + urlencode({'accessToken': token, **params})
    # 수백 번 부르다 보면 간헐적으로 연결이 끊긴다. 몇 번 다시 시도한다
    for attempt in range(4):
        try:
            with urlopen(url, timeout=60) as res:
                body = json.load(res)
            break
        except Exception as e:
            if attempt == 3:
                raise
            wait = 2 ** attempt
            print(f'    재시도 {attempt + 1}/3 ({wait}s) — {e}', flush=True)
            time.sleep(wait)

    cached.parent.mkdir(parents=True, exist_ok=True)
    cached.write_text(json.dumps(body, ensure_ascii=False), encoding='utf-8')
    time.sleep(0.15)
    return body


def dong_of(token, lng, lat):
    """좌표 → (시군구 코드, 행정동 코드). SGIS 는 자체 코드 체계를 쓴다."""
    body = call(token, 'addr/rgeocodewgs84.json',
                {'x_coor': lng, 'y_coor': lat, 'addr_type': 20},
                f'rgeo_{lng:.5f}_{lat:.5f}')
    rows = body.get('result') or []
    if not rows:
        return None, None
    r = rows[0]
    return r['sido_cd'] + r['sgg_cd'], r['sido_cd'] + r['sgg_cd'] + r['emdong_cd']


def workers_of_sgg(token, sgg_cd):
    """
    시군구 → {행정동코드: 종사자수}.
    거주 인구만으로는 서울역·을지로입구 같은 업무지구를 설명하지 못한다.
    집계구가 아니라 행정동 단위라 해상도는 낮다.
    """
    body = call(token, 'stats/company.json',
                {'year': 2022, 'adm_cd': sgg_cd}, f'wrk_{sgg_cd}')
    out = {}
    for r in body.get('result') or []:
        value = str(r.get('tot_worker', '')).strip()
        if value.isdigit():
            out[r['adm_cd']] = int(value)
    return out


def sgg_of(token, lng, lat):
    """좌표 → 시군구 코드 (시도2 + 시군구3). 실패하면 None."""
    body = call(token, 'addr/rgeocodewgs84.json',
                {'x_coor': lng, 'y_coor': lat, 'addr_type': 20},
                f'rgeo_{lng:.5f}_{lat:.5f}')
    rows = body.get('result') or []
    if not rows:
        return None
    r = rows[0]
    return r['sido_cd'] + r['sgg_cd']


def dong_list(token, sgg_cd):
    """시군구 → 행정동 코드 목록 (경계 API 가 동 코드만 받는다)."""
    body = call(token, 'stats/searchpopulation.json',
                {'year': YEAR, 'adm_cd': sgg_cd, 'low_search': 1},
                f'dong_{sgg_cd}')
    return [r['adm_cd'] for r in body.get('result') or []]


def population_of_sgg(token, sgg_cd):
    """시군구 → {집계구코드: 인구}. 한 번에 받아 호출을 줄인다."""
    body = call(token, 'stats/searchpopulation.json',
                {'year': YEAR, 'adm_cd': sgg_cd, 'low_search': 2},
                f'pop_{sgg_cd}')
    # 인구가 적은 집계구는 'N/A' 로 가려서 온다. 0 으로 채우지 말고 빼야
    # 반경 합산이 과소·과대되지 않는다 (없는 값과 0 은 다르다)
    out = {}
    for r in body.get('result') or []:
        value = str(r.get('population', '')).strip()
        if value.isdigit():
            out[r['adm_cd']] = int(value)
    return out


def centroids_of_dong(token, dong_cd):
    """행정동 → [(집계구코드, 위도, 경도)]. 경계의 무게중심을 대표점으로 쓴다."""
    body = call(token, 'boundary/statsarea.geojson',
                {'year': YEAR, 'adm_cd': dong_cd}, f'bnd_{dong_cd}')
    out = []
    for feature in body.get('features') or []:
        rings = feature['geometry']['coordinates']
        points = [p for ring in rings for p in (ring if isinstance(ring[0][0], float) else ring[0])]
        if not points:
            continue
        x = sum(p[0] for p in points) / len(points)
        y = sum(p[1] for p in points) / len(points)
        lng, lat = TO_WGS84.transform(x, y)
        out.append((feature['properties'].get('adm_cd'), lat, lng))
    return out


def distance_km(lat1, lng1, lat2, lng2):
    from math import radians, sin, cos, asin, sqrt
    dlat = radians(lat2 - lat1)
    dlng = radians(lng2 - lng1)
    a = sin(dlat / 2) ** 2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(dlng / 2) ** 2
    return 2 * EARTH_R * asin(sqrt(a))


def main():
    env = load_env()
    token = get_token(env)
    stations = pd.read_csv(BUILD / 'station_ridership.csv')
    stations = stations[stations.latitude.notna()].reset_index(drop=True)
    print(f'역 {len(stations)}개', flush=True)

    # 1) 역이 속한 시군구·행정동 모으기
    sggs = set()
    dong_by_station = {}
    for _, st in stations.iterrows():
        sgg, dong = dong_of(token, st.longitude, st.latitude)
        if sgg:
            sggs.add(sgg)
            dong_by_station[(st.line_name, st.station_name)] = dong
    print(f'시군구 {len(sggs)}개', flush=True)

    # 2~3) 시군구별 인구 + 동별 경계 중심점
    cells = []
    for i, sgg in enumerate(sorted(sggs), 1):
        pops = population_of_sgg(token, sgg)
        for dong in dong_list(token, sgg):
            for adm_cd, lat, lng in centroids_of_dong(token, dong):
                if adm_cd in pops:
                    cells.append({'adm_cd': adm_cd, 'latitude': lat,
                                  'longitude': lng, 'population': pops[adm_cd]})
        print(f'  [{i}/{len(sggs)}] {sgg} — 누적 집계구 {len(cells)}', flush=True)

    census = pd.DataFrame(cells).drop_duplicates('adm_cd')
    census.to_csv(BUILD / 'census_population.csv', index=False, encoding='utf-8-sig')
    print(f'집계구 {len(census)}개 → census_population.csv')

    # 4) 종사자 수 (행정동 단위) — 업무지구 보정용
    workers = {}
    for sgg in sorted(sggs):
        workers.update(workers_of_sgg(token, sgg))
    print(f'종사자 자료 {len(workers)}개 동', flush=True)

    # 동 단위 종사자에 좌표를 붙인다 — 집계구 코드 앞 8자리가 동 코드다.
    # 백엔드가 임의 노선의 주간인구를 추정할 때 쓴다
    census['dong'] = census.adm_cd.str[:8]
    dong_xy = census.groupby('dong')[['latitude', 'longitude']].mean()
    dong_rows = [{'dong_code': code, 'latitude': xy.latitude,
                  'longitude': xy.longitude, 'workers': workers[code]}
                 for code, xy in dong_xy.iterrows() if code in workers]
    pd.DataFrame(dong_rows).to_csv(BUILD / 'dong_workers.csv',
                                   index=False, encoding='utf-8-sig')
    print(f'동별 종사자 {len(dong_rows)}개 → dong_workers.csv', flush=True)

    # 환승 노선 수 — 같은 역명이 여러 노선에 나오면 환승역이다
    transfers = stations.groupby('station_name').line_name.nunique()

    # 5) 역 반경 1km 인구 + 부가 변수
    rows = []
    for _, s in stations.iterrows():
        near = census[(census.latitude.sub(s.latitude).abs() < 0.012)
                      & (census.longitude.sub(s.longitude).abs() < 0.015)]
        total = sum(c.population for c in near.itertuples()
                    if distance_km(s.latitude, s.longitude, c.latitude, c.longitude) <= RADIUS_KM)
        dong = dong_by_station.get((s.line_name, s.station_name))
        rows.append({'line_name': s.line_name, 'station_name': s.station_name,
                     'latitude': s.latitude, 'longitude': s.longitude,
                     'dong_code': dong,
                     'population_1km': total,
                     'workers_dong': workers.get(dong),
                     'transfer_lines': int(transfers[s.station_name]),
                     'cbd_distance_km': round(
                         distance_km(s.latitude, s.longitude, *CBD), 2),
                     'daily_total': s.daily_total})
    result = pd.DataFrame(rows)
    result.to_csv(BUILD / 'station_population.csv', index=False, encoding='utf-8-sig')

    zero = (result.population_1km == 0).sum()
    print(f'역별 인구 → station_population.csv (인구 0인 역 {zero}개)')
    print(result.nlargest(5, 'population_1km')[
        ['station_name', 'population_1km', 'daily_total']].to_string(index=False))


if __name__ == '__main__':
    sys.stdout.reconfigure(errors='replace')
    main()
