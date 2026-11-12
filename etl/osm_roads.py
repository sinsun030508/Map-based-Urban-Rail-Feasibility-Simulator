"""
큰길 중심선 — OpenStreetMap Overpass 에서 받는다. 정차역을 가장 가까운 큰길 위로 옮기는 데
쓴다(`RoadSnap`, 11-12 사용자 요청).

왜 필요한가
  정차역은 반경 500m 인구가 많은 쪽으로 끌려가다 보니 **아파트 단지 한가운데**(노량진2동 — 동 사이)에
  놓였다. 실제 역은 큰길 밑(지하철)·위(고가)나 도로 위(BRT·트램)에 있다.

무엇을 받나 (`CLASSES`)
  trunk·primary·secondary·tertiary — 간선·보조간선·집산도로. **residential·service 는 뺀다** — 단지 안
  통로와 골목이라 역을 옮겨 봐야 또 단지 안이다. **motorway 와 `motorroad=yes`(자동차전용도로)도 뺀다** —
  역을 둘 수 없다. **교량·터널 토막도 뺀다**(`bridge`·`tunnel`) — 한강 다리 위나 산 터널 속으로 역이 가면 안 된다.
  인도는 받지 않는다 — 한국은 OSM 에 인도(`sidewalk`·`footway=sidewalk`)가 거의 그려져 있지 않아,
  중심선이 실제로 쓸 수 있는 가장 가까운 자리다.

Overpass 에서 데인 것 (osm_water.py 와 같다)
  ① 본진만 쓴다 — 미러는 틀린 빈 답을 성공처럼 준다
  ② 본진이 504 를 자주 낸다 — 칸마다 캐시하고, 기다렸다 다시 묻는다
  ③ User-Agent 가 없으면 406 이다
  ④ **수도권 1°×1° 칸은 18MB 에서 끊겼다**(IncompleteRead) — 칸을 0.5° 로 4등분하고(`HALF`), 교량·터널·자동차전용도로는
     Overpass 쪽에서 걸러 받는다(받은 뒤 `usable` 로 한 번 더 거른다)

출력
  data/build/roads.csv.gz   한 줄에 도로 토막 하나 — `위도,경도 위도,경도 …` (소수 5자리 ≈ 1m).
                            UTM-K 에서 SIMPLIFY_M 오차로 줄인다

    python etl/osm_roads.py               # 받고 만든다
    python etl/osm_roads.py --from-cache  # 받은 칸만으로 만든다
"""

import gzip
import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

from pyproj import Transformer
from shapely.geometry import LineString
from shapely.ops import transform

from osm_water import TILES, UA, URL

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'data' / 'build'
CACHE = ROOT / 'data' / 'cache' / 'osm' / 'roads'
OUT = BUILD / 'roads.csv.gz'

CLASSES = ('trunk', 'primary', 'secondary', 'tertiary')
SIMPLIFY_M = 5          # 역을 옮길 자리라 몇 m 만 틀어져도 된다 — 줄이지 않으면 점이 몇 배다
NO = ('no', '')         # bridge·tunnel·motorroad 가 이 값이면 「아니다」

_TO_M = Transformer.from_crs('EPSG:4326', 'EPSG:5179', always_xy=True).transform
_TO_LL = Transformer.from_crs('EPSG:5179', 'EPSG:4326', always_xy=True).transform


def usable(tags):
    """역을 둘 수 있는 큰길인가 — 등급, 자동차전용도로, 교량·터널."""
    if tags.get('highway') not in CLASSES:
        return False
    for key in ('motorroad', 'bridge', 'tunnel'):
        if tags.get(key, 'no') not in NO:
            return False
    return True


def roads(elements, simplify_m=SIMPLIFY_M):
    """OSM 요소들 → [[(위도, 경도), …], …]. 같은 way 는 한 번만, 점이 둘 미만이면 뺀다."""
    seen, out = set(), []
    for e in elements:
        if e.get('type') != 'way' or e['id'] in seen or not usable(e.get('tags', {})):
            continue
        seen.add(e['id'])
        pts = [(p['lon'], p['lat']) for p in e.get('geometry', []) if p]
        if len(pts) < 2:
            continue
        line = LineString(pts)
        if simplify_m:
            line = transform(_TO_LL, transform(_TO_M, line).simplify(simplify_m))
        coords = [(round(y, 5), round(x, 5)) for x, y in line.coords]
        if len(coords) >= 2:
            out.append(coords)
    return out


