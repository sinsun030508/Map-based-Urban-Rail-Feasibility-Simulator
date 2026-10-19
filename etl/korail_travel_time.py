"""KTX 구간 소요시간 — TAGO 「열차정보」에서 받아 `data/build/ktx_travel_time.csv` 로 적는다.

**왜 미리 받아 두나.** 비교표가 뜰 때 외부 API 를 부르면, API 가 느리거나 죽은 날
비교 화면 전체가 같이 멈춘다. 다른 기준값과 같은 방식으로 미리 받아 파일에 둔다.

**왜 값 하나가 아니라 계통을 가르나.** 같은 구간에 경로가 둘인 일이 있다 — 서울~부산은
고속선 계통과 더 싸고 느린 우회(구포 경유) 계통으로 갈린다. 한 열차의 시각을
"KTX 소요시간" 으로 적으면 출처가 공표값이 되어 다음 사람이 의심하지 않는다. 그래서
**계통별 중앙값과 범위**를 적고, 화면의 한 칸에는 **빠른 계통의 중앙값**만 쓴다.

**그런데 섣불리 가르지 않는다.** 운임 절벽만으로 가르니 `밀양~천안아산` 이 1편 107분 /
7편 100~115분 으로 갈렸다 — 2분 차이를 계통이라 부른 것이고, 더 나쁜 것은 **그 1편이
대표값**이 된다는 점이다. 양쪽에 3편 이상 있고 시간 범위가 겹치지 않을 때만 가른다
(`split_systems`). 483구간 중 둘을 다 넘은 것은 **14개**뿐이고, 전부 고속선과
재래선이 갈리는 구간이다 — `오송~익산` 이 43편 28~36분 대 6편 75~79분으로 가장 크다.

**고르기는 운임이 아니라 시간으로 한다.** 묶기만 운임으로 한다 — "비싼 쪽이 빠르다" 는
서울~부산에서만 확인했지 다른 노선에서는 모른다(`test_싼_쪽이_빠르면_그_쪽이_1등이다`).

**받은 응답 원본을 `data/cache/ktx/` 에 남긴다.** 집계 규칙을 고치는 동안 659쌍을 세 번
다시 받았다(한 번에 15분). `--rebuild` 는 네트워크를 쓰지 않고 원본으로 CSV 만 다시 만든다.

```bash
set -a && . ./.env && set +a
python etl/korail_travel_time.py              # 받아서 CSV 까지
python etl/korail_travel_time.py --rebuild    # 규칙만 고쳤을 때 (안 받는다)
python etl/korail_travel_time.py --preflight  # 몇 쌍인지만
```

API: https://apis.data.go.kr/1613000/TrainInfo/
  GetCtyCodeList               도시코드
  GetCtyAcctoTrainSttnList     도시별 역 — `cityCode` 가 **필수**다(빠지면 resultCode 99)
  GetStrtpntAlocFndTrainInfo   출발·도착역 기준 시각표. `numOfRows` 를 무시하고 전부 준다

막힌 길과 폐기된 옛 경로는 `docs/research-log.md` 의 「KTX 소요시간」에 적어 뒀다.
"""
import argparse
import csv
import json
import os
import re
import statistics
import sys
import time
import urllib.parse
import urllib.request
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STATION_FILE = ROOT / 'data' / 'build' / 'ktx_station.csv'
OUT_FILE = ROOT / 'data' / 'build' / 'ktx_travel_time.csv'
RAW_DIR = ROOT / 'data' / 'cache' / 'ktx'       # 받은 응답 원본 — 커밋하지 않는다

BASE = 'https://apis.data.go.kr/1613000/TrainInfo'
MIN_PAIR_KM = 30.0               # 이보다 가까우면 KTX 판정(길이 > 도시철도 상한)이 안 걸린다
PAUSE_S = 0.2                    # 개발계정 10,000회/일 — 몰아치지 않는다
RETRIES = 4                      # 전송 오류만 되풀이한다 (1·2·4초)
SAVE_EVERY = 20                  # 이만큼마다 CSV 를 다시 쓴다 — 끊겨도 남는다

