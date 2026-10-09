"""
세종 BRT 표정속도 실측 - 세종 BIS 실시간 차량 위치를 모아 잰다.

왜 재나
  `mode_capacity.speed_kmh` 의 BRT 2종(고급형 25.0 / 저급형 18.0)만 가정값이다.
  저급형 18.0 이 기준 수단인 시내버스 21.3 보다 느려 편익이 항상 0 으로 찍히는데,
  속도가 가정값인 동안에는 그 0 을 결론으로 쓸 수 없다.
  공식 공표값을 6곳에서 찾아 실패하고, 시간표에는 기점 출발시각만 있어
  옮겨 적을 소요시간이 없었다 (`docs/research-log.md`).
  남은 길이 실시간 차량 추적이다.

무엇을 재나
  세종 BRT B4 (반석역~오송역). `brt_seed.csv` 에서 HIGH 등급이고 전용도로를 달린다.
  방향별로 route_id 가 따로 있어 상·하행이 자료 구조에서 갈라진다.

어떻게 재나
  ① `searchBusRealLocationDetail.do` 를 POLL_SEC 마다 불러 차량 좌표를 쌓는다
     (운영 화면은 3초마다 부른다. 우리는 30초로 충분하다 - 45분 주행에 ±1.1%)
  ② 좌표를 노선 선형(`searchBusRouteDetail.do` 의 571점)에 투영해
     **노선상 진행거리 km** 로 바꾼다.
     정류장 id 를 쓰지 않는 이유: 실시간 응답의 stop_id 가 "지금 있는 정류장"인지
     "방금 지난 정류장"인지 확실하지 않다. 좌표는 그 모호함이 없다.
  ③ 차량별로 (시각, 진행거리) 궤적을 만들고 한 운행씩 끊는다
  ④ **양 끝의 멈춰 있는 구간을 잘라낸다.** 표정속도는 중간 정차는 포함하지만
     종점 회차 대기는 포함하지 않는다. 진행거리가 변하지 않는 구간이 그 대기다
  ⑤ 표정속도 = 이동한 거리 / 걸린 시간

반복해서 돌릴 것
  **몇 번 재느냐보다 어떻게 골라 재느냐가 중요하다.** 반복은 분산(차량별 습관,
  신호 운)을 줄이지만 편향(같은 시간대만, 한쪽 방향만, 평일만)은 줄이지 않는다.
  관측은 `data/cache/brt/` 에 쌓이고 다시 돌리면 더해진다. `--report` 가
  요일 x 시간대 x 방향 격자로 채워진 칸을 보여 주므로, 빈 칸을 보고 다음에
  언제 돌릴지 고르면 된다. OSM 좌표 수집에서 캐시를 여러 번 돌려 채운 것과 같다.

    python etl/sejong_brt_speed.py --collect 60     # 60분 수집
    python etl/sejong_brt_speed.py --report         # 쌓인 것으로 집계
"""

import argparse
import csv
import io
import json
import math
import statistics
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

BASE = 'https://bis.sejong.go.kr'
HEADERS = {
    'User-Agent': 'Mozilla/5.0',
    'Referer': BASE + '/web/traffic/traffic_bus_line_search.view',
    'X-Requested-With': 'XMLHttpRequest',
    'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
}

# 재는 노선. 대개 방향마다 route_id 가 따로 있어 방향이 자료 구조에서 갈라진다.
# 등급은 `data/seed/brt_seed.csv` 의 구간과 맞춰 붙였다.
#   turn=True 는 **갔던 길을 되돌아오는** 노선이라 선형에 같은 길이 두 번 나온다.
#   기점=종점이어도 **순환선**(B0 내·외선, B5)은 한 바퀴 돌 뿐 되돌아오지 않으므로
#   turn=False 다. 기점·종점 이름만 보고 붙이면 틀린다 - check_shape() 가 검사한다.
ROUTES = {
    # 세종 BRT 본선 (brt_seed "세종 BRT 반석역~세종시~오송역" HIGH 31.2km)
    '293000317': dict(name='B4', mode='BRT_HIGH', direction='반석역->오송역', turn=False),
    '293000318': dict(name='B4', mode='BRT_HIGH', direction='오송역->반석역', turn=False),
    # 같은 전용도로를 쓰는 심야·새벽 노선. 정류장이 하나 더 많아 더 느릴 것으로 본다
    '293000362': dict(name='B2', mode='BRT_HIGH', direction='반석네거리->오송역', turn=False),
    '293000363': dict(name='B2', mode='BRT_HIGH', direction='오송역->반석네거리', turn=False),
    # 대전~세종 BRT (brt_seed "대전역~세종 BRT" LOW 53.0km). 회차 노선이라 선형이 왕복이다
    '187000003': dict(name='B1', mode='BRT_LOW', direction='대전역~오송역 왕복', turn=True),
    # 세종~청주 BRT. brt_seed 의 "행복도시~청주대농지구 32.3km" 와 구간이 달라 연장은 BIS 실측을 쓴다
    '271000801': dict(name='B3', mode='BRT_LOW', direction='세종터미널->청주공항', turn=False),
    '271000802': dict(name='B3', mode='BRT_LOW', direction='청주공항->세종터미널', turn=False),
    # 대조군 - 일반 시내버스. B4 와 기점·종점이 같지만(반석역~오송역) **길은 다르다**
    # (선형 겹침 18~36%, 공통 정류장은 양 끝 2개뿐. 1005 는 조치원을 지나 돌아간다).
    #
    # **같은 도로 대조군은 원리적으로 불가능하다.** B4 전용도로의 중간 정류장 4곳을
    # 지나는 노선을 모두 조회해 보니 전부 A·B 계열이었다 - 세종 BRT 전용도로에는
    # 일반 시내버스가 다니지 않는다. 설계가 그렇다.
    #
    # 그래서 1005 의 역할은 "같은 길에서 BRT 가 몇 배 빠른가" 가 아니라
    # **"일반 시내버스가 이 시간대에 연평균보다 얼마나 빠른가" 를 재는 탐침**이다.
    # 그 치우침으로 BRT 측정값을 나누면 심야 실측을 연평균 쪽으로 끌어올 수 있다.
    '293000282': dict(name='1005', mode='CITY_BUS', direction='반석역->오송역', turn=False),
    '293000283': dict(name='1005', mode='CITY_BUS', direction='오송역->반석역', turn=False),
    # 같은 전용도로를 쓰지만 정류장 수가 다른 노선들. 역간격이 0.63~2.55km 로 4배
    # 벌어져 **같은 도로에서 "정류장 간격이 표정속도를 얼마나 깎는가" 를 잴 수 있다.**
    # mode_capacity 가 수단별 spacing_km 을 쓰고 있어(중전철 1.06km->32.4,
    # 복선전철 4.98km->47.6) 그 관계를 실측으로 받칠 값어치가 있다.
    #
    # **등급은 붙이지 않는다(OTHER).** brt_seed.csv 에 이 노선들의 구간이 없어
    # 고급형인지 저급형인지 근거가 없다. BRT 중앙값을 오염시키지 않게 따로 둔다.
    '293000303': dict(name='B0순환', mode='OTHER', direction='외선', turn=False),
    '293000304': dict(name='B0터미널', mode='OTHER', direction='1', turn=False),
    '293000305': dict(name='B0터미널', mode='OTHER', direction='2', turn=False),
    '293000311': dict(name='B5', mode='OTHER', direction='1', turn=False),
    '293000312': dict(name='B5', mode='OTHER', direction='2', turn=False),
    '271000805': dict(name='B7', mode='OTHER', direction='집현동->비하', turn=False),
    '271000806': dict(name='B7', mode='OTHER', direction='비하->집현동', turn=False),
}

