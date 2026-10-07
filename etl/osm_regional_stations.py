"""
지방 도시철도 역 좌표 — OpenStreetMap Overpass 에서 받는다.

왜 OSM 인가
  기존 노선 활용 안내(`ExistingLineFinder`)가 쓰는 좌표는 수도권뿐이었다
  (`seoul_ridership.py` 산출, 39개 노선 784역). 서울 열린데이터광장은 서울만 주고,
  공공데이터포털·국가철도공단의 전국 역사 정보는 **서비스키가 필요**한데 이 프로젝트에는
  없다 (`.env` 의 KAKAO_REST_KEY 도 비어 있다). 키 없이 쓸 수 있고 노선-역 관계가
  구조화된 출처가 OSM 이다.

  **OSM 은 집단 편집 자료다.** 이 프로젝트는 출처를 따지므로 그대로 믿지 않고,
  국토교통부 운영현황의 역수(`reference_line.csv`)와 **대조해 검증한다.** 역수가 맞지
  않는 노선은 저장하지 않는다 — 반쪽짜리 좌표가 조용히 들어가는 것이 가장 나쁘다.

  쓰임이 "활용 검토" 안내까지라는 점도 감안했다. 좌표가 몇십 m 틀려도 반경 1km 판정은
  바뀌지 않고, 이 기능은 단정하지 않는다 (CLAUDE.md 기존 노선 활용 참고).

출력
  data/build/regional_station.csv   line_name, station_name, latitude, longitude

Overpass 에서 네 번 데였다 (같은 길을 다시 걷지 말 것)
  ① **미러가 틀린 빈 답을 빠르게 준다.** 같은 쿼리로 부산을 물었을 때 본진은 관계 8개,
     kumi.systems·osm.ch 는 0개였다. 본진을 먼저 쓰고 미러는 보조로만 둔다
  ② **행정구역(area) 조회가 광주에서 조용히 실패**했다. Overpass 는 영역을 못 찾아도
     오류 대신 빈 목록을 준다. 이름으로 물으면 나온다
  ③ 이름 정규식에 `route` 정규식을 **함께** 걸면 영역 제한이 없어 504 가 난다
  ④ **관계 멤버에는 이름이 없다.** 멤버 대부분이 `stop_position` 노드와 승강장 way 이고,
     정작 이름 붙은 `railway=station` 노드는 멤버가 아니다. 멤버만 긁었더니 부산 1호선이
     40역 중 19역밖에 안 나왔다. **선로(way) 주변의 역 노드**를 찾아야 전부 나온다
"""

import json
import math
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'data' / 'build'
# **노선별로 캐싱한다.** Overpass 본진이 504 를 자주 내서 한 번에 열 노선을 다 받기 어렵다.
# 캐시가 있으면 다시 돌릴 때 못 받은 노선만 물어 이어서 끝낼 수 있다
# (SGIS 응답을 data/cache/sgis 에 캐싱하는 것과 같은 이유).
CACHE = ROOT / 'data' / 'cache' / 'osm'

# **본진만 쓴다.** 미러는 빠르지만 영역 색인이 불완전해 **틀린 빈 결과를 성공처럼** 준다
# (위 ① 참고). 본진이 504 를 내면 기다렸다 다시 묻는다 - 느린 정답이 빠른 오답보다 낫다.
MIRRORS = ['https://overpass-api.de/api/interpreter']

STATION_RADIUS_M = 150      # 선로에서 이만큼 안의 역 노드를 그 노선 역으로 본다

# **관계 조회에 bbox 를 건다.** 영역 제한 없이 이름 정규식만 쓰면 Overpass 가 전 세계
# 관계를 훑어 504 가 난다 (슬롯이 비어 있어도 난다 - 속도 제한이 아니라 질의 비용 문제다).
# 도시를 넉넉히 감싸는 상자면 충분하다. 부산김해경전철은 김해까지 가므로 부산 상자를 넓게 잡았다.
CITY_BBOX = {
    '부산': (34.95, 128.75, 35.45, 129.35),
    '대구': (35.70, 128.35, 36.05, 128.85),
    '광주': (35.05, 126.65, 35.30, 127.00),
    '대전': (36.20, 127.25, 36.50, 127.60),
}


def bbox_of(line):
    for city, box in CITY_BBOX.items():
        if line.startswith(city):
            return box
    raise SystemExit(f'{line} 의 bbox 가 없습니다 - CITY_BBOX 에 추가하세요')

