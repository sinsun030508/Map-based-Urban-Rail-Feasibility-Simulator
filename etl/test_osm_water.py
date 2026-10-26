"""
호수·저수지 영역 — OSM 응답을 우회 판단에 쓸 다각형으로 바꾸는 규칙.

틀려도 조용히 넘어가는 곳
  ① 큰 호수는 relation 이고 바깥 경계가 **여러 way 로 쪼개져** 온다. 이어 붙이지 못하면
     소양호가 통째로 빠지는데 오류는 안 난다
  ② 같은 호수가 여러 칸(1°×1°)에 걸려 **두 번** 온다 — 겹쳐 세면 안 된다
  ③ 넓이를 위경도 그대로 재면 위도마다 크기가 달라진다 — 미터 좌표계로 잰다

    cd etl && python -m pytest test_osm_water.py
"""

import osm_water as w

# 위도 37.5 에서 경도 0.01도 ≈ 0.88km, 위도 0.01도 ≈ 1.11km
LAT0, LNG0 = 37.5, 127.5


def way(i, coords, name=None, water='reservoir'):
    tags = {'natural': 'water', 'water': water}
    if name:
        tags['name'] = name
    return {'type': 'way', 'id': i, 'tags': tags,
            'geometry': [{'lat': la, 'lon': lo} for la, lo in coords]}


def square(dlat, dlng, lat=LAT0, lng=LNG0):
    return [(lat, lng), (lat + dlat, lng), (lat + dlat, lng + dlng), (lat, lng + dlng), (lat, lng)]


class Test다각형으로_바꾸기:
    def test_닫힌_way_는_다각형이다(self):
        bodies = w.water_bodies([way(1, square(0.02, 0.02), '가저수지')])
        assert len(bodies) == 1
        assert bodies[0]['name'] == '가저수지'
        # 약 2.22km × 1.76km
        assert 3.5 < bodies[0]['area_km2'] < 4.3

    def test_쪼개진_relation_은_이어_붙인다(self):
        # 바깥 경계 하나를 두 way 로 나누고, 하나는 거꾸로 적는다
        ring = square(0.03, 0.03)
        a = ring[:3]
        b = list(reversed(ring[2:]))
        rel = {'type': 'relation', 'id': 9, 'tags': {'natural': 'water', 'water': 'lake',
                                                     'name': '나호'},
               'members': [
                   {'type': 'way', 'role': 'outer',
                    'geometry': [{'lat': la, 'lon': lo} for la, lo in a]},
                   {'type': 'way', 'role': 'outer',
                    'geometry': [{'lat': la, 'lon': lo} for la, lo in b]},
                   # 섬은 안쪽 경계다 — 우회 판단에서는 호수를 통째로 막으므로 쓰지 않는다
                   {'type': 'way', 'role': 'inner',
                    'geometry': [{'lat': la, 'lon': lo} for la, lo in square(0.005, 0.005, LAT0 + 0.01, LNG0 + 0.01)]},
               ]}
        bodies = w.water_bodies([rel])
        assert len(bodies) == 1
        assert bodies[0]['name'] == '나호'
        assert bodies[0]['area_km2'] > 8          # 약 3.33 × 2.64 = 8.8km²

    def test_작은_못은_뺀다(self):
        # 0.005° × 0.005° ≈ 0.24km² — 1km² 미만은 우회할 만한 물이 아니다
        assert w.water_bodies([way(2, square(0.005, 0.005))]) == []

    def test_여러_칸에_걸린_호수는_한_번만_센다(self):
        e = way(3, square(0.02, 0.02), '다저수지')
        assert len(w.water_bodies([e, e])) == 1


class Test단순화:
    def test_경계점을_줄여도_모양은_25m_안이다(self):
        # 원 위에 점 2,000개 — 소양호처럼 점이 많은 경계를 흉내 낸다
        import math
        r = 0.02
        ring = [(LAT0 + r * math.sin(t / 2000 * 2 * math.pi),
                 LNG0 + r * math.cos(t / 2000 * 2 * math.pi)) for t in range(2000)]
        ring.append(ring[0])
        b = w.water_bodies([way(4, ring)])[0]
        pts = sum(len(poly[0]) for poly in b['coordinates'])
        assert pts < 400
        assert abs(b['area_km2'] - w.water_bodies([way(5, ring)], simplify_m=0)[0]['area_km2']) < 0.1
