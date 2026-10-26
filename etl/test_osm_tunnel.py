"""
지하 비율 실측 — OSM 노선 관계에서 터널 길이의 비율을 재는 규칙.

틀려도 조용히 넘어가는 곳
  ① 조각 방향이 제각각이다 — 못 맞추면 줄이 앞뒤로 튀어 길이가 부풀거나 줄어든다
  ② 역 이름을 잘못 짚으면 엉뚱한 구간을 잰다 — 사업 연장과 견줘 거부한다
  ③ 관계가 덜 그려져 끊긴 곳을 직선으로 이으면 없는 선로를 센다

    cd etl && python -m pytest test_osm_tunnel.py
"""

import pytest

import osm_tunnel as t

LAT = 37.5
DLNG_KM = 111.320 * 0.7933          # 위도 37.5 에서 경도 1도 ≈ 88.3km


def lng(km):
    return 127.0 + km / DLNG_KM


def way(i, km0, km1, tunnel=False, reverse=False):
    """동서로 놓인 직선 조각 — km0 에서 km1 까지, 0.5km 마다 점"""
    n = int(round(abs(km1 - km0) / 0.5))
    pts = [(LAT, lng(km0 + (km1 - km0) * k / n)) for k in range(n + 1)]
    if reverse:
        pts = pts[::-1]
    tags = {'railway': 'subway'}
    if tunnel:
        tags['tunnel'] = 'yes'
    return pts, tags, i


def rel(ways, stops):
    """관계 응답 모양 — `out geom` 의 멤버 좌표 + 조각·역 태그"""
    members, elements = [], []
    for pts, tags, i in ways:
        members.append({'type': 'way', 'ref': i, 'role': '',
                        'geometry': [{'lat': a, 'lon': b} for a, b in pts]})
        elements.append({'type': 'way', 'id': i, 'tags': tags})
    for j, (name, km) in enumerate(stops):
        members.append({'type': 'node', 'ref': 1000 + j, 'role': 'stop',
                        'lat': LAT, 'lon': lng(km)})
        elements.append({'type': 'node', 'id': 1000 + j, 'tags': {'name': name}})
    return {'elements': [{'type': 'relation', 'id': 1, 'members': members}] + elements}


# 0~3km 지상, 3~7km 터널, 7~10km 지상. 역은 가(0)·나(3)·다(7)·라(10)
STOPS = [('가', 0), ('나역', 3), ('다', 7), ('라', 10)]


def line(reverse_first=False, reverse_mid=False):
    return rel([way(1, 0, 3, reverse=reverse_first),
                way(2, 3, 7, tunnel=True, reverse=reverse_mid),
                way(3, 7, 10)], STOPS)


class Test이어_붙이기:
    def test_노선_전체의_지하_비율(self):
        km, tun = t.measure_section(line(), None, None, 10.0)
        assert km == pytest.approx(10.0, abs=0.02)
        assert tun / km == pytest.approx(0.4, abs=0.005)

    @pytest.mark.parametrize('first, mid', [(True, False), (False, True), (True, True)])
    def test_뒤집힌_조각도_같은_답이다(self, first, mid):
        # 첫 조각이 뒤집혀 있으면 두 번째 조각이 그 「시작점」 에 붙는다 — 뒤집어 맞춘다
        km, tun = t.measure_section(line(first, mid), None, None, 10.0)
        assert km == pytest.approx(10.0, abs=0.02)
        assert tun == pytest.approx(4.0, abs=0.02)

    def test_끊긴_곳은_세지_않는다(self):
        # 3~4km 가 빠진 관계 — 직선으로 이으면 없는 선로 1km 를 지상으로 센다
        d = rel([way(1, 0, 3), way(2, 4, 7, tunnel=True)], [('가', 0), ('다', 7)])
        pts, segs = t.chain(t.stops_and_ways(d)[1])
        km, tun, gap = t.measure(pts, segs, (LAT, lng(0)), (LAT, lng(7)))
        assert gap == pytest.approx(1.0, abs=0.02)
        assert km == pytest.approx(6.0, abs=0.02)

    def test_많이_끊기면_재지_않는다(self):
        d = rel([way(1, 0, 3), way(2, 4, 7, tunnel=True)], [('가', 0), ('다', 7)])
        with pytest.raises(ValueError, match='끊겨'):
            t.measure_section(d, '가', '다', 7.0)


