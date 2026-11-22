"""동시성·부하 시험 — 단위 테스트(목)가 못 보는, **실제 MySQL·연결 풀·CPU 에서만 나는 문제**를 잡는다.

사용
  python tools/verify/concurrency.py                    ① 같은 시나리오 동시 계산  ② 긴 계산 여럿 동안 가벼운 요청 지연  ③ 계산·수정·삭제 혼합
  python tools/verify/concurrency.py --worst 30         허용 범위 안 최악 입력(점 50개 981km, 한 건 7~19초) N 개를 동시에 — 한도·연결 풀·다른 사용자 영향
  python tools/verify/concurrency.py --pool 2           DB 연결 풀을 2개로 줄여 독점을 드러낸다(계산이 연결을 잡았다면 N×시간÷2 로 늘어난다)
  python tools/verify/concurrency.py --debug-tx --worst 1   스프링 트랜잭션 DEBUG 로그 — 읽기·계산·쓰기 구간별 시각이 tools/verify/out/concurrency.log 에 찍힌다

**11-22 에 이걸로 찾은 것**(노트 「같은 시나리오를 바꾸는 쓰기는 줄을 선다」·「계산의 동시 실행 개수를 제한한다」)
  - 같은 시나리오 동시 계산 8개 → 7개가 500(MySQL 교착)   → 행 잠금(`lockOwned`)
  - 같은 시나리오 동시 수정 4개 → 3개가 500(낙관적 잠금)  → 같은 잠금
  - 최악 입력 30개 동시 → 10개 500(풀 고갈)·다른 사용자 조회 51초 → `CalculationGate`(사용자당 2·전체 코어/2, 넘으면 429)
**읽는 법** — 지금은 사용자당 한도(2) 때문에 ① 에서 429 가 나는 게 정상이다(「비정상」 은 200 이 아니라는 뜻으로만 표시). 500·서버 ERROR 로그가 0 이면 통과다.
"""
import argparse
import collections
import json
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from harness import P, Server, call, zigzag  # noqa: E402

SEOUL_BUSAN = [P(37.5547, 126.9706), P(35.1151, 129.0422)]
SEOUL_JAMSIL = [P(37.5663, 126.9779), P(37.5133, 127.1001)]
WORST = zigzag(50, 37.45, 37.63, 126.80, 127.20)       # ≈981km, 서울 도심 — 상한 근처 최악


def mk(tok, title, pts):
    return json.loads(call("POST", "/api/scenarios", {"title": title, "preferredStructure": "ELEVATED", "points": pts}, tok)[1])["id"]


def burst(jobs):
    """작업(callable)들을 장벽으로 동시에 출발시킨다 → 결과 리스트."""
    out = [None] * len(jobs)
    bar = threading.Barrier(len(jobs))

    def run(i):
        bar.wait()
        out[i] = jobs[i]()

    ts = [threading.Thread(target=run, args=(i,)) for i in range(len(jobs))]
    [t.start() for t in ts]
    [t.join() for t in ts]
    return out


def probe_while(fn):
    """fn 이 도는 동안 가벼운 요청(목록·providers)의 지연을 계속 잰다 → (fn 결과, [(목록 초, providers 초, 비정상 여부)])."""
    lat, stop = [], threading.Event()

    def loop():
        while not stop.is_set():
            a = call("GET", "/api/scenarios", None, TOKEN, timeout=120)
            b = call("GET", "/api/auth/providers", timeout=120)
            lat.append((a[2], b[2], a[0] != 200 or b[0] != 200))
            time.sleep(0.25)

    t = threading.Thread(target=loop)
    t.start()
    time.sleep(0.5)
    try:
        return fn(), lat
    finally:
        stop.set()
        t.join()


def light_summary(lat):
    la = sorted(x[0] for x in lat)
    return f"가벼운 요청 {len(lat)}회 — 목록 중앙 {la[len(la) // 2] * 1000:.0f}ms 최대 {la[-1] * 1000:.0f}ms · 200 아님 {sum(1 for x in lat if x[2])}회"