POLL_SEC = 30          # 운영 화면은 3초. 우리 목적엔 30초로 충분하고 서버에 덜 부담된다
DWELL_M = 200          # 양 끝에서 이만큼 안에 머문 관측은 종점 대기로 보고 잘라낸다
MIN_COVER = 0.90       # 이 비율 이상을 달렸으면 전구간 표본으로 센다
SEG_MIN_KM = 10.0      # 완주를 못 했어도 이어서 이만큼 달렸으면 구간 표본으로 센다
RUN_GAP_SEC = 900      # 관측이 이만큼 끊기면 다른 운행으로 본다
BACKSLIDE_KM = 1.0     # 진행거리가 이만큼 줄면 새 운행(종점에서 되돌아감)으로 본다
FWD_WINDOW = 40        # 전진 투영 창. 30초에 80km/h 로도 0.67km(약 12점)뿐이라 넉넉하다
BACK_WINDOW = 3        # GPS 흔들림으로 조금 뒤로 잡히는 것은 허용한다
OFF_ROUTE_KM = 1.0     # 선형에서 이만큼 벗어나면 그 노선을 달리는 차량이 아니다
STOP_WINDOW = 60       # stop_id 로 정해진 정류장 주변에서 찾는 창. 왕복의 두 구간은
                       # 색인이 수백 점 떨어져 있어 이 정도로는 섞이지 않는다
MAX_KMH = 120          # 이보다 빠르면 투영이 튄 것이다 (버스는 이렇게 못 달린다)

CACHE = Path(__file__).resolve().parent.parent / 'data' / 'cache' / 'brt'
BUILD = Path(__file__).resolve().parent.parent / 'data' / 'build'
OBS_COLS = ['ts', 'route_id', 'plate_no', 'lat', 'lng', 'dist_km',
            'stop_id', 'stop_name', 'spot_speed']


def post(path, **form):
    req = urllib.request.Request(BASE + path, urllib.parse.urlencode(form).encode(), HEADERS)
    with urllib.request.urlopen(req, timeout=25) as r:
        return json.loads(r.read().decode('utf-8', 'replace'))