COLUMNS = ['from_station', 'to_station', 'line_name', 'group_rank',
           'trains', 'median_min', 'min_min', 'max_min', 'fare_won', 'fetched_on']


# ── 집계 규칙 (파일도 네트워크도 읽지 않는다 — 테스트가 여기를 가린다) ──────────

def minutes(dep, arr):
    """`YYYYMMDDHHMMSS` 두 개의 차이를 분으로. 자정을 넘기면 하루를 더한다.

    0 분은 None 으로 돌려준다 — 0 분짜리 열차는 없고, 그대로 두면 **가장 빠른
    계통으로 뽑혀** 비교표에 0 분이 박힌다.
    """
    def at(s):
        return int(s[8:10]) * 60 + int(s[10:12])
    gap = at(arr) - at(dep)
    if gap < 0:
        gap += 24 * 60
    return gap or None


def group_by_fare(trains):
    """운임이 같은 열차를 한 묶음으로. 운임 칸이 없으면 버리지 않고 따로 묶는다."""
    groups = defaultdict(list)
    for t in trains:
        groups[t.get('adultcharge')].append(t)
    return dict(groups)


#: 가장 큰 운임 간격이 전체 폭의 이만큼을 넘어야 계통이 갈린 것으로 본다.
#: **판단값이다.** 막으려는 것은 **몇백 원 차이가 계통을 쪼개는 것**이다 — 서울→부산
#: 운임이 47,500 / 48,200 / 48,700 / 53,600 / 54,200 다섯 가지인데 경로가 갈리는 자리는
#: 48,700~53,600 의 4,900원 한 곳뿐이다(전체 폭 6,700 의 0.73). 나머지 간격은 700원
#: 이하로 정차역 차이다. 0.5 면 둘을 가른다.
SPLIT_RATIO = 0.5
MIN_GROUP_TRAINS = 3             # 판단값 — 아래 `split_systems` 참고


def split_point(fares):
    """정렬한 운임에서 **계통이 갈리는 경계값**. 안 갈리면 None.

    경계값 이상이 한 계통, 미만이 다른 계통이다. 간격이 고르면(어느 자리도 절벽이
    아니면) 자르지 않는다 — 억지로 둘로 나누면 없는 계통을 만들어 낸다.
    """
    uniq = sorted(set(fares))
    if len(uniq) < 2:
        return None
    gaps = [(uniq[i + 1] - uniq[i], uniq[i + 1]) for i in range(len(uniq) - 1)]
    widest, boundary = max(gaps)
    return boundary if widest >= (uniq[-1] - uniq[0]) * SPLIT_RATIO else None


def spans(group):
    """그 묶음의 소요시간들, 오름차순. 못 센 열차는 뺀다."""
    return sorted(m for m in (minutes(t['depplandtime'], t['arrplandtime']) for t in group) if m)


