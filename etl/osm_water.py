"""
호수·저수지 영역 — OpenStreetMap Overpass 에서 받는다. 노선 우회 판단(`WaterDetour`)에 쓴다.

왜 필요한가
  지도에 그린 노선이 저수지 한가운데를 지나도 지금은 아무 말이 없다. 역은 반경 500m 에
  사람이 없으면 안 놓지만(`StationPlanner`) **선은 물 위를 그대로 지난다.** 물 영역이 있으면
  "이 노선은 ○○저수지를 지납니다 — 돌아가는 선형으로 바꾸기" 를 제안할 수 있다.

무엇을 받나
  `natural=water` 이면서 `water=lake|reservoir` 인 것만. **강(river)은 받지 않는다** — 한강·
  낙동강은 돌아갈 수 없고 건너야 한다. 넓이 1km² 미만의 못은 뺀다(우회할 만한 물이 아니다).
  relation 의 **섬(inner)도 쓰지 않는다** — 섬을 지나도 양옆은 물이라 호수를 통째로 막는다.

Overpass 에서 데인 것 (osm_regional_stations.py 와 같다)
  ① 본진만 쓴다 — 미러는 틀린 빈 답을 성공처럼 준다
  ② 본진이 504 를 자주 낸다 — 칸(1°×1°)마다 캐시하고, 기다렸다 다시 묻는다
  ③ User-Agent 가 없으면 406 이다

출력
  data/build/water_bodies.geojson   FeatureCollection — MultiPolygon(바깥 경계만),
                                    properties: osm_id, name, water, area_km2

    python etl/osm_water.py
"""

import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

from pyproj import Transformer
from shapely.geometry import LineString, MultiPolygon, Polygon, mapping
from shapely.ops import linemerge, polygonize, transform, unary_union

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'data' / 'build'
CACHE = ROOT / 'data' / 'cache' / 'osm' / 'water'
OUT = BUILD / 'water_bodies.geojson'

URL = 'https://overpass-api.de/api/interpreter'
UA = {'User-Agent': 'railfeas-student-project'}

# 육지가 있는 칸만 묻는다(남서 모서리 위도, 경도). 바다뿐인 칸도 빈 답이 빨리 올 줄 알았는데
# 본진이 502·시간 초과를 내며 칸마다 몇 분씩 붙잡았다. 수도권을 먼저 받는다
TILES = [(37, 126), (37, 127), (36, 126), (36, 127), (37, 128), (37, 129), (38, 127), (38, 128),
         (36, 128), (36, 129), (35, 126), (35, 127), (35, 128), (35, 129),
         (34, 126), (34, 127), (34, 128), (33, 126)]

MIN_AREA_KM2 = 1.0      # 이보다 작은 못은 우회할 만한 물이 아니다
SIMPLIFY_M = 25         # 경계를 이만큼의 오차 안에서 줄인다 — 소양호 경계점이 27,989개다

# 넓이·단순화는 미터 좌표계(UTM-K)에서 한다. 위경도 그대로 재면 위도마다 크기가 달라진다
_TO_M = Transformer.from_crs('EPSG:4326', 'EPSG:5179', always_xy=True).transform
_TO_LL = Transformer.from_crs('EPSG:5179', 'EPSG:4326', always_xy=True).transform


def _line(geometry):
    return [(p['lon'], p['lat']) for p in geometry]


def outer_polygon(e):
    """한 요소의 바깥 경계 다각형(위경도). 만들 수 없으면 None."""
    if e['type'] == 'way':
        pts = _line(e.get('geometry', []))
        if len(pts) >= 4 and pts[0] == pts[-1]:
            return Polygon(pts)
        return None
    # relation — 바깥 경계가 여러 way 로 쪼개져 온다. 방향도 제각각이라 이어 붙여 닫는다
    lines = [LineString(_line(m['geometry'])) for m in e.get('members', [])
             if m.get('role') == 'outer' and len(m.get('geometry', [])) >= 2]
    if not lines:
        return None
    polys = list(polygonize(linemerge(lines)))
    if not polys:
        return None
    return unary_union(polys)


