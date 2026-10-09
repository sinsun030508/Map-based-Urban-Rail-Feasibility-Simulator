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
#   turn=True 는 회차 노선(기점=종점)이라 선형이 왕복이다 - project_km 설명 참고.
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
}

POLL_SEC = 30          # 운영 화면은 3초. 우리 목적엔 30초로 충분하고 서버에 덜 부담된다
DWELL_M = 200          # 양 끝에서 이만큼 안에 머문 관측은 종점 대기로 보고 잘라낸다
MIN_COVER = 0.90       # 노선의 이 비율 이상을 달린 운행만 표본으로 센다
RUN_GAP_SEC = 900      # 관측이 이만큼 끊기면 다른 운행으로 본다
BACKSLIDE_KM = 1.0     # 진행거리가 이만큼 줄면 새 운행(종점에서 되돌아감)으로 본다
FWD_WINDOW = 40        # 전진 투영 창. 30초에 80km/h 로도 0.67km(약 12점)뿐이라 넉넉하다
BACK_WINDOW = 3        # GPS 흔들림으로 조금 뒤로 잡히는 것은 허용한다
OFF_ROUTE_KM = 1.0     # 선형에서 이만큼 벗어나면 그 노선을 달리는 차량이 아니다

CACHE = Path(__file__).resolve().parent.parent / 'data' / 'cache' / 'brt'
BUILD = Path(__file__).resolve().parent.parent / 'data' / 'build'
OBS_COLS = ['ts', 'route_id', 'plate_no', 'lat', 'lng', 'dist_km', 'stop_name', 'spot_speed']


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
        'stops': [{'ord': int(s['stop_ord']), 'name': s['stop_name'], 'id': s['stop_id']}
                  for s in d['busRouteDetailList']],
        'alloc': d['busRouteDetailList'][0]['alloc_time'].strip(),
    }
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


def project_km(shape, lat, lng, last_idx=None):
    """좌표를 선형에 투영해 노선상 진행거리(km)로 바꾼다.

    점 간격이 약 58m 라 가장 가까운 점을 쓰면 오차가 +-29m 다. 33km 에 대해
    0.09% 이므로 선분까지 투영할 값어치가 없다.

    **회차 노선(B1)은 선형이 왕복이라 같은 길이 두 번 나온다.** 전체를 훑으면
    되돌아오는 차량이 가는 쪽 색인에 붙어 진행거리가 뒤로 튄다. 그래서 그 차량의
    직전 색인부터 앞쪽만 찾는다 - 회차점을 지나면 색인이 그대로 복귀 구간으로
    이어지므로 왕복이 하나의 증가하는 거리가 된다.
    창 안에서 선형을 벗어난 것으로 나오면 새 운행으로 보고 전체를 다시 훑는다.
    """
    if last_idx is not None:
        i, d = nearest(shape, lat, lng, last_idx - BACK_WINDOW, last_idx + FWD_WINDOW)
        if i is not None and d <= OFF_ROUTE_KM:
            return shape['cum_km'][i], d, i
    i, d = nearest(shape, lat, lng)
    return shape['cum_km'][i], d, i


