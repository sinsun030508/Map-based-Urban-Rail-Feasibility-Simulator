"""큰길 도로망 — 다닐 수 있는 길을 거르고, 교차점에서 잘라 간선으로 만드는 규칙."""

from osm_roads import BRIDGE, LINK, MINOR, TUNNEL, flags, graph, quarters, routable, usable


def way(i, tags, nodes, pts):
    return {'type': 'way', 'id': i, 'tags': tags, 'nodes': nodes,
            'geometry': [{'lat': a, 'lon': b} for a, b in pts]}


def test_큰길과_진출입로만_다니고_자동차전용도로는_뺀다():
    for cls in ('trunk', 'primary', 'secondary', 'tertiary', 'primary_link', 'trunk_link'):
        assert routable({'highway': cls})
    for cls in ('residential', 'service', 'unclassified', 'motorway', 'motorway_link', 'footway'):
        assert not routable({'highway': cls})
    assert not routable({'highway': 'trunk', 'motorroad': 'yes'})
    assert routable({'highway': 'trunk', 'motorroad': 'no'})


def test_다리_터널_진출입로는_표시하고_정차역은_그_위에_안_놓는다():
    assert flags({'highway': 'primary', 'bridge': 'yes'}) == BRIDGE
    assert flags({'highway': 'primary', 'bridge': 'viaduct', 'tunnel': 'yes'}) == BRIDGE | TUNNEL
    assert flags({'highway': 'secondary_link'}) == LINK
    assert flags({'highway': 'primary', 'bridge': 'no'}) == 0
    # 집산도로는 표시만 달고 역 자리로는 쓴다 — 보조간선 이상이 0.3km 안에 없을 때(백엔드가 가른다)
    assert flags({'highway': 'tertiary'}) == MINOR
    assert flags({'highway': 'tertiary_link'}) == MINOR | LINK
    assert usable({'highway': 'tertiary'})
    assert usable({'highway': 'primary'})
    assert not usable({'highway': 'primary', 'bridge': 'yes'})
    assert not usable({'highway': 'primary_link'})          # 다닐 수는 있지만 역 자리는 아니다


def test_교차점에서_잘라_간선을_만든다_같은_way_는_한_번만():
    # 동서 길(1-2-3) 가운데 2 에서 남북 다리(2-4)가 만난다
    ew = way(10, {'highway': 'primary'}, [1, 2, 3], [(37.50, 126.90), (37.50, 126.91), (37.50, 126.92)])
    ns = way(11, {'highway': 'primary', 'bridge': 'yes'}, [2, 4], [(37.50, 126.91), (37.49, 126.91)])
    n, edges = graph([ew, ns, ew, {'type': 'node', 'id': 5}], simplify_m=0)
    assert n == 4
    assert [(len(c), f) for _, _, f, c in edges] == [(2, 0), (2, 0), (2, BRIDGE)]
    # 동서 길의 두 간선은 가운데 교차점을 함께 쓴다 — 이어져 있다
    (a1, b1, _, _), (a2, b2, _, _), (a3, b3, _, _) = edges
    assert b1 == a2 == a3
    assert edges[0][3] == [(37.5, 126.9), (37.5, 126.91)]


def test_칸_밖으로_잘린_way_는_건너뛴다():
    cut = {'type': 'way', 'id': 7, 'tags': {'highway': 'primary'}, 'nodes': [1, 2],
           'geometry': [{'lat': 37.5, 'lon': 126.9}, None]}
    assert graph([cut]) == (0, [])


def test_곧은_길의_가운데_점은_줄이고_양끝은_남긴다():
    pts = [(37.51, 126.93 + i * 0.001) for i in range(21)]     # 동서로 곧은 1.8km, 교차점 없음
    _, edges = graph([way(1, {'highway': 'primary'}, list(range(100, 121)), pts)])
    assert len(edges) == 1
    assert edges[0][3] == [(37.51, 126.93), (37.51, 126.95)]


def test_칸은_0_5도_넷으로_나눈다():
    assert sorted(quarters(37, 126)) == [(37, 126), (37, 126.5), (37.5, 126), (37.5, 126.5)]
