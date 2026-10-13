"""
KTX 노선별 정차역 좌표 — 도시 간 거리 노선에 "기존 KTX 활용 검토" 를 안내하는 데 쓴다.

왜 필요한가
  기존 노선 활용 안내(`ExistingLineFinder`)는 수도권 전철과 지방 도시철도만 안다.
  인천~세종(120km) 같은 노선을 그리면 지하철·트램이 역 60~80개로 빽빽하게 찍히는데,
  정작 광명~오송을 잇는 KTX 가 이미 있다는 말은 못 했다.

출처 — 둘을 맞대어 쓴다
  ① **노선-역 관계**: 한국철도공사_KTX 노선별 역정보 (공공데이터포털 15127571, 파일)
     "이 역은 어느 KTX 노선 열차가 서는가" 의 공식 출처다.
     **순번을 선형 순서로 읽지 말 것** — 경부선이 `동대구 > 포항 > 경주 > 울산 > 경산`
     처럼 지선을 오간다. 노선은 "그 노선 열차가 서는 역의 모음" 으로만 쓴다.
  ② **좌표**: OpenStreetMap 역 노드. 공식 좌표(한국철도공사_역 위치 정보, 15127532)는
     **검증용으로만** 쓴다 — 품질이 낮아서다.

공식 좌표를 쓰지 않는 이유 (재 봤다)
  KTX 정차역 69곳 중 53곳은 OSM 과 1km 안에서 맞는다 — 두 출처가 서로를 받친다.
  그런데 **8곳이 크게 어긋나고 틀린 쪽은 공식 값이다**
    · `행신` 37.3645, 126.4952 — 인천 앞바다다 (실제 고양시, 40km 차이)
    · `구례구` 35.0, 127.0 / `나주` 35.01, 126.18 — 소수점이 잘린 자리표시자다
    · `마산`·`창원`·`창원중앙` — 경도가 128.3x 로 25km 씩 서쪽에 밀려 있다
  게다가 **10곳은 공식 좌표가 아예 없다**(`김천구미`, `울산(통도사)`, `여수EXPO` 등).
  OSM 좌표를 쓰고, 공식 좌표와 어긋나는 역은 `coord_check` 에 남겨 보고한다.

출력
  data/build/ktx_station.csv   line_name, station_name, latitude, longitude, coord_check

한계
  **SRT(수서·동탄·평택지제)는 없다** — 한국철도공사 자료라 SR 운행분이 빠진다. 같은
  고속선 위의 천안아산·오송 이남은 KTX 역으로 잡히므로 판정이 크게 흔들리지 않는다.
  무궁화·ITX 만 서는 일반철도역도 없다 (공식 노선별 정차역 자료가 없다).
"""

import json
import math
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / 'data' / 'raw'
BUILD = ROOT / 'data' / 'build'
LINE_FILE = RAW / '한국철도공사_KTX 노선별 역정보_20251121.csv'
LOCATION_FILE = RAW / '한국철도공사_역 위치 정보_20240401.csv'
# 전국 역 노드 한 번이면 된다 (bbox 조회라 가볍다). Overpass 본진이 504 를 자주 내므로
# 받은 것을 남겨 두고 다시 돌릴 때 쓴다 (osm_regional_stations.py 와 같은 이유)
OSM_CACHE = ROOT / 'data' / 'cache' / 'osm' / 'kr_rail_stations.json'
OSM_QUERY = '''[out:json][timeout:180];
node(33.0,124.5,38.7,130.0)["railway"~"^(station|halt)$"]["name"];
out body;'''

AGREE_M = 1000              # 공식과 OSM 이 이 안이면 같은 역으로 본다
SAME_PLACE_M = 200          # 같은 이름 노드가 이 안이면 한 역이다 (승강장·역사 노드가 따로 있다)

# 공식 표기 → OSM 표기. 괄호 부기는 따로 떼어 보므로(`진부(오대산)` → `진부`) 여기에는
# 괄호를 떼도 안 맞는 것만 둔다
ALIASES = {
    '김천구미': '김천(구미)',
    '여수EXPO': '여수엑스포',
    '울산(통도사)': '울산',
}


def meters(lat1, lon1, lat2, lon2):
    return math.hypot((lat1 - lat2) * 111_000,
                      (lon1 - lon2) * 111_000 * math.cos(math.radians(lat1)))


def osm_names(name):
    """공식 역명으로 찾아볼 OSM 이름들. 별칭 → 원래 이름 → 괄호 뗀 이름 순."""
    out = [ALIASES.get(name), name, re.sub(r'\(.*?\)', '', name).strip()]
    return [n for i, n in enumerate(out) if n and n not in out[:i]]


def load_osm():
    if not OSM_CACHE.exists():
        for attempt in range(8):
            try:
                req = urllib.request.Request(
                    'https://overpass-api.de/api/interpreter',
                    data=urllib.parse.urlencode({'data': OSM_QUERY}).encode(),
                    headers={'User-Agent': 'railfeas-student-project'})
                with urllib.request.urlopen(req, timeout=200) as res:
                    data = json.load(res)
                break
            except Exception as exc:                       # noqa: BLE001
                print(f'    재시도 {attempt + 1}/8 ({type(exc).__name__})')
                time.sleep(min(120, 20 * (attempt + 1)))
        else:
            raise SystemExit('Overpass 응답 없음 — 잠시 뒤 다시 돌리세요')
        OSM_CACHE.parent.mkdir(parents=True, exist_ok=True)
        OSM_CACHE.write_text(json.dumps(data, ensure_ascii=False), encoding='utf-8')
    return json.loads(OSM_CACHE.read_text(encoding='utf-8'))['elements']