def collect(minutes):
    CACHE.mkdir(parents=True, exist_ok=True)
    shapes = {rid: route_shape(rid) for rid in ROUTES}
    for rid, sh in shapes.items():
        r = ROUTES[rid]
        print('%s %-18s 선형 %6.2f km  정류장 %2d개  %s%s'
              % (r['name'], r['direction'], sh['length_km'], len(sh['stops']),
                 sh['alloc'] or '운행횟수 없음', ' [회차]' if r['turn'] else ''))

    obs_file = CACHE / 'observations.csv'
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
                    km, off, idx = project_km(sh, lat, lng, prev)
                    if off > OFF_ROUTE_KM:       # 선형을 벗어나면 그 노선 차량이 아니다
                        continue
                    last_idx[key] = idx
                    last_seen[key] = now
                    w.writerow({'ts': stamp, 'route_id': rid, 'plate_no': b['plate_no'],
                                'lat': lat, 'lng': lng, 'dist_km': round(km, 3),
                                'stop_name': b.get('stop_name', ''),
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


def load_obs():
    f = CACHE / 'observations.csv'
    if not f.exists():
        return []
    with f.open(encoding='utf-8') as fh:
        rows = list(csv.DictReader(fh))
    for r in rows:
        r['t'] = datetime.fromisoformat(r['ts'])
        r['dist_km'] = float(r['dist_km'])
    rows.sort(key=lambda r: (r['route_id'], r['plate_no'], r['t']))
    return rows


def split_runs(rows):
    """차량별 관측을 한 운행씩 끊는다."""
    runs, cur = [], []
    for r in rows:
        if cur:
            prev = cur[-1]
            same = (r['route_id'] == prev['route_id'] and r['plate_no'] == prev['plate_no'])
            gap = (r['t'] - prev['t']).total_seconds()
            back = prev['dist_km'] - r['dist_km']
            if not same or gap > RUN_GAP_SEC or back > BACKSLIDE_KM:
                runs.append(cur)
                cur = []
        cur.append(r)
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

    표정속도는 중간 정차는 포함하고 종점 회차 대기는 포함하지 않는다.
    양 끝에서 진행거리가 변하지 않는 관측이 그 대기이므로 잘라낸다.
    """
    span = hi_km - lo_km
    seen = [r for r in run if lo_km - 0.05 <= r['dist_km'] <= hi_km + 0.05]
    if len(seen) < 3:
        return None, '%s 관측 %d개' % (leg_name, len(seen))
    lo = min(r['dist_km'] for r in seen)
    hi = max(r['dist_km'] for r in seen)
    if hi - lo < MIN_COVER * span:
        return None, '%s %.0f%% 만 관측' % (leg_name, (hi - lo) / span * 100)

    dep = [r for r in seen if r['dist_km'] <= lo + DWELL_M / 1000][-1]
    arr = [r for r in seen if r['dist_km'] >= hi - DWELL_M / 1000][0]
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
        'minutes': round(secs / 60, 1),
        'speed_kmh': round(dist / (secs / 3600), 2),
        'n_obs': len(seen),
    }, None


def label(rid, leg):
    r = ROUTES[rid]
    base = '%s %s' % (r['name'], r['direction'])
    return base if leg == '전구간' else '%s(%s)' % (base, leg)


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

    samples, rejects = [], []
    for run in split_runs(rows):
        rid = run[0]['route_id']
        if rid not in ROUTES:
            continue
        got = False
        for leg_name, lo_km, hi_km in legs_of(shapes[rid], ROUTES[rid]['turn']):
            s, why = measure_leg(run, leg_name, lo_km, hi_km)
            if s:
                samples.append(s)
                got = True
            else:
                rejects.append((rid, run[0]['plate_no'], run[0]['t'], why))
        if not got:
            pass

    print('완주 표본 %d개 / 미완주 조각 %d개' % (len(samples), len(rejects)))
    if not samples:
        print('')
        print('아직 한 구간을 처음부터 끝까지 따라간 차량이 없습니다. 더 모으세요.')
        print('가장 많이 달린 조각들:')
        for rid, plate, t, why in rejects[:10]:
            print('   %-22s %s %s  %s' % (label(rid, '전구간'),
                                          plate, t.strftime('%m-%d %H:%M'), why))
        return
    print('')

    print('%-30s %-16s %-12s %7s %6s %7s'
          % ('구간', '출발', '차량', 'km', '분', 'km/h'))
    for s in sorted(samples, key=lambda x: x['depart']):
        print('%-30s %s  %-12s %7.2f %6.1f %7.2f'
              % (label(s['route_id'], s['leg']), s['depart'].strftime('%m-%d %a %H:%M'),
                 s['plate_no'], s['dist_km'], s['minutes'], s['speed_kmh']))
    print('')

    # 등급별 집계 - 이게 mode_capacity.speed_kmh 에 들어갈 값이다
    print('등급별 (mode_capacity.speed_kmh 후보)')
    by_mode = {}
    for s in samples:
        by_mode.setdefault(ROUTES[s['route_id']]['mode'], []).append(s['speed_kmh'])
    for mode in ('BRT_HIGH', 'BRT_LOW'):
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
    for mode in ('BRT_HIGH', 'BRT_LOW'):
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
                                'minutes', 'speed_kmh', 'n_obs'])
        w.writeheader()
        for s in sorted(samples, key=lambda x: x['depart']):
            r = ROUTES[s['route_id']]
            w.writerow({'route_name': r['name'], 'direction': r['direction'],
                        'leg': s['leg'], 'mode': r['mode'], 'plate_no': s['plate_no'],
                        'depart': s['depart'].isoformat(), 'arrive': s['arrive'].isoformat(),
                        'weekday': s['weekday'], 'hour': s['hour'],
                        'dist_km': s['dist_km'], 'minutes': s['minutes'],
                        'speed_kmh': s['speed_kmh'], 'n_obs': s['n_obs']})
    print('')
    print('%s 에 표본 %d개 저장' % (out, len(samples)))


def main():
    # 한글 출력이 cp949 콘솔에서 깨지지 않게 한다. import 하는 쪽을 건드리지
    # 않으려고 모듈이 아니라 여기서 감싼다
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
    ap = argparse.ArgumentParser()
    ap.add_argument('--collect', type=int, metavar='MINUTES', help='이 분만큼 수집한다')
    ap.add_argument('--report', action='store_true', help='쌓인 관측으로 집계한다')
    a = ap.parse_args()
    if a.collect:
        collect(a.collect)
    if a.report or not a.collect:
        report()


if __name__ == '__main__':
    main()
