"""osm_coast.py — 해안선을 읽는 규칙. 틀려도 그럴듯한 숫자가 나오는 곳(바다 위 다리 길이)을 막는다."""
from osm_coast import ways, simplify


def way(id, pts):
    return {'type': 'way', 'id': id, 'geometry': [{'lat': a, 'lon': b} for a, b in pts]}


def test_칸_경계를_넘는_way_는_한_번만_쓴다():
    # 같은 way 가 이웃한 두 칸 응답에 다 들어 있다 — 두 번 쓰면 해안선이 겹쳐 짝홀 판정이 뒤집힌다
    w = way(1, [(37.0, 126.0), (37.1, 126.1), (37.2, 126.2)])
    assert len(ways([w, w], simplify_m=0)) == 1


def test_way_가_아니거나_점이_둘보다_적으면_버린다():
    elements = [{'type': 'node', 'id': 5, 'lat': 37, 'lon': 126}, way(2, [(37.0, 126.0)]), way(3, [])]
    assert ways(elements, simplify_m=0) == []


def test_방향을_그대로_둔다():
    # OSM 은 육지가 해안선 왼쪽이다. 지금 판정은 방향을 안 쓰지만, 뒤집으면 나중에 그 규칙을 쓸 때 거꾸로가 된다
    w = way(4, [(37.0, 126.0), (37.5, 126.2), (37.9, 126.1)])
    out = ways([w], simplify_m=0)[0]
    assert out[0] == (37.0, 126.0) and out[-1] == (37.9, 126.1)


def test_줄여도_양_끝은_남긴다():
    # 위도 0.0001° 씩 곧게 올라가는 점 여섯 개 — 가운데는 지워져도 양 끝은 그대로여야 이웃한 way 와 이어진다
    pts = [(37.0 + 0.0001 * i, 126.0) for i in range(6)]
    out = simplify(pts, 10)
    assert out[0] == (37.0, 126.0) and out[-1] == (37.0005, 126.0)
    assert len(out) < len(pts)


def test_좌표는_소수_5자리로_맞춘다():
    out = ways([way(6, [(37.123456789, 126.987654321), (37.2, 126.9)])], simplify_m=0)[0]
    assert out[0] == (37.12346, 126.98765)


def test_멀리_떨어진_섬도_받을_칸_안에_있다():
    # 육지 칸(osm_water.TILES)만 받으면 서해·동해 섬이 빠져 그 섬 안의 점이 바다로 읽힌다(섬에 새 도로를 그으면 다리값이 붙는다)
    from math import floor
    from osm_coast import COAST_TILES
    islands = {'백령도': (37.90, 124.70), '연평도': (37.66, 125.70), '흑산도': (34.68, 125.43), '가거도': (34.07, 125.12),
               '울릉도': (37.50, 130.87), '독도': (37.24, 131.87), '제주도': (33.38, 126.55), '거제도': (34.88, 128.62)}
    missing = [n for n, (lat, lng) in islands.items() if (floor(lat), floor(lng)) not in COAST_TILES]
    assert missing == [], '받을 칸 밖의 섬: %s' % missing
