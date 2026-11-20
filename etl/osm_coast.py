"""
해안선 — OpenStreetMap Overpass 에서 받는다. **바다 위 새 도로의 다리값**을 매기려고 바다 위 길이를 재는 데 쓴다(11-18).

왜 필요한가
  트램·BRT 의 새 도로가 강·호수를 건너는 몫은 일반국도 교량 단가(451억/km)를 매기는데(`RoadRoute`), 바다는 물 파일(`water_bodies.geojson`)에 없어
  다리값이 0 이었다. 지형 타일(DEM)로 바다를 가를 수도 없다 — 연안 바다도 간척지도 0m 라 둘이 안 갈린다(research-log 「바다를 DEM 으로 가를 수 없다」).
  OSM 해안선(`natural=coastline`)은 **간척지를 둑 쪽에 그려** 육지로 두므로 이 문제가 없다.

무엇을 받나
  `natural=coastline` way — 육지가 있는 1° 칸(`osm_water.TILES`)에 **서쪽으로 쏜 선이 지나는 칸**(`RAY_TILES` — 육지 칸마다 그 위도의 124° 부터,
  서해 섬과 북위 38° 의 북한 서해안이 든다)과 울릉도·독도 칸(`ISLAND_TILES`)을 더해 0.5° 로 나눠 받는다.
  한반도 상자 안은 way 11,598개·원본 약 66~83MB 로 어림했다. 섬 칸을 빼면 짝홀 판정은 섬 **밖**의 점은 맞지만(섬을 지나는 선은 횟수를 2씩만 늘린다)
  그 섬 **안**의 점이 바다로 읽혀 섬에 새 도로를 그으면 다리값이 붙는다.

어떻게 쓰나 (백엔드 `Coastline`)
  점에서 **서쪽 바다(경도 123°)로 선을 쏴서 해안선과 만나는 횟수**를 센다 — 홀수면 육지, 짝수면 바다. 해안선의 방향(OSM 은 육지가 왼쪽)에도
  칸 경계에서 way 가 끊기는 것에도 기대지 않는다. 123° 는 중국 산둥 반도 동쪽 끝(122.7°)보다 동쪽이라 항상 바다다.

Overpass 에서 데인 것 (osm_roads.py 와 같다)
  ① 본진만 쓴다 ② 본진이 502·504 를 자주 낸다 — 칸마다 캐시하고 기다렸다 다시 묻는다. 두 번 실패하면 칸을 쪼갠다 ③ User-Agent 가 없으면 406 이다

출력
  data/build/coastline.csv.gz   한 줄에 way 하나 — `위도,경도 위도,경도 …`(방향 그대로). 좌표는 소수 5자리(≈1m), SIMPLIFY_M 오차로 줄인다

    python etl/osm_coast.py               # 받고 만든다
    python etl/osm_coast.py --from-cache  # 받은 칸만으로 만든다
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

from osm_roads import HALF, MIN_SIZE, SPLIT_AFTER, quarters
from osm_water import TILES, UA, URL

# 짝홀 판정이 서쪽(경도 123°)으로 쏜 선이 지나는 칸 — 육지 칸마다 그 위도의 경도 124° 부터 그 칸 바로 서쪽까지. 손으로 적지 않고 육지 칸에서
# 끌어낸다(11-21) — 손으로 적었을 때 북위 38° 의 124~126°(북한 서해안)가 빠져 철원·화천·인제·속초 내륙이 「바다」 로 읽혔다.
# 서해 섬(백령·연평·덕적·신안·흑산·가거도)도 여기 든다
RAY_TILES = sorted({(lat, k) for lat, lng in TILES for k in range(124, lng)} - set(TILES))
# 울릉도·독도 — 선이 지나는 칸은 아니지만 받지 않으면 섬 안의 점이 바다로 읽힌다(130~131°)
ISLAND_TILES = [(37, 130), (37, 131)]
COAST_TILES = TILES + RAY_TILES + ISLAND_TILES

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'data' / 'build'
CACHE = ROOT / 'data' / 'cache' / 'osm' / 'coast'
OUT = BUILD / 'coastline.csv.gz'
SIMPLIFY_M = 10        # 다리 길이를 몇 m 단위로 재면 되니 10m 오차는 괜찮다 — 줄이지 않으면 점이 몇 배다

_TO_M = Transformer.from_crs('EPSG:4326', 'EPSG:5179', always_xy=True).transform
_TO_LL = Transformer.from_crs('EPSG:5179', 'EPSG:4326', always_xy=True).transform


def simplify(coords, simplify_m=SIMPLIFY_M):
    """[(위도, 경도)] — 양 끝은 남긴다. 줄이지 않는 경우(점 둘 이하)도 소수 5자리로 맞춘다"""
    if not simplify_m or len(coords) <= 2:
        return [(round(a, 5), round(b, 5)) for a, b in coords]
    line = transform(_TO_LL, transform(_TO_M, LineString([(b, a) for a, b in coords])).simplify(simplify_m))
    return [(round(y, 5), round(x, 5)) for x, y in line.coords]


def ways(elements, simplify_m=SIMPLIFY_M):
    """way 번호로 한 번씩만 — 칸 경계를 넘는 way 는 두 칸에서 다 온다. 점이 둘보다 적은 것은 버린다"""
    seen, out = set(), []
    for e in elements:
        if e.get('type') != 'way' or e['id'] in seen:
            continue
        seen.add(e['id'])
        coords = [(g['lat'], g['lon']) for g in e.get('geometry', [])]
        if len(coords) >= 2:
            out.append(simplify(coords, simplify_m))
    return out


def tile_path(lat, lng, size=HALF):
    if size == HALF:
        return CACHE / ('%.1f_%.1f.json' % (lat, lng))
    return CACHE / ('%.3f_%.3f_%.3f.json' % (lat, lng, size))


def fetch_tile(lat, lng, size=HALF, retries=6):
    """한 칸의 원본 응답. 캐시가 있으면 묻지 않는다. SPLIT_AFTER 번 실패하면 넷으로 쪼개 받아 합친다."""
    path = tile_path(lat, lng, size)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        return json.loads(path.read_text(encoding='utf-8'))
    box = '%.3f,%.3f,%.3f,%.3f' % (lat, lng, lat + size, lng + size)
    q = '[out:json][timeout:170];way["natural"="coastline"](%s);out geom;' % box
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
            req = urllib.request.Request(URL, data=urllib.parse.urlencode({'data': q}).encode(), headers=UA)
            data = json.loads(urllib.request.urlopen(req, timeout=200).read())
            path.write_text(json.dumps(data, ensure_ascii=False), encoding='utf-8')
            time.sleep(2)             # 공개 서버에 예의 — 몰아서 묻지 않는다
            return data
        except Exception as ex:      # 502·504·시간 초과 — 기다렸다 다시 묻는다
            print('  %.3f,%.3f 재시도 %d: %s' % (lat, lng, attempt + 1, str(ex)[:60]), flush=True)
            time.sleep(30 * (attempt + 1))
    raise SystemExit('%.3f,%.3f 칸을 받지 못했습니다 — 캐시된 칸은 남아 있으니 다시 돌리세요' % (lat, lng))


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    from_cache = '--from-cache' in sys.argv
    elements, missing = [], []
    for lat, lng in [q for t in COAST_TILES for q in quarters(*t)]:
        if from_cache and not tile_path(lat, lng).exists():
            missing.append('%.1f,%.1f' % (lat, lng))
            continue
        d = fetch_tile(lat, lng)
        elements += d.get('elements', [])
        print('%.1f,%.1f  way %d' % (lat, lng, len(d.get('elements', []))), flush=True)
    if missing:
        print('**캐시에 없어 빠진 칸 %d개: %s — 이 지역은 해안선이 없다**' % (len(missing), ' '.join(missing)))
    lines = ways(elements)
    BUILD.mkdir(parents=True, exist_ok=True)
    # mtime=0 — 같은 자료면 같은 파일이 나와야 커밋 차이가 안 생긴다
    with open(OUT, 'wb') as raw, gzip.GzipFile(fileobj=raw, mode='wb', mtime=0, filename='') as f:
        for coords in lines:
            f.write((' '.join('%.5f,%.5f' % p for p in coords) + '\n').encode('ascii'))
    pts = sum(len(c) for c in lines)
    print('-> %s  way %d · 점 %d · %.0fKB' % (OUT, len(lines), pts, OUT.stat().st_size / 1024))


if __name__ == '__main__':
    main()