def water_bodies(elements, min_area_km2=MIN_AREA_KM2, simplify_m=SIMPLIFY_M):
    """OSM 요소들 → [{osm_id, name, water, area_km2, coordinates}]. 같은 요소는 한 번만."""
    seen, out = set(), []
    for e in elements:
        key = (e['type'], e['id'])
        if key in seen:
            continue
        seen.add(key)
        poly = outer_polygon(e)
        if poly is None or poly.is_empty:
            continue
        poly_m = transform(_TO_M, poly).buffer(0)
        area = poly_m.area / 1e6
        if area < min_area_km2:
            continue
        if simplify_m:
            poly_m = poly_m.simplify(simplify_m, preserve_topology=True)
        ll = transform(_TO_LL, poly_m)
        parts = list(ll.geoms) if isinstance(ll, MultiPolygon) else [ll]
        tags = e.get('tags', {})
        out.append({
            'osm_id': '%s/%d' % (e['type'], e['id']),
            'name': tags.get('name', ''),
            'water': tags.get('water', ''),
            'area_km2': round(area, 3),
            # 바깥 경계만 — GeoJSON MultiPolygon 의 각 다각형은 [바깥 고리] 하나다
            'coordinates': [[[ [round(x, 6), round(y, 6)] for x, y in p.exterior.coords ]]
                            for p in parts],
        })
    return out


def fetch_tile(lat, lng, retries=6):
    """한 칸의 원본 응답. 캐시가 있으면 묻지 않는다."""
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / ('%d_%d.json' % (lat, lng))
    if path.exists():
        return json.loads(path.read_text(encoding='utf-8'))
    box = '%d,%d,%d,%d' % (lat, lng, lat + 1, lng + 1)
    q = ('[out:json][timeout:170];('
         'way["natural"="water"]["water"~"^(lake|reservoir)$"](%s);'
         'relation["natural"="water"]["water"~"^(lake|reservoir)$"](%s););out geom;' % (box, box))
    for attempt in range(retries):
        try:
            req = urllib.request.Request(URL, data=urllib.parse.urlencode({'data': q}).encode(),
                                         headers=UA)
            body = urllib.request.urlopen(req, timeout=200).read()
            data = json.loads(body)
            path.write_text(json.dumps(data, ensure_ascii=False), encoding='utf-8')
            return data
        except Exception as ex:          # 504·시간 초과 — 기다렸다 다시 묻는다
            print('  %d,%d 재시도 %d: %s' % (lat, lng, attempt + 1, str(ex)[:60]), flush=True)
            time.sleep(30 * (attempt + 1))
    raise SystemExit('%d,%d 칸을 받지 못했습니다 — 캐시된 칸은 남아 있으니 다시 돌리세요' % (lat, lng))


def main():
    """`--from-cache` 면 묻지 않고 캐시된 칸만으로 만든다 — 빠진 칸을 크게 알린다."""
    sys.stdout.reconfigure(encoding='utf-8')
    from_cache = '--from-cache' in sys.argv
    elements, missing = [], []
    for lat, lng in TILES:
        if from_cache and not (CACHE / ('%d_%d.json' % (lat, lng))).exists():
            missing.append('%d,%d' % (lat, lng))
            continue
        d = fetch_tile(lat, lng)
        elements += d.get('elements', [])
        print('%d,%d  요소 %d' % (lat, lng, len(d.get('elements', []))), flush=True)
    if missing:
        print('**캐시에 없어 빠진 칸 %d개: %s — 이 지역의 호수는 우회 제안이 안 된다**'
              % (len(missing), ' '.join(missing)))
    bodies = water_bodies(elements)
    BUILD.mkdir(parents=True, exist_ok=True)
    fc = {'type': 'FeatureCollection', 'features': [
        {'type': 'Feature',
         'properties': {k: b[k] for k in ('osm_id', 'name', 'water', 'area_km2')},
         'geometry': {'type': 'MultiPolygon', 'coordinates': b['coordinates']}}
        for b in sorted(bodies, key=lambda b: -b['area_km2'])]}
    OUT.write_text(json.dumps(fc, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
    print('')
    print('호수·저수지 %d개 (넓이 %.0fkm² 이상) -> %s  %.0fKB'
          % (len(bodies), MIN_AREA_KM2, OUT, OUT.stat().st_size / 1024))
    for b in sorted(bodies, key=lambda b: -b['area_km2'])[:10]:
        print('  %-14s %7.2fkm²' % (b['name'] or '(이름 없음)', b['area_km2']))


if __name__ == '__main__':
    main()
