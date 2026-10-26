"""
지하 비율 실측 — OpenStreetMap 선로의 터널 표시로 사업 구간의 지하 비율을 잰다.

왜 필요한가
  비용 모델에서 지하 비율 계수가 가장 크다(측정 전 0.575, 지금 0.515). 그런데 `metro_seed.csv`·
  `rail_station_seed.csv` 의 지하 비율 40건은 **전부 추정치**다(공식 미공표). 0 이나 1 은
  거의 확실하지만 0.20·0.70 같은 중간값은 어림이라 틀릴 여지가 가장 크다 — 용인경전철을
  0.20 으로 적어 두었는데 OSM 은 선로 99.6% 를 고가(`bridge=yes`)로, 터널은 0 으로 적었다.

재는 법
  OSM 의 대중교통 노선 관계(route relation, PTv2)는 **선로 조각을 운행 순서대로**, 정차역도
  순서대로 담는다. 조각을 이어 한 줄을 만들고, 구간 양 끝 역을 그 줄 위에 찍어 그 사이에서
  `tunnel` 표시가 붙은 길이의 비율을 잰다. 어느 사업 구간이 어느 관계의 어느 역~역인지는
  `data/seed/tunnel_sections.csv` 에 사람이 적는다(숫자가 아니라 사실만 적는 표다).

틀려도 조용히 넘어가는 곳
  ① 역 이름을 잘못 짚으면 엉뚱한 구간을 잰다 — **잰 길이가 사업 연장과 15% 넘게 다르면
     거부한다**
  ② 조각 사이가 끊기면(관계가 덜 그려졌다) 직선으로 잇지 않는다 — 끊긴 길이가 0.5km 를
     넘으면 거부한다
  ③ 공사 중·계획 노선은 OSM 에 운행 노선이 없다 — 표에 넣지 않고 추정치로 남긴다
     (「못 잰다」 이지 「지상이다」 가 아니다)

출력
  data/build/underground_measured.csv   line_name, section_name, relation_id, osm_km,
                                        seed_km, tunnel_km, underground_ratio

    python etl/osm_tunnel.py
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
SEED = ROOT / 'data' / 'seed' / 'tunnel_sections.csv'
CACHE = ROOT / 'data' / 'cache' / 'osm' / 'tunnel'
OUT = ROOT / 'data' / 'build' / 'underground_measured.csv'

URL = 'https://overpass-api.de/api/interpreter'
UA = {'User-Agent': 'railfeas-student-project'}

LENGTH_TOL = 0.15       # 잰 길이와 사업 연장이 이보다 더 다르면 구간을 잘못 짚은 것이다
MAX_GAP_KM = 0.5        # 관계의 조각이 이보다 많이 끊겨 있으면 재지 않는다
JOIN_KM = 0.05          # 조각 끝이 이만큼 안이면 이어진 것으로 본다


def hav(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = (math.sin((la2 - la1) / 2) ** 2
         + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2)
    return 2 * 6371.0 * math.asin(math.sqrt(h))


def is_tunnel(tags):
    return tags.get('tunnel', 'no') != 'no' or tags.get('location') == 'underground'


def chain(ways):
    """[(점 목록, 터널인가)] (운행 순서) → 이은 점들, 구간마다의 (길이, 터널, 끊김) 목록.

    조각 방향이 제각각이라 앞 조각 끝에 가까운 쪽으로 뒤집는다. 첫 조각은 두 번째 조각이
    시작점 쪽에 붙어 있으면 뒤집는다.
    """
    ways = [(list(g), t) for g, t in ways if len(g) >= 2]
    if len(ways) >= 2:
        g0, g1 = ways[0][0], ways[1][0]
        if min(hav(g0[0], g1[0]), hav(g0[0], g1[-1])) < min(hav(g0[-1], g1[0]), hav(g0[-1], g1[-1])):
            ways[0] = (g0[::-1], ways[0][1])
    pts, segs = [], []                  # segs[i] 는 pts[i] → pts[i+1]
    for g, tun in ways:
        if not pts:
            pts.append(g[0])
        else:
            if hav(pts[-1], g[-1]) < hav(pts[-1], g[0]):
                g = g[::-1]
            gap = hav(pts[-1], g[0])
            if gap > JOIN_KM:           # 끊김 — 표시만 하고 길이에 넣지 않는다
                segs.append((gap, False, True))
                pts.append(g[0])
            # 이어졌으면 g[0] 은 앞 조각의 끝점과 같은 자리라 다시 넣지 않는다
        for p in g[1:]:
            segs.append((hav(pts[-1], p), tun, False))
            pts.append(p)
    return pts, segs


def measure(pts, segs, a, b):
    """점 a·b(역 좌표)에 가장 가까운 꼭짓점 사이를 잰다 → (길이, 터널 길이, 끊긴 길이)."""
    ia = min(range(len(pts)), key=lambda i: hav(pts[i], a))
    ib = min(range(len(pts)), key=lambda i: hav(pts[i], b))
    lo, hi = sorted((ia, ib))
    km = tun = gap = 0.0
    for L, t, cut in segs[lo:hi]:       # segs[i] 는 pts[i] → pts[i+1]
        if cut:
            gap += L
            continue
        km += L
        tun += L if t else 0.0
    return km, tun, gap


def ask(q, retries=6):
    for attempt in range(retries):
        try:
            req = urllib.request.Request(URL, data=urllib.parse.urlencode({'data': q}).encode(),
                                         headers=UA)
            return json.loads(urllib.request.urlopen(req, timeout=200).read())
        except Exception as ex:          # 502·504 — 기다렸다 다시 묻는다
            print('  재시도 %d: %s' % (attempt + 1, str(ex)[:60]), flush=True)
            time.sleep(30 * (attempt + 1))
    raise SystemExit('Overpass 가 답하지 않습니다 — 캐시된 관계는 남아 있으니 다시 돌리세요')


def relation(rid):
    """관계 하나 — 조각 좌표(out geom)와 조각 태그, 정차역 이름까지. 칸처럼 캐시한다."""
    path = CACHE / ('rel_%d.json' % rid)
    if not path.exists():
        CACHE.mkdir(parents=True, exist_ok=True)
        d = ask('[out:json][timeout:120];relation(%d);out geom;(way(r);node(r););out tags;' % rid)
        path.write_text(json.dumps(d, ensure_ascii=False), encoding='utf-8')
    return json.loads(path.read_text(encoding='utf-8'))


def stops_and_ways(d):
    """관계 응답 → (정차역 [(이름, 좌표)], 조각 [(점들, 터널인가)])"""
    rel = next(e for e in d['elements'] if e['type'] == 'relation')
    tags = {(e['type'], e['id']): e.get('tags', {}) for e in d['elements'] if e['type'] != 'relation'}
    stops, ways = [], []
    for m in rel['members']:
        if m['type'] == 'node' and m.get('role', '').startswith('stop'):
            name = tags.get(('node', m['ref']), {}).get('name', '')
            stops.append((name, (m['lat'], m['lon'])))
        elif m['type'] == 'way' and not m.get('role') and 'geometry' in m:
            g = [(p['lat'], p['lon']) for p in m['geometry']]
            ways.append((g, is_tunnel(tags.get(('way', m['ref']), {}))))
    return stops, ways


def find_stop(stops, name):
    """정차역 이름 — 「역」 을 떼고 견준다. 없거나 둘 이상 다르면 멈춘다(엉뚱한 역에 붙지 않게)."""
    key = name.removesuffix('역')
    hits = {s[0]: s[1] for s in stops if s[0].removesuffix('역') == key}
    if len(hits) != 1:
        raise ValueError('정차역 %r 을 관계에서 찾지 못했습니다 (후보 %d)' % (name, len(hits)))
    return next(iter(hits.values()))


def measure_section(d, from_stop, to_stop, seed_km):
    """→ (잰 길이, 터널 길이). 길이·끊김 검사를 통과 못 하면 ValueError."""
    stops, ways = stops_and_ways(d)
    pts, segs = chain(ways)
    if from_stop and to_stop:
        a, b = find_stop(stops, from_stop), find_stop(stops, to_stop)
    else:                                       # 노선 전체
        a, b = pts[0], pts[-1]
    km, tun, gap = measure(pts, segs, a, b)
    if gap > MAX_GAP_KM:
        raise ValueError('관계가 %.2fkm 끊겨 있습니다' % gap)
    if seed_km and abs(km / seed_km - 1) > LENGTH_TOL:
        raise ValueError('잰 길이 %.2fkm 가 사업 연장 %.2fkm 와 %.0f%% 다릅니다'
                         % (km, seed_km, 100 * (km / seed_km - 1)))
    return km, tun


def seed_lengths():
    """사업 연장 — metro_seed 는 직접, rail_station_seed 는 연장이 없어 None(길이 검사 생략)."""
    m = pd.read_csv(ROOT / 'data' / 'seed' / 'metro_seed.csv')
    return {(r.line_name, r.section_name): r.length_km for r in m.itertuples()}


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    plan = pd.read_csv(SEED)
    lengths = seed_lengths()
    rows, rejected = [], []
    for r in plan.itertuples():
        seed_km = lengths.get((r.line_name, r.section_name))
        frm = r.from_stop if isinstance(r.from_stop, str) else None
        to = r.to_stop if isinstance(r.to_stop, str) else None
        try:
            km, tun = measure_section(relation(int(r.relation_id)), frm, to, seed_km)
        except ValueError as ex:        # 거부는 예상된 결과다 — 그 구간만 추정치로 남긴다
            rejected.append('%s %s — %s' % (r.line_name, r.section_name, ex))
            continue
        rows.append({'line_name': r.line_name, 'section_name': r.section_name,
                     'relation_id': int(r.relation_id), 'osm_km': round(km, 3),
                     'seed_km': seed_km, 'tunnel_km': round(tun, 3),
                     'underground_ratio': round(tun / km, 2)})
        print('%-28s %-24s %6.2fkm  지하 %.2f' % (r.line_name, r.section_name, km, tun / km))
    out = pd.DataFrame(rows)
    out.to_csv(OUT, index=False, encoding='utf-8')
    print('-> %s  %d건' % (OUT, len(out)))
    if rejected:
        print('**거부 %d건 — 추정치로 남는다**' % len(rejected))
        for line in rejected:
            print('  ' + line)


if __name__ == '__main__':
    main()