def split_systems(trains):
    """계통별 열차 묶음. **가를 근거가 없으면 한 묶음으로 돌려준다.**

    가르는 것이 공짜가 아니다 — 표 아래에 "경로가 다른 N편" 이 붙고, 빠른 쪽의
    중앙값이 화면의 그 칸을 차지한다. 그래서 운임 절벽(`split_point`)을 찾은 뒤에도
    **두 조건을 둘 다** 넘어야 가른다. 실제 자료로 가려 보니 둘을 다 넘는 구간은
    경부축 13개뿐이고, 나머지는 운임 몇백 원 차이를 계통이라 부른 것이었다.

    ① **양쪽에 `MIN_GROUP_TRAINS` 편 이상.** 1편짜리를 계통이라 부르면 그 **한 열차의
       시각이 그대로 대표값**이 된다 — 계통을 가른 목적이 바로 그걸 피하는 것이었다.
       실제로 `밀양~천안아산` 이 1편 107분 / 7편 100~115분 으로 갈렸다. 3 은 판단값이다.
       막으려는 것은 정확도가 아니라 **한 편짜리가 대표값이 되는 것**이다.
    ② **시간 범위가 겹치지 않을 것.** 겹치면 갈라 적어도 읽을 게 없다 —
       `경주~대전` 은 32편 59~69분 / 3편 68~73분 이었다. 경로가 갈리면 범위도 갈린다:
       `부산~서울` 은 44편 143~176분 과 11편 180~205분 으로 떨어져 있다.

    **절벽이 없을 때도 한 묶음이다.** 전에는 `by_fare` 를 그대로 흘려보내 **운임 종류만큼
    계통이 생겼다.** 실제 자료에서는 안 터졌지만(절벽이 늘 있었다) 규칙이 아니라 운이었다.
    """
    by_fare = group_by_fare(trains)
    cut = split_point([int(f) for f in by_fare if f and f.isdigit()])
    if cut is None:
        return [trains]
    low, high = [], []
    for fare, group in by_fare.items():
        (high if fare and fare.isdigit() and int(fare) >= cut else low).extend(group)
    a, b = spans(low), spans(high)
    if len(a) < MIN_GROUP_TRAINS or len(b) < MIN_GROUP_TRAINS:
        return [trains]
    if not (a[-1] < b[0] or b[-1] < a[0]):
        return [trains]
    return [low, high]


def summarise(trains):
    """계통별 한 줄씩. **빠른 계통(중앙값이 가장 작은 묶음)이 `group_rank` 1 이다.**

    묶기는 운임으로 하되 가르는 조건은 `split_systems` 가 본다.
    고르기는 시간으로 한다 — "비싼 쪽이 빠르다" 를 전제하지 않는다.
    """
    ktx = [t for t in trains if t.get('traingradename', 'KTX').startswith('KTX')]

    rows = []
    for group in split_systems(ktx):
        spans_ = spans(group)
        if not spans_:
            continue
        # 묶은 뒤에는 열쇠가 운임이 아닐 수 있으므로(절벽으로 합쳤다) 실제 열차에서 꺼낸다.
        # 계통 안에서도 운임이 몇백 원 갈리므로 **가장 싼 값**을 대표로 적는다
        fares = sorted(int(t['adultcharge']) for t in group
                       if str(t.get('adultcharge', '')).isdigit())
        rows.append({'trains': len(spans_),
                     'median_min': round(statistics.median(spans_)),
                     'min_min': spans_[0],
                     'max_min': spans_[-1],
                     'fare_won': fares[0] if fares else None})
    rows.sort(key=lambda r: r['median_min'])
    for rank, row in enumerate(rows, start=1):
        row['group_rank'] = rank
    return rows


def haversine_km(lat1, lon1, lat2, lon2):
    from math import asin, cos, radians, sin, sqrt
    p1, p2 = radians(lat1), radians(lat2)
    a = sin((p2 - p1) / 2) ** 2 + cos(p1) * cos(p2) * sin(radians(lon2 - lon1) / 2) ** 2
    return 2 * 6371.0088 * asin(sqrt(a))


def pairs_to_fetch(stations, min_km=MIN_PAIR_KM):
    """같은 노선에 있고 `min_km` 이상 떨어진 역 쌍. 가까운 쌍은 판정이 안 걸려 받을 필요가 없다."""
    out = []
    by_line = defaultdict(list)
    for s in stations:
        by_line[s['line_name']].append(s)
    for line, group in by_line.items():
        for i in range(len(group)):
            for j in range(i + 1, len(group)):
                a, b = group[i], group[j]
                if haversine_km(a['lat'], a['lng'], b['lat'], b['lng']) >= min_km:
                    out.append((line, a['name'], b['name']))
    return out


def remaining(pairs, done):
    """아직 안 받은 쌍만. `done` 은 받아 둔 `(from, to)` 모음이며 **방향을 가리지 않는다** —
    대표역을 어느 쪽에서 골랐느냐에 따라 순서가 뒤집힐 뿐 같은 구간이다."""
    seen = {tuple(sorted(p)) for p in done}
    return [p for p in pairs if tuple(sorted((p[1], p[2]))) not in seen]