def index_osm(elements):
    """
    이름 → 후보 좌표. **도시철도 역과 북한 역은 뺀다.**
    `양평` 은 경의중앙선 역과 서울 5호선 `양평`(영등포구)이 같은 이름이고,
    `판교` 는 북한 경원선에도 있다.

    단 `station=subway` 라도 **`train=yes` 면 남긴다** — Korail `동대구`·`경산` 이
    대경선 광역전철 때문에 subway 로 찍혀 있어, 처음엔 이 둘이 통째로 빠졌다.
    """
    by_name = {}
    for e in elements:
        tags = e.get('tags', {})
        urban = tags.get('station') in ('subway', 'light_rail', 'monorail')
        if urban and tags.get('train') != 'yes':
            continue
        if '조선민주주의인민공화국' in tags.get('operator', ''):
            continue
        for key in ('name', 'name:ko'):
            if tags.get(key):
                by_name.setdefault(tags[key], []).append((e['lat'], e['lon']))
    return by_name


def distinct_places(points):
    """같은 자리(승강장·역사 노드가 따로 찍힌 것)는 하나로 묶는다."""
    kept = []
    for p in points:
        if all(meters(*p, *k) > SAME_PLACE_M for k in kept):
            kept.append(p)
    return kept


def candidates(name, by_name):
    for n in osm_names(name):
        if by_name.get(n):
            return distinct_places(by_name[n])
    return []


def pick(cands, anchors):
    """
    후보가 여럿이면 **같은 노선의 다른 역들과 가장 가까운 것**을 고른다.
    `판교(경기)` 는 성남(중부내륙선)과 충남 서천(장항선)에 같은 이름의 역이 있는데,
    중부내륙선의 다른 역(부발·가남·충주)과 가까운 쪽이 맞다.
    """
    if len(cands) == 1 or not anchors:
        return cands[0]
    return min(cands, key=lambda c: min(meters(*c, *a) for a in anchors))


def resolve(lines, by_name, official):
    """
    노선별 역 좌표를 정한다. 후보가 하나인 역부터 정하고, 그걸 기준점으로
    후보가 여럿인 역을 고른다.
    반환: rows(list[dict]), 문제 목록(list[str])
    """
    rows, problems = [], []
    for line, names in lines.items():
        cands = {n: candidates(n, by_name) for n in names}
        anchors = [c[0] for c in cands.values() if len(c) == 1]
        for name in names:
            if not cands[name]:
                problems.append(f'{line} {name}: OSM 에서 못 찾음 — 별칭(ALIASES)을 추가하세요')
                continue
            lat, lon = pick(cands[name], anchors)
            off = official.get(name)
            if off is None:
                check = '공식 좌표 없음'
            else:
                gap = meters(lat, lon, *off)
                check = 'OK' if gap <= AGREE_M else f'공식 좌표와 {gap / 1000:.1f}km 차이'
            rows.append({'line_name': f'KTX {line}', 'station_name': name,
                         'latitude': round(lat, 6), 'longitude': round(lon, 6),
                         'coord_check': check})
    return rows, problems


def read_raw():
    lines_df = pd.read_csv(LINE_FILE, encoding='cp949')
    lines_df['역명'] = lines_df['역명'].str.strip()
    lines = {line: list(g.sort_values('순번')['역명'])
             for line, g in lines_df.groupby('노선명')}
    loc = pd.read_csv(LOCATION_FILE, encoding='cp949')
    loc['역명'] = loc['역명'].str.strip()
    official = dict(zip(loc['역명'], zip(loc['위도'], loc['경도'])))
    return lines, official


def main():
    lines, official = read_raw()
    rows, problems = resolve(lines, index_osm(load_osm()), official)
    if problems:
        print('\n'.join(problems))
        # 반쪽짜리 노선이 들어가면 "그 노선에 역이 없다" 로 조용히 빗나간다
        raise SystemExit(f'{len(problems)}개 역의 좌표를 못 정했습니다 — 저장하지 않았습니다')

    df = pd.DataFrame(rows).sort_values(['line_name', 'station_name'])
    BUILD.mkdir(parents=True, exist_ok=True)
    df.to_csv(BUILD / 'ktx_station.csv', index=False, encoding='utf-8-sig')

    stations = df.drop_duplicates('station_name')
    print(f'KTX {df.line_name.nunique()}개 노선 / 역 {len(stations)}곳 (노선별 {len(df)}행)')
    for check, n in stations.coord_check.map(
            lambda c: c if c in ('OK', '공식 좌표 없음') else '공식 좌표와 어긋남').value_counts().items():
        print(f'  {check}: {n}')
    for _, r in stations[~stations.coord_check.isin(['OK', '공식 좌표 없음'])].iterrows():
        print(f'    {r.station_name}: {r.coord_check} (OSM 좌표를 씀)')
    print('→ ktx_station.csv')


if __name__ == '__main__':
    sys.exit(main())