# 국토교통부 도시철도 운영현황 역수 (reference_line.csv). OSM 을 검증하는 기준이자
# **채택 명단**이다. OSM 에는 미개통 노선도 운영 노선처럼 올라와 있어(광주 2호선은 공사
# 중인데 route 관계가 있다) 명단 밖은 아예 받지 않는다. 없는 노선을 "기존 노선 활용
# 검토" 로 안내하면 잘못된 권고가 된다.
OFFICIAL_STATIONS = {
    '부산 도시철도 1호선': 40, '부산 도시철도 2호선': 43, '부산 도시철도 3호선': 17,
    '부산 도시철도 4호선': 14, '부산김해경전철': 21,
    '대구 도시철도 1호선': 35, '대구 도시철도 2호선': 29, '대구 도시철도 3호선': 30,
    '광주 도시철도 1호선': 20, '대전 도시철도 1호선': 22,
}

TOLERANCE = 2               # 역수가 이보다 더 어긋나면 그 노선은 쓰지 않는다


def ask(query, tries=8):
    """Overpass 에 묻는다. 본진 우선, 504 가 흔해 미러로 재시도한다."""
    last = None
    for attempt in range(tries):
        for host in MIRRORS:
            try:
                req = urllib.request.Request(
                    host, data=urllib.parse.urlencode({'data': query}).encode(),
                    headers={'User-Agent': 'railfeas-student-project'})
                with urllib.request.urlopen(req, timeout=180) as res:
                    return json.load(res)
            except Exception as exc:                       # noqa: BLE001
                last = f'{host.split("/")[2]}: {type(exc).__name__}'
        # 본진이 바쁘면 길게 기다린다. 504 가 몰려 오는 시간대가 있어서, 짧게 여러 번
        # 두드리는 것보다 느긋하게 기다리는 편이 결국 빠르다 (최대 2분)
        time.sleep(min(120, 15 * (attempt + 1)))
        print(f'    재시도 {attempt + 1}/{tries} ({last})')
    raise SystemExit(f'Overpass 응답 없음 - {last}')


def member_points(line):
    """관계 멤버 노드의 좌표만 받는다 (`out skel` - 태그 없이 가볍다)."""
    south, west, north, east = bbox_of(line)
    data = ask(f'''[out:json][timeout:180];
rel({south},{west},{north},{east})["type"="route"]["name"~"^{line}"];
node(r);
out skel;''')
    return [(e['lat'], e['lon']) for e in data['elements'] if 'lat' in e]


def named_stations_in(bbox):
    """bbox 안의 이름 있는 역 노드. bbox 조회는 Overpass 에서 싸다."""
    south, west, north, east = bbox
    data = ask(f'''[out:json][timeout:180];
node({south},{west},{north},{east})["railway"~"^(station|halt)$"];
out body;''')
    return [(e['tags']['name'], e['lat'], e['lon'])
            for e in data['elements'] if e.get('tags', {}).get('name')]


def meters(lat1, lon1, lat2, lon2):
    """짧은 거리라 평면 근사로 충분하다."""
    return math.hypot((lat1 - lat2) * 111_000,
                      (lon1 - lon2) * 111_000 * math.cos(math.radians(lat1)))


SAME_PLACE_M = 200          # 이보다 가까우면 같은 장소로 본다


def dedupe(found):
    """
    **같은 장소의 역을 하나로 묶는다.**

    선로 주변을 훑으면 나란히 있는 **일반철도 역까지 딸려 온다.** 실제로 대구 1호선에서
    코레일 `대구` 와 지하철 `대구역` 이 101m 거리로 둘 다 잡혀 역수가 하나 많았다
    (36 vs 공식 35). 국내 도시철도 역간격이 1km 안팎이라 200m 안이면 같은 자리로 봐도
    안전하다. 겹침 판정에는 영향이 없지만, 역수가 맞아야 자료를 믿을 수 있다.
    """
    kept = {}
    for name, (lat, lon) in found.items():
        if any(meters(lat, lon, klat, klon) <= SAME_PLACE_M
               for klat, klon in kept.values()):
            continue
        kept[name] = (lat, lon)
    return kept