# ── 입출력 ────────────────────────────────────────────────────────────────────

def call(op, **params):
    """한 번 부른다. **전송 오류는 되풀이해 본다** — 659쌍을 받는 10분 동안 한 번은
    끊기고(`WinError 10060` 으로 실제로 날렸다), 그 한 번에 전부 날아가면 안 된다.
    다만 **API 가 내놓은 오류는 되풀이하지 않는다** — 키나 파라미터가 틀린 것이라
    다시 불러도 같다."""
    key = os.environ.get('DATA_GO_KR_KEY')
    if not key:
        sys.exit('DATA_GO_KR_KEY 가 없습니다 — `set -a && . ./.env && set +a` 를 먼저 하세요')
    params = {'serviceKey': key, '_type': 'json', 'numOfRows': 500, 'pageNo': 1, **params}
    url = BASE + '/' + op + '?' + urllib.parse.urlencode(params)
    for attempt in range(RETRIES):
        try:
            with urllib.request.urlopen(url, timeout=30) as resp:
                body = json.loads(resp.read().decode('utf-8'))
            break
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
            if attempt == RETRIES - 1:
                raise
            print('    전송 실패({}/{}) — {}초 뒤 다시: {}'.format(
                attempt + 1, RETRIES, 2 ** attempt, e))
            time.sleep(2 ** attempt)
    if 'OpenAPI_ServiceResponse' in body:
        head = body['OpenAPI_ServiceResponse']['cmmMsgHeader']
        raise RuntimeError(op + ' — ' + head.get('errMsg', '') + ' (' + head.get('returnAuthMsg', '') + ')')
    head = body.get('response', body).get('header', {})
    if head.get('resultCode') not in ('00', None):
        raise RuntimeError(op + ' — ' + str(head))
    items = body.get('response', {}).get('body', {}).get('items')
    if not items:
        return []
    item = items.get('item', [])
    return item if isinstance(item, list) else [item]


def node_ids():
    """역 이름 → `NAT…` 코드. 도시코드를 돌며 모은다.

    같은 이름이 여러 도시에 있으면 **버린다.** 어느 쪽인지 모르는 채로 쓰면 엉뚱한
    구간의 시각표를 받아 그대로 적게 된다 — 빈 칸이 낫다.
    """
    by_name, dropped = {}, set()
    for city in call('GetCtyCodeList'):
        for st in call('GetCtyAcctoTrainSttnList', cityCode=city['citycode']):
            name, node = st['nodename'], st['nodeid']
            if name in by_name and by_name[name] != node:
                dropped.add(name)
            by_name[name] = node
        time.sleep(PAUSE_S)
    for name in dropped:
        by_name.pop(name, None)
    return by_name, sorted(dropped)


def short_name(name):
    """`울산(통도사)` → `울산`. 괄호 안은 부제다."""
    return re.sub(r'\([^)]*\)', '', name).strip()


def resolve_ids(names, ids):
    """우리 역 이름 → TAGO 코드. 못 찾은 이름은 **넣지 않는다**(빈 칸이 된다).

    두 이름표가 어긋나는 자리다. 우리 역 파일은 공식 이름 `울산(통도사)` 를 쓰고
    TAGO 는 `울산` 을 쓴다 — 처음 받을 때 울산·진부가 통째로 빠졌는데 자료가 없어서가
    아니라 **내가 못 찾은 것**이었다.

    **정확한 이름이 먼저다.** TAGO 는 `판교(경기)`/`판교(충남)` 처럼 괄호로 서로 다른
    역을 가르기도 한다. 벗긴 이름부터 보면 엉뚱한 역의 시각표를 받아 그대로 적게 된다.
    그리고 **둘 이상이 같은 이름으로 줄어들면 둘 다 버린다** — `node_ids` 가 겹친 이름을
    버리는 것과 같은 이유다.
    """
    found, by_short = {}, defaultdict(list)
    for name in names:
        if name in ids:
            found[name] = ids[name]
            continue
        short = short_name(name)
        if short and short != name and short in ids:
            by_short[short].append(name)
    for short, owners in by_short.items():
        if len(owners) == 1:
            found[owners[0]] = ids[short]
    return found