def main():
    global TOKEN
    ap = argparse.ArgumentParser()
    ap.add_argument("--worst", type=int, default=0, help="최악 입력 N 개를 동시에 계산")
    ap.add_argument("--pool", type=int, default=0, help="DB 연결 풀 크기(0 이면 기본)")
    ap.add_argument("--debug-tx", action="store_true", help="스프링 트랜잭션 DEBUG 로그")
    args = ap.parse_args()
    over = {}
    if args.pool:
        over["SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE"] = str(args.pool)
    if args.debug_tx:
        over["LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_ORM_JPA"] = "DEBUG"
    bad = False
    with Server("conc", over) as srv:
        TOKEN = tok = srv.signup("동시")
        if args.worst:
            ids = [mk(tok, "worst", WORST) for _ in range(args.worst)]
            base = sorted(call("GET", "/api/scenarios", None, tok)[2] for _ in range(5))[2]
            out, lat = probe_while(lambda: burst([lambda i=i: call("POST", f"/api/scenarios/{i}/calculate", None, tok, timeout=900) for i in ids]))
            cc = collections.Counter(o[0] for o in out)
            secs = sorted(o[2] for o in out)
            print(f"기준선 목록 {base * 1000:.0f}ms | 최악 계산 {args.worst}개 동시 → 상태 {dict(cc)} · 개별 중앙 {secs[len(secs) // 2]:.1f}s 최대 {secs[-1]:.1f}s")
            print(light_summary(lat))
            bad |= any(o[0] >= 500 or o[0] == -1 for o in out)
            for i in ids:
                call("DELETE", f"/api/scenarios/{i}", None, tok)
        else:
            # ① 같은 시나리오 동시 계산
            sid = mk(tok, "conc-same", SEOUL_JAMSIL)
            res = burst([lambda: call("POST", f"/api/scenarios/{sid}/calculate", None, tok) for _ in range(8)])
            cnt = collections.Counter(r[0] for r in res)
            print(f"① 같은 시나리오 동시 계산 8개 → 상태 {dict(cnt)}   (429 는 사용자당 한도 2 — 정상. 500 이 있으면 결함)")
            d = json.loads(call("GET", f"/api/scenarios/{sid}", None, tok)[1])
            keys = [(r["modeType"], r["structureType"]) for r in d["results"]]
            print(f"   최종 결과 행 {len(keys)}개, 중복 {len(keys) - len(set(keys))}개 | 이어서 순차 계산 → {call('POST', f'/api/scenarios/{sid}/calculate', None, tok)[0]}")
            bad |= any(r[0] >= 500 or r[0] == -1 for r in res) or len(keys) != len(set(keys))
            # ② 긴 계산 여럿 동안 가벼운 요청 지연
            busan = [mk(tok, f"conc-busan-{i}", SEOUL_BUSAN) for i in range(12)]
            out, lat = probe_while(lambda: burst([lambda i=i: call("POST", f"/api/scenarios/{i}/calculate", None, tok, timeout=600) for i in busan]))
            cc = collections.Counter(o[0] for o in out)
            print(f"② 서울~부산 계산 12개 동시 → 상태 {dict(cc)} · {light_summary(lat)}")
            bad |= any(o[0] >= 500 or o[0] == -1 for o in out)
            # ③ 계산 · 수정(PUT) · 삭제 혼합
            mix = {}
            pts_a = SEOUL_JAMSIL
            pts_b = [P(37.5551, 126.9368), P(37.5563, 126.9236), P(37.56, 126.91)]
            for rnd in range(3):
                s3 = mk(tok, f"conc-mix-{rnd}", SEOUL_JAMSIL)
                jobs, kinds = [], []
                for k in range(4):
                    jobs.append(lambda s=s3: call("POST", f"/api/scenarios/{s}/calculate", None, tok))
                    kinds.append("calc")
                    jobs.append(lambda s=s3, k=k: call("PUT", f"/api/scenarios/{s}", {"title": f"mix{k}", "preferredStructure": "ELEVATED", "points": pts_b if k % 2 else pts_a}, tok))
                    kinds.append("put")
                jobs.append(lambda s=s3: (time.sleep(0.05), call("DELETE", f"/api/scenarios/{s}", None, tok))[1])
                kinds.append("delete")
                for kind, o in zip(kinds, burst(jobs)):
                    mix.setdefault(kind, collections.Counter())[o[0]] += 1
                    bad |= o[0] >= 500 or o[0] == -1
                call("DELETE", f"/api/scenarios/{s3}", None, tok)
            print("③ 계산·수정·삭제 혼합(3판) → " + " · ".join(f"{k} {dict(v)}" for k, v in mix.items()) + "   (404 는 삭제가 먼저 들어간 정상 응답)")
            for i in busan + [sid]:
                call("DELETE", f"/api/scenarios/{i}", None, tok)
        errors = srv.error_log_lines()
        print(f"서버 ERROR 로그 {errors}줄  →  {'결함 있음' if bad or errors else '통과'}")
        bad |= errors > 0
    return 1 if bad else 0


TOKEN = None

if __name__ == "__main__":
    sys.exit(main())
