"""
큰길 도로망 — OpenStreetMap Overpass 에서 받는다. 두 군데에 쓴다.

  ① 정차역을 가장 가까운 큰길 위로 옮긴다(`RoadNetwork.snap`, 11-12 사용자 요청) — 역이 아파트 단지 한가운데(노량진2동)에 놓였다
  ② 트램·BRT 는 이미 있는 도로·다리를 따라간다(`RoadNetwork.route`, 11-12 사용자 요청) — 그린 직선이 다리 없는 강을 건넜다

무엇을 받나 (`CLASSES`)
  trunk·primary·secondary·tertiary 와 그 진출입로(`*_link`) — 간선·보조간선·집산도로. **residential·service 는 뺀다**
  (단지 통로·골목). **motorway 와 `motorroad=yes`(자동차전용도로)도 뺀다** — 트램·BRT 가 못 다니고 역을 둘 수 없다.
  **다리·터널은 받는다** — 길 찾기에 한강 다리가 있어야 한다. 대신 표시(`flags`)를 달아 정차역은 그 위에 놓지 않는다.
  인도는 받지 않는다 — 한국은 OSM 에 인도가 거의 없어 중심선이 가장 가까운 자리다.

도로망 만들기 (`graph`)
  way 를 **교차점**(두 way 이상이 쓰는 node, 그리고 양 끝)마다 잘라 「교차점 A – 교차점 B」 간선으로 만든다. 방향(일방통행)은
  보지 않는다 — 트램·BRT 는 양방향 노선이다. 칸 경계를 넘는 way 는 두 칸에서 다 오므로 way 번호로 한 번만 쓴다.

Overpass 에서 데인 것 (osm_water.py 와 같다)
  ① 본진만 쓴다 — 미러는 틀린 빈 답을 성공처럼 준다
  ② 본진이 502·504 를 자주 낸다 — 칸마다 캐시하고, 기다렸다 다시 묻는다. 두 번 실패하면 칸을 넷으로 쪼갠다
  ③ User-Agent 가 없으면 406 이다
  ④ **수도권 1°×1° 칸은 18MB 에서 끊겼다**(IncompleteRead) — 칸을 0.5° 로 4등분한다(`HALF`)

출력
  data/build/road_graph.csv.gz   한 줄에 간선 하나 — `A B 표시 위도,경도 위도,경도 …`. A·B 는 교차점 번호(0부터, 이 파일 안에서만),
                                 표시는 1 다리 · 2 터널 · 4 진출입로 · 8 집산도로를 더한 값. 좌표는 소수 5자리(≈1m), UTM-K 에서 SIMPLIFY_M 오차로 줄인다

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
CACHE = ROOT / 'data' / 'cache' / 'osm' / 'road_graph'
OUT = BUILD / 'road_graph.csv.gz'

CLASSES = ('trunk', 'primary', 'secondary', 'tertiary')
LINKS = tuple(c + '_link' for c in CLASSES)
SIMPLIFY_M = 5          # 몇 m 만 틀어져도 된다 — 줄이지 않으면 점이 몇 배다
NO = ('no', '')         # bridge·tunnel·motorroad 가 이 값이면 「아니다」
BRIDGE, TUNNEL, LINK, MINOR = 1, 2, 4, 8

_TO_M = Transformer.from_crs('EPSG:4326', 'EPSG:5179', always_xy=True).transform
_TO_LL = Transformer.from_crs('EPSG:5179', 'EPSG:4326', always_xy=True).transform


def routable(tags):
    """트램·BRT 가 다닐 수 있는 길인가 — 큰길 등급과 그 진출입로, 자동차전용도로 아님."""
    return tags.get('highway') in CLASSES + LINKS and tags.get('motorroad', 'no') in NO


def flags(tags):
    """다리 1 · 터널 2 · 진출입로 4 · 집산도로 8. 정차역은 다리·터널·진출입로가 아닌 간선에 놓고, 집산도로는 보조간선 이상이
    0.3km 안에 없을 때만 쓴다(11-12 사용자 결정 — 집산도로엔 낮은 건물 사이 2차로 길이 섞여 있다)."""
    f = 0
    if tags.get('bridge', 'no') not in NO:
        f |= BRIDGE
    if tags.get('tunnel', 'no') not in NO:
        f |= TUNNEL
    if tags.get('highway', '').endswith('_link'):
        f |= LINK
    if tags.get('highway', '').startswith('tertiary'):
        f |= MINOR
    return f


def usable(tags):
    """정차역을 둘 수 있는 큰길인가 — 진출입로·다리·터널·자동차전용도로가 아닌 큰길(집산도로 포함)."""
    return routable(tags) and tags.get('highway') in CLASSES and flags(tags) & (BRIDGE | TUNNEL | LINK) == 0


def _simplify(coords, simplify_m):
    """[(위도, 경도)] — 양 끝은 남긴다"""
    if not simplify_m or len(coords) <= 2:
        return [(round(a, 5), round(b, 5)) for a, b in coords]
    line = transform(_TO_LL, transform(_TO_M, LineString([(b, a) for a, b in coords])).simplify(simplify_m))
    return [(round(y, 5), round(x, 5)) for x, y in line.coords]


def graph(elements, simplify_m=SIMPLIFY_M):
    """OSM way 들 → (교차점 수, [(A, B, 표시, [(위도, 경도), …]), …]). 교차점·양 끝에서 자른다."""
    ways, seen = [], set()
    for e in elements:
        if e.get('type') != 'way' or e['id'] in seen or not routable(e.get('tags', {})):
            continue
        nodes, geom = e.get('nodes') or [], e.get('geometry') or []
        if len(nodes) < 2 or len(nodes) != len(geom) or any(g is None for g in geom):
            continue          # 칸 밖으로 잘린 점은 null 로 온다 — 그 way 는 옆 칸에서 온전히 온다
        seen.add(e['id'])
        ways.append((nodes, [(g['lat'], g['lon']) for g in geom], flags(e['tags'])))
    use = {}
    for nodes, _, _ in ways:
        for n in set(nodes):
            use[n] = use.get(n, 0) + 1
        use[nodes[0]] = use.get(nodes[0], 0) + 1      # 양 끝은 늘 교차점
        use[nodes[-1]] = use.get(nodes[-1], 0) + 1
    index, edges = {}, []
    for nodes, coords, f in ways:
        start = 0
        for i in range(1, len(nodes)):
            if use[nodes[i]] >= 2 or i == len(nodes) - 1:
                a, b = nodes[start], nodes[i]
                if a != b:
                    ia = index.setdefault(a, len(index))
                    ib = index.setdefault(b, len(index))
                    edges.append((ia, ib, f, _simplify(coords[start:i + 1], simplify_m)))
                start = i
    return len(index), edges


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
    q = ('[out:json][timeout:170];way["highway"~"^(%s)(_link)?$"]["motorroad"!="yes"](%s);out body geom;'
         % ('|'.join(CLASSES), box))
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
    elements, missing = [], []
    for lat, lng in [q for t in TILES for q in quarters(*t)]:
        if from_cache and not tile_path(lat, lng).exists():
            missing.append('%.1f,%.1f' % (lat, lng))
            continue
        d = fetch_tile(lat, lng)
        elements += d.get('elements', [])
        print('%.1f,%.1f  요소 %d' % (lat, lng, len(d.get('elements', []))), flush=True)
    if missing:
        print('**캐시에 없어 빠진 칸 %d개: %s — 이 지역은 도로망이 없다**' % (len(missing), ' '.join(missing)))
    n, edges = graph(elements)
    BUILD.mkdir(parents=True, exist_ok=True)
    # mtime=0 — 같은 자료면 같은 파일이 나와야 커밋 차이가 안 생긴다
    with open(OUT, 'wb') as raw, gzip.GzipFile(fileobj=raw, mode='wb', mtime=0, filename='') as f:
        for a, b, fl, coords in edges:
            f.write(('%d %d %d ' % (a, b, fl) + ' '.join('%.5f,%.5f' % p for p in coords) + '\n').encode('ascii'))
    pts = sum(len(e[3]) for e in edges)
    print('-> %s  교차점 %d · 간선 %d · 점 %d · %.0fKB' % (OUT, n, len(edges), pts, OUT.stat().st_size / 1024))


if __name__ == '__main__':
    main()