def stations_of(line):
    """
    노선 하나의 역 좌표.

    **무거운 조회를 쪼개고 매칭은 로컬에서 한다.** 처음엔 `way(r)` 주변을 Overpass 가
    직접 훑게 했는데(`node(around.w:150)`), 45km 짜리 부산 2호선에서 504 가 나고
    미러는 빈 답을 줘 **0역**으로 끝났다. 세 단계로 나누면 같은 노선이 7초에 끝난다.

      ① 관계 멤버 노드의 **좌표만** 받는다 (태그 없이 - 가볍다)
      ② 그 좌표들의 bbox 안에서 **이름 있는 역 노드**를 받는다 (bbox 조회는 싸다)
      ③ 멤버 좌표에서 150m 안의 역만 남긴다 (파이썬에서 계산)

    ②가 bbox 라 옆 노선 역까지 딸려 오지만, ③에서 이 노선 선로 위의 것만 걸러진다.
    """
    points = member_points(line)
    if not points:
        return {}
    lats = [p[0] for p in points]
    lons = [p[1] for p in points]
    margin = 0.01                                  # 약 1km - 역이 bbox 가장자리에 걸리는 것 방지
    bbox = (min(lats) - margin, min(lons) - margin,
            max(lats) + margin, max(lons) + margin)

    found = {}
    for name, lat, lon in named_stations_in(bbox):
        if name in found:
            continue
        if any(meters(lat, lon, plat, plon) <= STATION_RADIUS_M for plat, plon in points):
            found[name] = (lat, lon)
    return dedupe(found)


def cached(line):
    """검증을 통과해 저장해 둔 결과. 없으면 None."""
    path = CACHE / f'{line}.json'
    if path.exists():
        return {k: tuple(v) for k, v in json.loads(path.read_text(encoding='utf-8')).items()}
    return None


def save_cache(line, found):
    CACHE.mkdir(parents=True, exist_ok=True)
    (CACHE / f'{line}.json').write_text(
        json.dumps(found, ensure_ascii=False), encoding='utf-8')


def main():
    rows, report = [], []
    for line, official in OFFICIAL_STATIONS.items():
        hit = cached(line)
        if hit is not None:
            found = dedupe(hit)          # 옛 캐시도 같은 규칙으로 정리한다
            print(f'{line} 캐시 사용 ({len(found)}역)')
            report.append((line, len(found), official, len(found) - official, True))
            for name, (lat, lon) in found.items():
                rows.append({'line_name': line, 'station_name': name,
                             'latitude': round(lat, 6), 'longitude': round(lon, 6)})
            continue

        print(f'{line} 조회')
        found = {}
        for attempt in range(3):
            found = stations_of(line)
            if abs(len(found) - official) <= TOLERANCE:
                break
            print(f'    {len(found)}역 (공식 {official}역) - 부분 응답으로 보고 다시 묻는다'
                  f' ({attempt + 1}/3)')
            time.sleep(8)
        gap = len(found) - official
        ok = abs(gap) <= TOLERANCE
        report.append((line, len(found), official, gap, ok))
        print(f'  {len(found)}역 / 공식 {official}역  {"OK" if ok else "불일치"}')
        if ok:
            save_cache(line, found)        # 통과한 것만 캐싱한다
            for name, (lat, lon) in found.items():
                rows.append({'line_name': line, 'station_name': name,
                             'latitude': round(lat, 6), 'longitude': round(lon, 6)})

    print()
    print('노선별 역수 (OSM vs 국토교통부 운영현황)')
    for line, got, official, gap, ok in report:
        mark = 'OK' if ok else f'차이 {gap:+d}  <<< 채택 안 함'
        print(f'  {line:24s} {got:3d}역   공식 {official:3d}역   {mark}')

    bad = [r for r in report if not r[4]]
    if bad:
        print()
        print(f'**역수가 맞지 않는 노선 {len(bad)}종은 저장하지 않았다.**')
        print('  OSM 이 집단 편집 자료라 누락이 있을 수 있다. 반쪽짜리 좌표를 넣으면')
        print('  "기존 노선 활용" 판정이 조용히 빗나가므로, 맞는 노선만 쓴다')

    if not rows:
        raise SystemExit('검증을 통과한 노선이 없습니다 - 저장하지 않았습니다')

    df = pd.DataFrame(rows).sort_values(['line_name', 'station_name'])
    BUILD.mkdir(parents=True, exist_ok=True)
    df.to_csv(BUILD / 'regional_station.csv', index=False, encoding='utf-8-sig')
    print()
    print(f'역 {len(df)}개 / 노선 {df.line_name.nunique()}종 저장'
          f' (검증 통과 {len(report) - len(bad)}/{len(report)})')
    print('→ regional_station.csv')


if __name__ == '__main__':
    sys.exit(main())