class Test역_사이:
    def test_두_역_사이만_잰다(self):
        km, tun = t.measure_section(line(), '나', '라', 7.0)
        assert km == pytest.approx(7.0, abs=0.02)
        assert tun / km == pytest.approx(4 / 7, abs=0.005)

    def test_역_순서가_거꾸로여도_같다(self):
        assert t.measure_section(line(), '라', '나', 7.0) == pytest.approx(
            t.measure_section(line(), '나', '라', 7.0))

    def test_역_글자는_떼고_견준다(self):
        # OSM 은 「나역」, 표는 「나」 — 또는 그 반대
        assert t.measure_section(line(), '나역', '다역', 4.0)[0] == pytest.approx(4.0, abs=0.02)

    def test_없는_역이면_멈춘다(self):
        with pytest.raises(ValueError, match='찾지 못했'):
            t.measure_section(line(), '마', '라', 7.0)

    def test_연장과_다르면_거부한다(self):
        # 나~라(7km) 를 사업 연장 10km 로 적었다 — 역을 잘못 짚은 것이다
        with pytest.raises(ValueError, match='다릅니다'):
            t.measure_section(line(), '나', '라', 10.0)

    def test_연장이_없으면_길이_검사를_건너뛴다(self):
        # rail_station_seed 는 연장 열이 없다
        assert t.measure_section(line(), '나', '라', None)[0] == pytest.approx(7.0, abs=0.02)


class Test터널_표시:
    def test_터널과_지하_위치(self):
        assert t.is_tunnel({'tunnel': 'yes'})
        assert t.is_tunnel({'tunnel': 'building_passage'})
        assert t.is_tunnel({'location': 'underground'})

    def test_고가와_지상은_아니다(self):
        assert not t.is_tunnel({'bridge': 'yes', 'layer': '2'})
        assert not t.is_tunnel({'tunnel': 'no'})
        assert not t.is_tunnel({})


class Test추정치_바꾸기:
    """`etl.apply_measured_underground` — 잰 값이 추정치를 대신하고, 비고가 그 사실을 말한다."""

    @staticmethod
    def frame():
        import pandas as pd
        return pd.DataFrame([
            {'line_name': '가선', 'section_name': '가~나', 'underground_ratio': 0.20,
             'note': '차량기지 포함 / 지하비율 추정치 (공식 미공표)'},
            {'line_name': '다선', 'section_name': '다~라', 'underground_ratio': 0.70,
             'note': '지하비율 추정치 (공식 미공표)'},
        ])

    @staticmethod
    def measured(tmp_path, rows):
        import pandas as pd
        p = tmp_path / 'underground_measured.csv'
        pd.DataFrame(rows).to_csv(p, index=False)
        return p

    def test_잰_구간만_바뀐다(self, tmp_path):
        import etl
        p = self.measured(tmp_path, [{'line_name': '가선', 'section_name': '가~나',
                                      'underground_ratio': 0.0}])
        df = etl.apply_measured_underground(self.frame(), p)
        assert df.loc[0, 'underground_ratio'] == 0.0
        assert df.loc[0, 'note'] == '차량기지 포함 / ' + etl.MEASURED_NOTE
        # 못 잰 구간은 추정치 그대로, 비고도 그대로
        assert df.loc[1, 'underground_ratio'] == 0.70
        assert etl.ESTIMATE_NOTE in df.loc[1, 'note']

    def test_맞는_구간이_없으면_멈춘다(self, tmp_path):
        import etl
        p = self.measured(tmp_path, [{'line_name': '가선', 'section_name': '가~다',
                                      'underground_ratio': 0.0}])
        with pytest.raises(ValueError, match='매칭 실패'):
            etl.apply_measured_underground(self.frame(), p)