def coverage(pairs, rows):
    """노선마다 `(노선, 받은 구간, 받을 구간)`. 이름 순.

    **전체 숫자만 보면 노선 하나가 통째로 빈 것이 안 보인다.** 등급 `00` 만 받았을 때
    강릉선이 0/N 이었는데 "직통 KTX 가 없는 구간 238개" 라는 한 줄에 묻혔다 — 예외도
    안 나고 로그도 조용했다. 노선 단위로 세면 `KTX 강릉선 0/12` 로 바로 드러난다.

    방향은 무시한다(`remaining` 과 같다). 한 구간이 여러 노선에 걸쳐 있으면 양쪽에 센다.
    """
    want = defaultdict(set)
    for line, a, b in pairs:
        want[line].add(frozenset((a, b)))
    have = {frozenset((r['from_station'], r['to_station'])) for r in rows}
    return sorted((line, len(ps & have), len(ps)) for line, ps in want.items())


def read_stations():
    with STATION_FILE.open(encoding='utf-8') as f:
        return [{'line_name': r['line_name'], 'name': r['station_name'],
                 'lat': float(r['latitude']), 'lng': float(r['longitude'])}
                for r in csv.DictReader(f.read().replace('﻿', '').splitlines())]


def next_weekday(today=None):
    """다음 평일. 하루치 시각표라 **공휴일을 피하고 평일을 고른다.**"""
    day = (today or date.today()) + timedelta(days=1)
    while day.weekday() >= 5:
        day += timedelta(days=1)
    return day


def write_csv(rows):
    OUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    with OUT_FILE.open('w', encoding='utf-8', newline='') as f:
        w = csv.DictWriter(f, fieldnames=COLUMNS)
        w.writeheader()
        for row in sorted(rows, key=lambda r: (r['line_name'], r['from_station'],
                                               r['to_station'], r['group_rank'])):
            w.writerow({c: row.get(c) for c in COLUMNS})


def raw_file(when):
    """날짜마다 따로 둔다 — 섞으면 어느 날 시각표인지 모른다."""
    return RAW_DIR / ('trains_' + when + '.jsonl')


def read_raw(when):
    """받아 둔 응답. `{(from, to): [열차…]}`.

    **왜 원본을 남기나.** 집계 규칙(계통을 가르는 조건)을 고치는 동안 659쌍을 **세 번**
    다시 받았다 — 한 번에 15분이다. 원본이 있으면 `--rebuild` 로 네트워크 없이 다시
    집계한다. 규칙은 앞으로도 바뀔 것이고, 받은 자료는 안 바뀐다.
    """
    f = raw_file(when)
    if not f.exists():
        return {}
    out = {}
    for line in f.read_text(encoding='utf-8').splitlines():
        if line.strip():
            d = json.loads(line)
            out[(d['from'], d['to'])] = d['trains']
    return out


def append_raw(when, a, b, trains):
    """한 쌍씩 바로 덧붙인다 — 끊겨도 **그 한 쌍만** 잃는다."""
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    with raw_file(when).open('a', encoding='utf-8') as f:
        print(json.dumps({'from': a, 'to': b, 'trains': trains}, ensure_ascii=False), file=f)