HALF = 0.5


def quarters(lat, lng):
    """1° 칸을 0.5° 넷으로 — 남서 모서리들"""
    return [(lat + a, lng + b) for a in (0, HALF) for b in (0, HALF)]


def tile_path(lat, lng, size=HALF):
    if size == HALF:
        return CACHE / ('%.1f_%.1f.json' % (lat, lng))
    return CACHE / ('%.3f_%.3f_%.3f.json' % (lat, lng, size))


MIN_SIZE = 0.125      # 이보다는 쪼개지 않는다 — 그래도 안 되면 서버 쪽 문제다
SPLIT_AFTER = 2       # 이만큼 실패하면 넷으로 쪼갠다


def fetch_tile(lat, lng, size=HALF, retries=6):
    """한 칸의 원본 응답. 캐시가 있으면 묻지 않는다.

    서울 0.5° 칸은 502 가 거듭 났다(응답이 커서 본진이 버틴다) — SPLIT_AFTER 번 실패하면 넷으로 쪼개 받아 합친다.
    """
    path = tile_path(lat, lng, size)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        return json.loads(path.read_text(encoding='utf-8'))
    box = '%.3f,%.3f,%.3f,%.3f' % (lat, lng, lat + size, lng + size)
    q = ('[out:json][timeout:170];way["highway"~"^(%s)$"]["bridge"!~"."]["tunnel"!~"."]'
         '["motorroad"!="yes"](%s);out tags geom;' % ('|'.join(CLASSES), box))
    for attempt in range(retries):
        if attempt == SPLIT_AFTER and size / 2 >= MIN_SIZE:
            half = size / 2
            print('  %.3f,%.3f 를 %.3f° 넷으로 쪼갭니다' % (lat, lng, half), flush=True)
            elements = []
            for a in (0, half):
                for b in (0, half):
                    elements += fetch_tile(lat + a, lng + b, half, retries).get('elements', [])
            data = {'elements': elements}
            path.write_text(json.dumps(data, ensure_ascii=False), encoding='utf-8')
            return data
        try:
            req = urllib.request.Request(URL, data=urllib.parse.urlencode({'data': q}).encode(),
                                         headers=UA)
            body = urllib.request.urlopen(req, timeout=200).read()
            data = json.loads(body)
            path.write_text(json.dumps(data, ensure_ascii=False), encoding='utf-8')
            return data
        except Exception as ex:          # 502·504·시간 초과 — 기다렸다 다시 묻는다
            print('  %.3f,%.3f 재시도 %d: %s' % (lat, lng, attempt + 1, str(ex)[:60]), flush=True)
            time.sleep(30 * (attempt + 1))
    raise SystemExit('%.3f,%.3f 칸을 받지 못했습니다 — 캐시된 칸은 남아 있으니 다시 돌리세요' % (lat, lng))


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    from_cache = '--from-cache' in sys.argv
    lines, missing = [], []
    for lat, lng in [q for t in TILES for q in quarters(*t)]:
        if from_cache and not tile_path(lat, lng).exists():
            missing.append('%.1f,%.1f' % (lat, lng))
            continue
        d = fetch_tile(lat, lng)
        got = roads(d.get('elements', []))
        lines += got
        print('%.1f,%.1f  요소 %d → 토막 %d' % (lat, lng, len(d.get('elements', [])), len(got)), flush=True)
    if missing:
        print('**캐시에 없어 빠진 칸 %d개: %s — 이 지역은 역을 옮기지 않는다**' % (len(missing), ' '.join(missing)))
    # 칸 경계를 넘는 way 는 두 칸에서 다 온다 — 같은 토막은 한 번만
    unique = sorted({tuple(l) for l in lines})
    BUILD.mkdir(parents=True, exist_ok=True)
    # mtime=0 — 같은 자료면 같은 파일이 나와야 커밋 차이가 안 생긴다
    with open(OUT, 'wb') as raw, gzip.GzipFile(fileobj=raw, mode='wb', mtime=0, filename='') as f:
        for l in unique:
            f.write((' '.join('%.5f,%.5f' % p for p in l) + '\n').encode('ascii'))
    pts = sum(len(l) for l in unique)
    print('-> %s  토막 %d · 점 %d · %.0fKB' % (OUT, len(unique), pts, OUT.stat().st_size / 1024))


if __name__ == '__main__':
    main()