def haversine_km(lat1, lng1, lat2, lng2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lng2 - lng1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * 6371.0088 * math.asin(math.sqrt(h))


def route_shape(route_id):
    """노선 선형과 정류장. 받아서 캐시한다 (--report 는 네트워크 없이 돈다)."""
    CACHE.mkdir(parents=True, exist_ok=True)
    f = CACHE / ('shape_' + route_id + '.json')
    if f.exists():
        return json.loads(f.read_text(encoding='utf-8'))

    d = post('/web/traffic/searchBusRouteDetail.do', busRouteId=route_id)
    pts = [(float(p['lat']), float(p['lng']))
           for p in d['busRouteDetailMapVoList'] if p['lat'] and p['lng']]
    cum = [0.0]
    for a, b in zip(pts, pts[1:]):
        cum.append(cum[-1] + haversine_km(a[0], a[1], b[0], b[1]))
    shape = {
        'route_id': route_id,
        'points': pts,
        'cum_km': cum,
        'length_km': round(cum[-1], 3),
        'alloc': d['busRouteDetailList'][0]['alloc_time'].strip(),
    }

    rows = [{'ord': int(st['stop_ord']), 'name': st['stop_name'].strip(),
             'id': st['stop_id'].strip(), 'lat': float(st['lat']), 'lng': float(st['lng'])}
            for st in d['busRouteDetailList']]
    shape['stops'] = place_stops(shape, rows, ROUTES.get(route_id, {}).get('turn', False))
    # 실시간 응답의 stop_id 로 위치를 짚는다
    shape['stop_idx'] = {st['id']: st['idx'] for st in shape['stops']}
    f.write_text(json.dumps(shape, ensure_ascii=False), encoding='utf-8')
    return shape


def nearest(shape, lat, lng, lo=0, hi=None):
    """선형의 [lo, hi) 구간에서 가장 가까운 점의 색인과 거리."""
    pts = shape['points']
    hi = len(pts) if hi is None else min(hi, len(pts))
    lo = max(0, lo)
    best, best_d = None, 1e9
    for i in range(lo, hi):
        d = haversine_km(lat, lng, pts[i][0], pts[i][1])
        if d < best_d:
            best, best_d = i, d
    return best, best_d


def turn_ord(rows):
    """회차 노선의 반환 정류장 순번 - 첫 정류장에서 직선거리가 가장 먼 정류장."""
    a = rows[0]
    return max(rows, key=lambda r: haversine_km(a['lat'], a['lng'], r['lat'], r['lng']))['ord']


def place_stops(shape, rows, turn):
    """정류장을 선형 색인에 맞춘다.

    **좌표만으로는 회차 노선의 구간을 가릴 수 없다.** 같은 장소의 가는 편·오는 편
    차로가 100m 안에 나란히 있어서 정류장 좌표가 양쪽 선형 모두에 그만큼 가깝다.
    실제로 B1 에서 가는 편 정류장이 오는 편 자리에 무작위로 붙어, 15번 보람동은
    제자리(28.89km)인데 11번 국제과학비즈니스벨트는 복귀 자리(83.95km)로 갔다.
    그 지도를 쓰면 차량 진행거리가 거꾸로 기어간다.

    가릴 수 있는 것은 좌표가 아니라 **`stop_ord`** 다. 순번이 곧 구간이다 -
    반환 정류장 전은 가는 편, 후는 오는 편. 구간을 먼저 가르고 그 안에서만
    찾으면 한 구간에 같은 장소가 한 번만 나오므로 애매함이 없다.
    """
    n = len(shape['points'])
    ti = turn_index(shape) if turn else n
    t_ord = turn_ord(rows) if turn else None
    out = []
    cursor, cur_leg = 0, None
    for r in sorted(rows, key=lambda x: x['ord']):
        if turn and r['ord'] > t_ord:
            leg, lo, hi = 'back', ti, n                 # 오는 편
        elif turn:
            leg, lo, hi = 'out', 0, min(ti + 1, n)      # 가는 편
        else:
            leg, lo, hi = 'one', 0, n
        first = leg != cur_leg
        if first:
            cursor, cur_leg = lo, leg
        # 구간 안에서 **앞으로만** 찾는다. 한 구간에서 같은 장소를 한 번만 지나므로
        # 전진 탐색이면 혼동이 없고 순서도 보장된다.
        #
        # 단 **구간의 첫 정류장은 앞쪽 10% 로 더 좁힌다.** 순환선(B0·B5)은 기점과
        # 종점이 같은 장소여서, 선형의 끝점이 시작점보다 미세하게 더 가까우면
        # 1번 정류장이 선형 끝에 붙는다. 그러면 전진 커서가 거기 갇혀 나머지
        # 정류장이 전부 몰리고(B5 에서 실제로 1~38번이 모두 23.37km 에 쌓였다),
        # 이탈 거리만 1.6km, 2.6km 로 늘어난다. 노선의 첫 정류장은 정의상
        # 노선의 시작이므로 그렇게 멀리서 찾을 이유가 없다.
        top = lo + max(10, (hi - lo) // 10) if first else hi
        i, off = nearest(shape, r['lat'], r['lng'], cursor, top)
        cursor = i
        out.append({'ord': r['ord'], 'name': r['name'], 'id': r['id'], 'idx': i,
                    'km': round(shape['cum_km'][i], 3), 'off_km': round(off, 3)})
    return out


def project_km(shape, lat, lng, stop_id=None, last_idx=None):
    """좌표를 선형에 투영해 노선상 진행거리(km)로 바꾼다.

    점 간격이 약 58m 라 가장 가까운 점을 쓰면 오차가 +-29m 다. 33km 에 대해
    0.09% 이므로 선분까지 투영할 값어치가 없다.

    **회차 노선(B1)은 선형이 왕복이라 같은 길이 두 번 나온다.** 그대로 가장 가까운
    점을 찾으면 되돌아오는 차량이 가는 쪽 색인에 붙는다. 실제로 그렇게 붙은 차량이
    진행거리 29km 에서 25km 로 **거꾸로 기어가다가** 갑자기 81km 로 튀었다.

    그래서 **`stop_id` 를 1순위 기준으로 쓴다.** 왕복 노선도 같은 장소의 정류장 id 가
    방향마다 다르므로(B1 은 55개가 모두 유일하다) id 하나로 구간이 정해진다. 상태를
    들고 다니지 않아 잡힌 순서에 좌우되지 않고, 좌표만 있으면 나중에 다시 계산할 수도 있다.

    stop_id 를 못 쓰면 직전 색인부터 앞쪽만 찾는 방식으로 물러난다.
    """
    anchor = shape['stop_idx'].get(stop_id) if stop_id else None
    if anchor is not None:
        i, d = nearest(shape, lat, lng, anchor - STOP_WINDOW, anchor + STOP_WINDOW)
        if i is not None and d <= OFF_ROUTE_KM:
            return shape['cum_km'][i], d, i
    if last_idx is not None:
        i, d = nearest(shape, lat, lng, last_idx - BACK_WINDOW, last_idx + FWD_WINDOW)
        if i is not None and d <= OFF_ROUTE_KM:
            return shape['cum_km'][i], d, i
    i, d = nearest(shape, lat, lng)
    return shape['cum_km'][i], d, i


def check_shape(shape, route_id):
    """선형 지도가 쓸 만한지 검사한다. 아니면 멈춘다.

    정류장 위치가 틀리면 차량 진행거리가 조용히 어긋나 그럴듯한 속도가 나온다.
    실제로 B1 에서 55개 중 24군데가 순서를 거슬렀는데 숫자는 멀쩡해 보였다.
    또 **기점=종점이라고 다 왕복은 아니다** - 순환선(B0·B5)은 한 바퀴 돌 뿐이다.
    turn 을 잘못 붙이면 구간이 엉뚱하게 갈리므로 여기서 가려낸다.
    """
    name = ROUTES[route_id]['name'] + ' ' + ROUTES[route_id]['direction']
    stops = shape['stops']
    back = [(a['ord'], b['ord']) for a, b in zip(stops, stops[1:]) if b['km'] < a['km']]
    if back:
        raise SystemExit('%s: 정류장 위치가 순서를 거슬렀습니다 %s개 (%s...). '
                         'turn 설정과 place_stops 를 보세요'
                         % (name, len(back), back[:3]))
    far = [(st['ord'], st['name'], st['off_km']) for st in stops if st['off_km'] > 0.5]
    if far:
        raise SystemExit('%s: 선형에서 500m 넘게 떨어진 정류장 %s' % (name, far[:3]))
    # 되돌아오는지 기하로 확인해 turn 설정과 맞춰 본다
    pts, n = shape['points'], len(shape['points'])
    half = n // 2
    tail = {'points': pts[half:], 'cum_km': shape['cum_km'][half:]}
    step = max(1, half // 40)
    near = [nearest(tail, pts[i][0], pts[i][1])[1] < 0.3 for i in range(0, half, step)]
    retraces = sum(near) / len(near) > 0.8
    if retraces != ROUTES[route_id]['turn']:
        raise SystemExit('%s: 선형은 %s인데 turn=%s 로 돼 있습니다'
                         % (name, '왕복' if retraces else '왕복이 아님',
                            ROUTES[route_id]['turn']))


def collect(minutes, only=None, obs_name='observations.csv'):
    CACHE.mkdir(parents=True, exist_ok=True)
    picked = [r for r in ROUTES if only is None or ROUTES[r]['name'] in only]
    shapes = {rid: route_shape(rid) for rid in picked}
    for rid, sh in shapes.items():
        check_shape(sh, rid)
    for rid, sh in shapes.items():
        r = ROUTES[rid]
        print('%s %-18s 선형 %6.2f km  정류장 %2d개  %s%s'
              % (r['name'], r['direction'], sh['length_km'], len(sh['stops']),
                 sh['alloc'] or '운행횟수 없음', ' [회차]' if r['turn'] else ''))

    obs_file = CACHE / obs_name
    new_file = not obs_file.exists()
    deadline = time.time() + minutes * 60
    rows = 0
    seen = set()
    last_idx = {}          # (route_id, plate_no) -> 직전 선형 색인. 전진 투영에 쓴다
    last_seen = {}         # 같은 키의 직전 관측 시각. 끊기면 색인을 버린다
    with obs_file.open('a', newline='', encoding='utf-8') as fh:
        w = csv.DictWriter(fh, OBS_COLS)
        if new_file:
            w.writeheader()
        while time.time() < deadline:
            now = datetime.now().replace(microsecond=0)
            stamp = now.isoformat()
            tick = 0
            for rid, sh in shapes.items():
                try:
                    lst = post('/web/traffic/searchBusRealLocationDetail.do',
                               busRouteId=rid).get('busRealLocList', [])
                except Exception as e:
                    print('  %s %s 실패: %s' % (stamp, rid, type(e).__name__))
                    continue
                for b in lst:
                    if not b.get('lat') or not b.get('lng'):
                        continue
                    lat, lng = float(b['lat']), float(b['lng'])
                    key = (rid, b['plate_no'])
                    prev = last_idx.get(key)
                    if prev is not None and last_seen.get(key) is not None:
                        if (now - last_seen[key]).total_seconds() > RUN_GAP_SEC:
                            prev = None          # 오래 끊겼으면 이어 보지 않는다
                    sid = (b.get('stop_id') or '').strip()
                    km, off, idx = project_km(sh, lat, lng, sid, prev)
                    if off > OFF_ROUTE_KM:       # 선형을 벗어나면 그 노선 차량이 아니다
                        continue
                    last_idx[key] = idx
                    last_seen[key] = now
                    w.writerow({'ts': stamp, 'route_id': rid, 'plate_no': b['plate_no'],
                                'lat': lat, 'lng': lng, 'dist_km': round(km, 3),
                                'stop_id': sid,
                                'stop_name': (b.get('stop_name') or '').strip(),
                                'spot_speed': b.get('speed', '')})
                    rows += 1
                    tick += 1
                    seen.add((rid, b['plate_no']))
            fh.flush()
            left = int(deadline - time.time())
            print('  %s  차량 %2d대  누적 %5d행  남은 %d분'
                  % (stamp, tick, rows, left // 60), flush=True)
            if time.time() < deadline:
                time.sleep(POLL_SEC)
    print('')
    print('%d행 저장 (%s), 차량 %d대' % (rows, obs_file, len(seen)))


def load_obs(recompute=True):
    """쌓인 관측을 모두 모아 읽는다.

    수집 과정을 여러 개 동시에 돌릴 수 있어야 한다 - 막차 시각이 노선마다
    다르고, 한 과정이 끝날 때까지 기다리면 그 사이 운행이 날아간다.
    그래서 과정마다 파일을 따로 쓰고 집계에서 합친다.
    """
    rows = []
    for f in sorted(CACHE.glob("observations*.csv")):
        with f.open(encoding="utf-8") as fh:
            rows.extend(csv.DictReader(fh))
    if not rows:
        return []
    for r in rows:
        r['t'] = datetime.fromisoformat(r['ts'])
        r['dist_km'] = float(r['dist_km'])
    if recompute:
        # 저장해 둔 dist_km 을 믿지 않고 좌표로 다시 계산한다.
        # 수집기는 돌면서 계산해야 하므로 그때 쓴 선형 지도가 틀렸을 수 있다
        # (실제로 B1 정류장 지도를 두 번 고쳤다). 좌표와 stop_id 는 원본이라
        # 다시 계산하면 그 수정이 과거 관측까지 소급된다. 집계가 결정적이 된다.
        shapes = {}
        for r in rows:
            rid = r['route_id']
            if rid not in ROUTES:
                continue
            if rid not in shapes:
                shapes[rid] = route_shape(rid)
            km, off, _ = project_km(shapes[rid], float(r['lat']), float(r['lng']),
                                    (r.get('stop_id') or '').strip() or None)
            r['dist_km'] = round(km, 3)
            r['off_km'] = round(off, 3)
    rows.sort(key=lambda r: (r['route_id'], r['plate_no'], r['t']))
    return rows


def split_runs(rows):
    """차량별 관측을 한 운행씩 끊는다.

    한 운행 안에서 진행거리는 줄지 않는다. 그래서 **그 운행에서 지금까지 간
    가장 먼 거리보다 뒤로 내려가면** 다른 운행이다. 한 걸음씩만 보면 못 잡는다 -
    종점에 닿은 버스가 반대 방향 운행을 시작하면서 몇 분간 이전 route_id 에
    남아 있는 일이 있고(1005 에서 실제로 나왔다), 그때 조금씩 3.5km 를 뒤로 갔다.
    """
    runs, cur, peak = [], [], None
    for r in rows:
        if cur:
            prev = cur[-1]
            same = (r['route_id'] == prev['route_id'] and r['plate_no'] == prev['plate_no'])
            gap = (r['t'] - prev['t']).total_seconds()
            # 투영이 튀면 거리가 앞으로도 불가능하게 뛴다 (실제로 30초에 55km 가 나왔다)
            step = r['dist_km'] - prev['dist_km']
            jump = gap > 0 and step / (gap / 3600) > MAX_KMH
            if not same or gap > RUN_GAP_SEC or peak - r['dist_km'] > BACKSLIDE_KM or jump:
                runs.append(cur)
                cur, peak = [], None
        cur.append(r)
        peak = r['dist_km'] if peak is None else max(peak, r['dist_km'])
    if cur:
        runs.append(cur)
    return runs

def turn_index(shape):
    """회차 노선의 반환점 - 기점에서 직선거리가 가장 먼 선형 점.

    왕복 노선은 갔던 길을 되돌아오므로 기점에서 가장 먼 점이 곧 회차 지점이다.
    정류장 이름을 뒤지지 않아도 되고 노선이 바뀌어도 따라간다.
    """
    p0 = shape['points'][0]
    best, best_d = 0, -1.0
    for i, p in enumerate(shape['points']):
        d = haversine_km(p0[0], p0[1], p[0], p[1])
        if d > best_d:
            best, best_d = i, d
    return best


def legs_of(shape, turn):
    """재는 구간. 회차 노선은 가는 편·오는 편을 따로 잰다.

    왕복 전체(B1 은 104km)를 기다리면 한 표본에 두 시간이 걸리고, 그 안에
    회차 대기까지 섞인다. 반환점에서 끊으면 편도 표본이 두 개 나온다.
    """
    if not turn:
        return [('전구간', 0.0, shape['length_km'])]
    tk = shape['cum_km'][turn_index(shape)]
    return [('가는 편', 0.0, tk), ('오는 편', tk, shape['length_km'])]


def measure_leg(run, leg_name, lo_km, hi_km):
    """한 운행에서 한 구간의 표정속도를 뽑는다. 못 뽑으면 (None, 이유).

    표정속도는 중간 정차는 포함하고 **종점 회차 대기는 포함하지 않는다.**
    진행거리가 변하지 않는 관측이 그 대기인데, **구간 끝에 닿아 있을 때만**
    대기로 본다. 관측이 시작된 지점을 대기로 오인해 잘라내면 실제로 달린
    시간이 빠져 속도가 부풀어 오른다.

    완주 여부는 판단하지 않고 cover_pct 를 함께 돌려준다 - 부르는 쪽이
    전구간 표본으로 셀지 구간 표본으로 셀지 고른다. 33km 노선에서 20km 를
    이어서 따라갔으면 그것도 쓸 수 있는 측정이다. 완주만 세면 표본이 아깝다.
    """
    span = hi_km - lo_km
    seen = [r for r in run if lo_km - 0.05 <= r['dist_km'] <= hi_km + 0.05]
    if len(seen) < 3:
        return None, '%s 관측 %d개' % (leg_name, len(seen))
    lo = min(r['dist_km'] for r in seen)
    hi = max(r['dist_km'] for r in seen)
    if hi - lo < 0.1:
        return None, '%s 움직이지 않았다' % leg_name

    dwell = DWELL_M / 1000
    at_start = lo <= lo_km + dwell          # 기점에 닿아 있었다 -> 출발 대기를 자른다
    at_end = hi >= hi_km - dwell            # 종점에 닿았다 -> 도착 후 대기를 자른다
    dep = [r for r in seen if r['dist_km'] <= lo + dwell][-1] if at_start else seen[0]
    arr = [r for r in seen if r['dist_km'] >= hi - dwell][0] if at_end else seen[-1]
    secs = (arr['t'] - dep['t']).total_seconds()
    dist = arr['dist_km'] - dep['dist_km']
    if secs <= 0 or dist <= 0:
        return None, '%s 시간/거리가 0' % leg_name
    return {
        'route_id': dep['route_id'],
        'leg': leg_name,
        'plate_no': dep['plate_no'],
        'depart': dep['t'],
        'arrive': arr['t'],
        'weekday': dep['t'].strftime('%a'),
        'hour': dep['t'].hour,
        'dist_km': round(dist, 3),
        'span_km': round(span, 3),
        'cover_pct': round(dist / span * 100, 1),
        'full': dist >= MIN_COVER * span,
        'minutes': round(secs / 60, 1),
        'speed_kmh': round(dist / (secs / 3600), 2),
        'n_obs': len(seen),
        # 검산용 - 표정속도 계산에는 쓰지 않는다 (sanity_check 설명 참고)
        'spot': [int(r['spot_speed']) for r in seen
                 if (r.get('spot_speed') or '').strip().lstrip('-').isdigit()],
    }, None


SEED = Path(__file__).resolve().parent.parent / 'data' / 'seed' / 'road_speed.csv'
BASELINE_CITY = '대전'      # 세종은 대전권이다. B1 은 대전~세종을 잇는다


def published_city_bus():
    """공표된 시내버스 표정속도. 국가지표체계 승인통계(`road_speed.csv`).

    대조군(일반 시내버스 1005)을 이 값과 견주면 **측정이 어느 시간대에 치우쳤는지**가
    숫자로 나온다. 공표값은 연간 실적이라 전 시간대를 섞은 값이고 내 측정은 특정
    시점이다. 둘의 비가 그 치우침이다.

    **그 비로 BRT 측정값을 나눠 연평균을 만들 수는 없다** - report() 설명 참고.

    세종은 6대 광역시 통계에 없어 대전 값을 대리로 쓴다 (근사).
    """
    rows = []
    with SEED.open(encoding='utf-8') as fh:
        for r in csv.DictReader(fh):
            if r['vehicle'] == '시내버스':
                rows.append((int(r['year']), r['city'], float(r['speed_kmh'])))
    if not rows:
        return None, None
    year = max(y for y, _, _ in rows)
    latest = {c: v for y, c, v in rows if y == year}
    return latest.get(BASELINE_CITY), statistics.median(sorted(latest.values()))


def label(rid, leg):
    r = ROUTES[rid]
    base = '%s %s' % (r['name'], r['direction'])
    return base if leg == '전구간' else '%s(%s)' % (base, leg)


def sanity_check(samples):
    """계산한 표정속도를 **쓰지 않는 자료**로 검산한다.

    실시간 응답에는 순간속도(`speed`)가 들어 있는데 표정속도 계산에는 쓰지 않는다.
    그래서 독립 검산이 된다 - 표정속도는 그 운행의 순간 최대속도를 넘을 수 없고,
    정차 시간이 분모에 들어가므로 순간 평균보다도 낮아야 한다. 투영이 어딘가
    틀리면 거리가 부풀어 이 조건이 깨진다.

    순간속도에 쓰레기 값이 섞인다(한 차량이 255km/h 로 찍혔다). 그래서 버스가
    낼 수 있는 속도(MAX_KMH)로 잘라서 본다.
    """
    over = []
    for m in samples:
        sp = [v for v in m.get('spot', []) if 0 <= v <= MAX_KMH]
        if sp and m['speed_kmh'] > max(sp) + 1:
            over.append((m, max(sp)))
    if over:
        print('검산 실패 - 표정속도가 순간 최대속도를 넘은 표본 %d개' % len(over))
        for m, mx in over[:5]:
            print('   %-24s %-12s 표정 %.2f > 순간최대 %d'
                  % (label(m['route_id'], m['leg']), m['plate_no'], m['speed_kmh'], mx))
        print('   **투영이 틀렸을 수 있습니다. 거리가 부풀었는지 보세요.**')
    else:
        n = sum(1 for m in samples if m.get('spot'))
        print('검산 통과 - 표본 %d개 모두 표정속도 <= 그 운행의 순간 최대속도' % n)
    print('')


def spacing_report(samples, shapes):
    """역간격이 표정속도를 얼마나 깎는가.

    B4 전용도로에 일반 시내버스는 다니지 않지만 **정류장 수가 다른 BRT 계열이
    여럿 있다** - 역간격이 0.63~2.73km 로 4.3배 벌어진다. 같은 도로에서 재면
    도로 조건이 통제되므로 역간격 효과만 남는다.

    `mode_capacity.spacing_km` 가 수단별로 다르고(중전철 1.06km->32.4,
    복선전철 4.98km->47.6) 그 관계가 이미 자료에 암묵적으로 들어 있다.
    실측으로 받칠 값어치가 있다.

    시간대를 섞으면 안 된다 - 심야 표본과 첨두 표본을 함께 회귀하면 역간격
    계수가 시간대 효과를 흡수한다. 그래서 시간대별로 따로 본다.
    """
    by_route = {}
    for m in samples:
        rid = m['route_id']
        sh = shapes[rid]
        spacing = sh['length_km'] / (len(sh['stops']) - 1)
        h = m['hour']
        slot = '첨두' if h in (7, 8, 17, 18) else ('심야' if (22 <= h or h < 6) else '보통')
        key = (slot, ROUTES[rid]['name'], round(spacing, 2))
        by_route.setdefault(key, []).append(m['speed_kmh'])

    print('역간격 대비 표정속도 (같은 전용도로, 시간대별로 나눠 본다)')
    if not by_route:
        print('  표본 없음')
        print('')
        return
    for slot in ('첨두', '보통', '심야'):
        rows = sorted((k, v) for k, v in by_route.items() if k[0] == slot)
        if not rows:
            continue
        print('  [%s]' % slot)
        print('    %-10s %8s %5s %9s' % ('노선', '역간격km', 'n', '중앙값'))
        for (_, name, sp), v in rows:
            print('    %-10s %8.2f %5d %8.2f' % (name, sp, len(v), statistics.median(v)))
        if len(rows) >= 3:
            xs = [k[2] for k, _ in rows]
            ys = [statistics.median(v) for _, v in rows]
            mx, my = statistics.mean(xs), statistics.mean(ys)
            den = sum((x - mx) ** 2 for x in xs)
            if den > 0:
                b = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / den
                print('    역간격 1km 늘 때 %+.2f km/h (노선 %d개 중앙값으로 단순 회귀)'
                      % (b, len(rows)))
                print('    주의: 노선마다 길·신호가 달라 역간격만의 효과는 아니다')
    print('')


def report():
    rows = load_obs()
    if not rows:
        print('관측이 없습니다. 먼저 --collect 로 모으세요.')
        return
    shapes = {rid: route_shape(rid) for rid in ROUTES}
    lo_t = min(r['t'] for r in rows)
    hi_t = max(r['t'] for r in rows)
    print('관측 %s행  %s ~ %s'
          % (format(len(rows), ','), lo_t.strftime('%Y-%m-%d(%a) %H:%M'),
             hi_t.strftime('%m-%d %H:%M')))
    print('')

    samples, short = [], []
    for run in split_runs(rows):
        rid = run[0]['route_id']
        if rid not in ROUTES:
            continue
        for leg_name, lo_km, hi_km in legs_of(shapes[rid], ROUTES[rid]['turn']):
            m, why = measure_leg(run, leg_name, lo_km, hi_km)
            if m is None:
                continue
            if m['full'] or m['dist_km'] >= SEG_MIN_KM:
                samples.append(m)
            else:
                short.append(m)

    full = [m for m in samples if m['full']]
    seg = [m for m in samples if not m['full']]
    print('전구간 표본 %d개 / 구간 표본 %d개 (%.0fkm 이상) / 너무 짧은 조각 %d개'
          % (len(full), len(seg), SEG_MIN_KM, len(short)))
    if not samples:
        print('')
        print('아직 %.0fkm 이상 이어서 따라간 차량이 없습니다. 더 모으세요.' % SEG_MIN_KM)
        print('가장 많이 달린 조각들:')
        for m in sorted(short, key=lambda x: -x['dist_km'])[:10]:
            print('   %-28s %-12s %s  %5.1f km (%4.1f%%)'
                  % (label(m['route_id'], m['leg']), m['plate_no'],
                     m['depart'].strftime('%m-%d %H:%M'), m['dist_km'], m['cover_pct']))
        return
    print('')

    print('%-28s %-16s %-12s %7s %6s %6s %7s'
          % ('구간', '출발', '차량', 'km', '비율', '분', 'km/h'))
    for m in sorted(samples, key=lambda x: x['depart']):
        print('%-28s %s  %-12s %7.2f %5.0f%% %6.1f %7.2f%s'
              % (label(m['route_id'], m['leg']), m['depart'].strftime('%m-%d %a %H:%M'),
                 m['plate_no'], m['dist_km'], m['cover_pct'], m['minutes'],
                 m['speed_kmh'], '' if m['full'] else '  (구간)'))
    print('')

    # 구간 표본이 전구간 표본과 어긋나는지 - 어긋나면 구간 값을 쓸 수 없다
    if full and seg:
        fm, sm = statistics.median([m['speed_kmh'] for m in full]),                  statistics.median([m['speed_kmh'] for m in seg])
        print('전구간 중앙값 %.2f  vs  구간 중앙값 %.2f  (%.2f배)' % (fm, sm, sm / fm))
        print('  구간 표본은 종점 부근의 가·감속과 혼잡을 덜 담아 조금 빠르게 나올 수 있다.')
        print('  둘이 크게 어긋나면 구간 표본을 섞지 말 것.')
        print('')

    # 등급별 집계 - 이게 mode_capacity.speed_kmh 에 들어갈 값이다
    print('등급별 (mode_capacity.speed_kmh 후보)')
    by_mode = {}
    for s in samples:
        by_mode.setdefault(ROUTES[s['route_id']]['mode'], []).append(s['speed_kmh'])
    for mode in ('BRT_HIGH', 'BRT_LOW', 'CITY_BUS'):
        v = by_mode.get(mode)
        if not v:
            print('  %-10s 표본 없음' % mode)
            continue
        med = statistics.median(v)
        line = '  %-10s n=%-3d 중앙값 %6.2f  평균 %6.2f  범위 %.2f~%.2f' % (
            mode, len(v), med, statistics.mean(v), min(v), max(v))
        if len(v) >= 2:
            sd = statistics.stdev(v)
            line += '  표준오차 %.2f' % (sd / math.sqrt(len(v)))
        print(line)
    print('')

    # 노선별 - 등급 안에서도 노선마다 다르면 중앙값을 쓸 근거가 된다
    print('노선별')
    by_route = {}
    for s in samples:
        by_route.setdefault(label(s['route_id'], s['leg']), []).append(s['speed_kmh'])
    for k in sorted(by_route):
        v = by_route[k]
        print('  %-30s n=%-3d 중앙값 %6.2f  범위 %.2f~%.2f'
              % (k, len(v), statistics.median(v), min(v), max(v)))
    print('')

    # 대조군으로 시간대 치우침을 잰다
    dj, med6 = published_city_bus()
    ctrl = by_mode.get('CITY_BUS')
    print('공표값과 견주기 (road_speed.csv, 국가지표체계 승인통계)')
    print('  공표 시내버스  %s %.1f / 6대 광역시 중앙값 %.1f  <- 연간 실적, 전 시간대'
          % (BASELINE_CITY, dj, med6))
    if not ctrl:
        print('  대조군(1005 일반버스) 표본이 없어 치우침을 못 잽니다.')
        print('  **--only 1005 로 같은 시간대를 함께 재야 측정값을 해석할 수 있습니다.**')
    else:
        obs_bus = statistics.median(ctrl)
        bias = obs_bus / dj
        print('  측정 일반버스  1005 %.2f (n=%d)  -> 공표값의 %.2f배' % (obs_bus, len(ctrl), bias))
        print('')
        print('  %-10s %10s %12s' % ('', '측정', '일반버스대비'))
        for mode in ('BRT_HIGH', 'BRT_LOW'):
            v = by_mode.get(mode)
            if not v:
                print('  %-10s %10s' % (mode, '표본 없음'))
                continue
            m = statistics.median(v)
            print('  %-10s %10.2f %11.2f배' % (mode, m, m / obs_bus))
        print('')
        print('  **치우침으로 나눠서 연평균을 만들 수는 없습니다.** 그러려면 BRT 와')
        print('  일반버스가 혼잡에 똑같이 민감해야 하는데, 그건 BRT 의 존재 이유를')
        print('  부정하는 가정입니다. 전용도로는 막힐 때 값을 하고 빈 도로에서는')
        print('  아무것도 사 주지 않습니다 - 실제로 심야에는 BRT 가 일반버스보다')
        print('  빠르지도 않게 나옵니다.')
        if bias > 1.3:
            print('')
            print('  지금 표본은 공표 연평균의 %.2f배인 시간대에서 나왔습니다.' % bias)
            print('  **절대값은 혼잡이 없을 때의 상한으로만 읽으세요.**')
            print('  BRT 우위 비율도 이 시간대에는 과소평가입니다.')
            print('  쓸 값을 얻으려면 첨두·보통 시간대를 직접 재야 합니다 (아래 격자).')
    print('')

    sanity_check(samples)

    # 역간격과 표정속도 - 같은 전용도로에서 정류장 수만 다른 노선들이 있다
    spacing_report(samples, shapes)

    # 격자 - 빈 칸을 보고 다음에 언제 돌릴지 고른다
    print('요일 x 시간대 격자 (등급별)')
    grid = {}
    for s in samples:
        kind = '주말' if s['weekday'] in ('Sat', 'Sun') else '평일'
        h = s['hour']
        if h in (7, 8, 17, 18):
            slot = '첨두'
        elif 22 <= h or h < 6:
            slot = '심야'
        else:
            slot = '보통'
        grid.setdefault((ROUTES[s['route_id']]['mode'], kind, slot), []).append(s['speed_kmh'])
    for mode in ('BRT_HIGH', 'BRT_LOW', 'CITY_BUS'):
        for kind in ('평일', '주말'):
            for slot in ('첨두', '보통', '심야'):
                v = grid.get((mode, kind, slot))
                mark = ('n=%-3d 중앙값 %6.2f' % (len(v), statistics.median(v))) if v \
                    else '비어 있음'
                print('  %-10s %s %-4s %s' % (mode, kind, slot, mark))
    print('')
    print('반복은 분산(차량별 습관·신호 운)을 줄이지만 치우침은 줄이지 않는다.')
    print('비어 있는 칸이 있으면 그 시간대에 다시 돌릴 것 - 심야에만 20번 재면')
    print('심야 값을 아주 정확하게 잰 것이고, 하루 평균은 여전히 모른다.')

    BUILD.mkdir(parents=True, exist_ok=True)
    out = BUILD / 'brt_speed_samples.csv'
    with out.open('w', newline='', encoding='utf-8') as fh:
        w = csv.DictWriter(fh, ['route_name', 'direction', 'leg', 'mode', 'plate_no',
                                'depart', 'arrive', 'weekday', 'hour', 'dist_km',
                                'span_km', 'cover_pct', 'full', 'minutes', 'speed_kmh',
                                'n_obs'])
        w.writeheader()
        for s in sorted(samples, key=lambda x: x['depart']):
            r = ROUTES[s['route_id']]
            w.writerow({'route_name': r['name'], 'direction': r['direction'],
                        'leg': s['leg'], 'mode': r['mode'], 'plate_no': s['plate_no'],
                        'depart': s['depart'].isoformat(), 'arrive': s['arrive'].isoformat(),
                        'weekday': s['weekday'], 'hour': s['hour'],
                        'dist_km': s['dist_km'], 'span_km': s['span_km'],
                        'cover_pct': s['cover_pct'], 'full': int(s['full']),
                        'minutes': s['minutes'], 'speed_kmh': s['speed_kmh'],
                        'n_obs': s['n_obs']})
    print('')
    print('%s 에 표본 %d개 저장' % (out, len(samples)))


def main():
    # 한글 출력이 cp949 콘솔에서 깨지지 않게 한다. import 하는 쪽을 건드리지
    # 않으려고 모듈이 아니라 여기서 감싼다
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
    ap = argparse.ArgumentParser()
    ap.add_argument('--collect', type=int, metavar='MINUTES', help='이 분만큼 수집한다')
    ap.add_argument('--report', action='store_true', help='쌓인 관측으로 집계한다')
    ap.add_argument('--only', nargs='+', metavar='노선', help='이 노선만 수집한다 (예: B4 1005)')
    ap.add_argument('--obs', default='observations.csv', help='관측을 쓸 파일 이름')
    a = ap.parse_args()
    if a.collect:
        collect(a.collect, only=a.only, obs_name=a.obs)
    if a.report or not a.collect:
        report()


if __name__ == '__main__':
    main()