def build_rows(pairs, raw, when):
    """받아 둔 응답 → CSV 줄과 빈 구간 목록. **네트워크를 쓰지 않는다.**

    한 구간이 여러 노선에 걸쳐 있으면 **먼저 나온 노선 이름**을 쓴다 — 받을 때도
    그 한 번만 받는다(`remaining` 이 방향과 중복을 거른다).
    """
    line_of = {}
    for line, a, b in pairs:
        line_of.setdefault(frozenset((a, b)), line)
    rows, empty = [], []
    for (a, b), trains in raw.items():
        found = summarise(trains)
        if not found:
            empty.append(a + '~' + b)
        for row in found:
            rows.append({**row, 'from_station': a, 'to_station': b,
                         'line_name': line_of.get(frozenset((a, b)), ''), 'fetched_on': when})
    return rows, sorted(empty)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--date', help='YYYYMMDD. 기본은 다음 평일')
    ap.add_argument('--limit', type=int, help='쌍 개수 제한 (시험용)')
    ap.add_argument('--preflight', action='store_true', help='받지 않고 몇 쌍인지만 센다')
    ap.add_argument('--rebuild', action='store_true',
                    help='받아 둔 원본으로 CSV 만 다시 만든다 (네트워크 안 씀)')
    args = ap.parse_args()

    stations = read_stations()
    pairs = pairs_to_fetch(stations)
    print('역 {}개 · 받을 쌍 {}개'.format(len(stations), len(pairs)))
    if args.preflight:
        return

    when = args.date or next_weekday().strftime('%Y%m%d')
    raw = read_raw(when)
    if raw:
        print('{} 에 {}구간이 있습니다 — 다시 받지 않습니다'.format(raw_file(when).name, len(raw)))

    missing = set()
    if args.rebuild:
        if not raw:
            sys.exit('{} 가 없습니다 — 먼저 --rebuild 없이 한 번 받으세요'.format(raw_file(when)))
    else:
        tago, dropped = node_ids()
        names = sorted({s['name'] for s in stations})
        codes = resolve_ids(names, tago)
        print('TAGO 역 {}개 (이름이 겹쳐 뺀 역 {}개: {}) — 우리 역 {}개 중 {}개를 맞췄습니다'
              .format(len(tago), len(dropped), ', '.join(dropped) or '없음',
                      len(names), len(codes)))

        todo = remaining(pairs, set(raw))
        if args.limit:
            todo = todo[:args.limit]
        try:
            for n, (line, a, b) in enumerate(todo, start=1):
                if a not in codes or b not in codes:
                    missing.update(x for x in (a, b) if x not in codes)
                    continue
                # **`trainGradeCode` 를 걸지 않는다.** `00`(KTX)만 받았더니 강릉선이 통째로
                # 빠졌다 — 서울~진부는 전부 KTX-이음(16)이다. 계열이 여섯이라(00 · 07·0A·10
                # 산천 · 16 이음 · 19 청룡) 등급으로 거르면 하나 늘 때마다 코드를 고쳐야 한다.
                # 전부 받아 `summarise` 가 **이름으로** 거른다
                trains = call('GetStrtpntAlocFndTrainInfo', depPlaceId=codes[a],
                              arrPlaceId=codes[b], depPlandTime=when)
                append_raw(when, a, b, trains)        # 한 쌍씩 바로 남긴다
                raw[(a, b)] = trains
                if n % SAVE_EVERY == 0:
                    print('  {}/{} …'.format(n, len(todo)))
                time.sleep(PAUSE_S)
        except KeyboardInterrupt:
            print('멈춥니다 — 받아 둔 데까지 적고 끝냅니다. 다시 돌리면 이어서 받습니다')

    rows, empty = build_rows(pairs, raw, when)
    write_csv(rows)

    print('{} 에 {}줄 ({}구간)'.format(OUT_FILE.name, len(rows),
                                      len({(r['from_station'], r['to_station']) for r in rows})))
    if missing:
        print('코드를 못 찾은 역 {}개: {}'.format(len(missing), ', '.join(sorted(missing))))
    if empty:
        print('직통 KTX 가 없는 구간 {}개 — 빈 칸으로 둔다'.format(len(empty)))
    # **노선 단위로 센다.** 위의 전체 숫자만 보면 노선 하나가 통째로 빈 것이 묻힌다
    print('노선별 (받은 구간/받을 구간)')
    for line, got, want in coverage(pairs, rows):
        print('  {:<14} {}/{}{}'.format(line, got, want,
                                        '   ← 통째로 비었다. 왜인지 보라' if not got else ''))


if __name__ == '__main__':
    main()
