"""큰길 중심선 — 역을 둘 수 없는 길(골목·단지 통로·자동차전용도로·교량·터널)을 거르는 규칙."""

from osm_roads import roads, usable


def way(i, tags, pts=((37.51, 126.93), (37.51, 126.95))):
    return {'type': 'way', 'id': i, 'tags': tags,
            'geometry': [{'lat': a, 'lon': b} for a, b in pts]}


def test_큰길_등급만_받는다():
    for cls in ('trunk', 'primary', 'secondary', 'tertiary'):
        assert usable({'highway': cls})
    # 단지 통로·골목은 역을 옮겨 봐야 또 단지 안이다. 고속도로에는 역을 둘 수 없다
    for cls in ('residential', 'service', 'unclassified', 'motorway', 'trunk_link', 'footway'):
        assert not usable({'highway': cls})


def test_자동차전용도로_교량_터널_토막은_뺀다():
    assert not usable({'highway': 'trunk', 'motorroad': 'yes'})
    assert not usable({'highway': 'primary', 'bridge': 'yes'})
    assert not usable({'highway': 'primary', 'bridge': 'viaduct'})
    assert not usable({'highway': 'secondary', 'tunnel': 'yes'})
    # 「아니다」 를 적은 것은 받는다
    assert usable({'highway': 'primary', 'bridge': 'no', 'tunnel': 'no', 'motorroad': 'no'})


def test_같은_way_는_한_번만_좌표는_위도_경도_소수_5자리():
    els = [way(1, {'highway': 'primary'}), way(1, {'highway': 'primary'}),
           way(2, {'highway': 'service'}), {'type': 'node', 'id': 3}]
    out = roads(els, simplify_m=0)
    assert out == [[(37.51, 126.93), (37.51, 126.95)]]


def test_곧은_길의_가운데_점은_줄이고_양끝은_남긴다():
    pts = [(37.51, 126.93 + i * 0.001) for i in range(21)]     # 동서로 곧은 1.8km
    out = roads([way(1, {'highway': 'primary'}, pts)])
    assert len(out) == 1
    assert out[0][0] == (37.51, 126.93) and out[0][-1] == (37.51, 126.95)
    assert len(out[0]) == 2


def test_칸은_0_5도_넷으로_나눈다():
    from osm_roads import quarters
    assert sorted(quarters(37, 126)) == [(37, 126), (37, 126.5), (37.5, 126), (37.5, 126.5)]
