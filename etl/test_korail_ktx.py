"""
KTX 정차역 좌표 규칙. 틀려도 **그럴듯한 좌표가 나오는** 곳들이다 —
다른 도시의 같은 이름 역을 집거나, 지하철 역을 집거나, 공식 좌표의 오류를 그대로 받는다.
"""

import korail_ktx_stations as k


def node(name, lat, lon, **tags):
    return {'lat': lat, 'lon': lon, 'tags': {'name': name, **tags}}


def test_공식_표기가_달라도_별칭으로_찾는다():
    assert k.osm_names('김천구미')[0] == '김천(구미)'
    # 괄호 부기는 떼어 본다
    assert '진부' in k.osm_names('진부(오대산)')


def test_지하철_역은_빼되_일반열차가_서면_남긴다():
    by_name = k.index_osm([
        node('양평', 37.5256, 126.8861, station='subway'),                  # 서울 5호선
        node('양평', 37.4928, 127.4919, station='train'),
        node('동대구', 35.8792, 128.6283, station='subway', train='yes'),   # Korail (대경선)
        node('판교', 38.6087, 127.0215, operator='조선민주주의인민공화국 철도성'),
    ])
    assert by_name['양평'] == [(37.4928, 127.4919)]
    assert by_name['동대구'] == [(35.8792, 128.6283)]
    assert '판교' not in by_name


def test_같은_자리의_노드는_한_역이다():
    assert len(k.distinct_places([(37.3947, 127.1112), (37.3947, 127.1115)])) == 1


def test_같은_이름이_여럿이면_같은_노선의_다른_역에_가까운_쪽():
    seongnam, seocheon = (37.3947, 127.1112), (36.1554, 126.6794)
    bubal = (37.2604, 127.4903)                         # 중부내륙선의 다른 역
    assert k.pick([seocheon, seongnam], [bubal]) == seongnam


def test_공식_좌표가_어긋나면_OSM_을_쓰고_기록한다():
    by_name = k.index_osm([node('행신', 37.612, 126.834, station='train'),
                           node('서울', 37.5547, 126.9707, station='train')])
    official = {'행신': (37.3645, 126.4952),            # 원본의 실제 오류 — 인천 앞바다
                '서울': (37.55473, 126.9708)}
    rows, problems = k.resolve({'경부선': ['행신', '서울']}, by_name, official)
    by = {r['station_name']: r for r in rows}
    assert not problems
    assert by['행신']['latitude'] == 37.612
    assert by['행신']['coord_check'].startswith('공식 좌표와')
    assert by['서울']['coord_check'] == 'OK'


def test_못_찾은_역은_문제로_보고한다():
    rows, problems = k.resolve({'경부선': ['없는역']}, {}, {})
    assert not rows and len(problems) == 1
