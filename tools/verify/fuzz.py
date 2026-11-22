"""API 입력 견고성 훑기 — 잘못되거나 극단적인 입력이 **5xx·연결 끊김·느린 응답**을 만드는지 본다. 약 50가지.

사용: python tools/verify/fuzz.py   (플래그 0건이 정상. 표는 stdout, 서버 로그는 tools/verify/out/fuzz.log)
**무엇을 보나** — 점 0·1·50 초과, 한반도 밖 좌표, null·문자열 좌표, 1,000km 초과, 비정상 제목·구조, 깨진 JSON, 남의 시나리오 조회·계산·삭제(404 이고 실제로 안 지워져야 한다), 토큰 없음·깨진 토큰·일반회원의
관리자 API(401/403), 보조 API(우회·산·구역)의 극단 입력. **스프링 웹·시큐리티·검증 계층을 바꾼 뒤**(Boot 업그레이드) 응답 코드·메시지가 그대로인지 보는 데 쓴다 — 11-22 3.3 → 3.5 에서 동일했다.
**알려진 경미한 점**(고치지 않음): 주소의 id 가 문자열·범위 초과면 400 본문이 스프링 기본 형식이고, 같은 점 두 개(길이 0)도 저장·계산된다(B/C 0·추천 없음).
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from harness import P, Server, call, raw_call  # noqa: E402

SEOUL, JAMSIL = P(37.5663, 126.9779), P(37.5133, 127.1001)


def main():
    flags, rows = [], []
    with Server("fuzz") as srv:
        tok, tok2 = srv.signup("퍼즈"), srv.signup("퍼즈2")

        def record(name, st, cst, sec, msg):
            rows.append((name, st, cst, round(sec, 1), msg))
            for code in (st, cst):
                if code is not None and (code >= 500 or code == -1):
                    flags.append((name, "5xx/연결오류", st, cst))
            if sec > 20:
                flags.append((name, "느림", round(sec, 1)))

        def case(name, body, calc=True):
            st, txt, sec = call("POST", "/api/scenarios", body, tok)
            cst, msg = None, txt[:70].replace("\n", " ")
            if st in (200, 201):
                sid = json.loads(txt)["id"]
                if calc:
                    cst, ctxt, csec = call("POST", f"/api/scenarios/{sid}/calculate", None, tok, timeout=200)
                    sec += csec
                    msg = ctxt[:90].replace("\n", " ") if cst != 200 else ""
                call("DELETE", f"/api/scenarios/{sid}", None, tok)
            record(name, st, cst, sec, msg)

        def ok(pts, **kw):
            return {"title": "fz", "preferredStructure": "ELEVATED", "points": pts, **kw}

        case("점 0개", ok([]))
        case("점 1개", ok([SEOUL]))
        case("같은 점 2개", ok([SEOUL, SEOUL]))
        case("위도 91", ok([P(91, 127), P(37, 127)]))
        case("경도 200", ok([P(37, 200), P(37, 127)]))
        case("위도 -91", ok([P(-91, 127), P(37, 127)]))
        case("위도 null", ok([{"latitude": None, "longitude": 127}, SEOUL]))
        case("위도 문자열", ok([{"latitude": "abc", "longitude": 127}, SEOUL]))
        case("좌표 키 없음", ok([{}, SEOUL]))
        case("한국 밖 도쿄 20km", ok([P(35.68, 139.69), P(35.50, 139.60)]))
        case("적도 (0,0)", ok([P(0, 0), P(0.1, 0.1)]))
        case("남극 근처", ok([P(-89.9, 0), P(-89.5, 10)]))
        case("서울→도쿄 1100km", ok([SEOUL, P(35.68, 139.69)]))
        case("서울→부산→서울 왕복 650km", ok([SEOUL, P(35.1151, 129.0422), SEOUL]))
        case("점 200개 지그재그", ok([P(37.5 + (i % 2) * 0.05, 126.9 + i * 0.001) for i in range(200)]))
        case("점 2000개", ok([P(37.5 + (i % 2) * 0.001, 126.5 + i * 0.0005) for i in range(2000)]))
        case("제목 빈 문자열", {"title": "", "preferredStructure": "ELEVATED", "points": [SEOUL, JAMSIL]})
        case("제목 null", {"title": None, "preferredStructure": "ELEVATED", "points": [SEOUL, JAMSIL]})
        case("제목 1000자", {"title": "가" * 1000, "preferredStructure": "ELEVATED", "points": [SEOUL, JAMSIL]})
        case("제목 이모지·SQL", {"title": "😀'; DROP TABLE scenario;--<script>", "preferredStructure": "ELEVATED", "points": [SEOUL, JAMSIL]})
        case("구조 FOO", {"title": "fz", "preferredStructure": "FOO", "points": [SEOUL, JAMSIL]})
        case("구조 null", {"title": "fz", "preferredStructure": None, "points": [SEOUL, JAMSIL]})
        case("points 키 없음", {"title": "fz", "preferredStructure": "ELEVATED"})
        case("points 가 객체", {"title": "fz", "preferredStructure": "ELEVATED", "points": {"a": 1}})
        case("아주 가까운 두 점 1m", ok([P(37.5663, 126.9779), P(37.56631, 126.97791)]))
        case("정밀도 높은 좌표", ok([P(37.56630000000000001, 126.97790000000000001), JAMSIL]))
        for name, raw in [("본문이 JSON 아님", b"not json"), ("본문 없음", b"")]:
            st, txt = raw_call("POST", "/api/scenarios", raw, tok)
            record(name, st, None, 0, txt[:70])
        st, txt = raw_call("POST", "/api/scenarios", json.dumps({"title": "x" * 10, "points": [SEOUL, JAMSIL], "extra": {"deep": [1] * 1000}}).encode(), tok)
        record("모르는 필드 추가", st, None, 0, txt[:50])
        if 200 <= st < 300:
            call("DELETE", f"/api/scenarios/{json.loads(txt)['id']}", None, tok)

        # 권한·존재
        sid = json.loads(call("POST", "/api/scenarios", ok([SEOUL, JAMSIL]), tok)[1])["id"]
        for name, method, path, t in [
            ("남의 시나리오 조회", "GET", f"/api/scenarios/{sid}", tok2),
            ("남의 시나리오 계산", "POST", f"/api/scenarios/{sid}/calculate", tok2),
            ("남의 시나리오 삭제", "DELETE", f"/api/scenarios/{sid}", tok2),
            ("없는 id 조회", "GET", "/api/scenarios/999999999", tok),
            ("없는 id 계산", "POST", "/api/scenarios/999999999/calculate", tok),
            ("id 가 문자열", "GET", "/api/scenarios/abc", tok),
            ("id 음수", "GET", "/api/scenarios/-1", tok),
            ("id 범위 초과", "GET", "/api/scenarios/99999999999999999999", tok),
            ("토큰 없이 목록", "GET", "/api/scenarios", None),
            ("깨진 토큰 목록", "GET", "/api/scenarios", "abc.def.ghi"),
            ("일반회원이 관리자 API", "GET", "/api/admin/users", tok),
            ("토큰 없이 관리자 API", "GET", "/api/admin/users", None),
            ("route/detour 점 없음", "POST", "/api/route/detour", tok),
            ("route/mountain 점 없음", "POST", "/api/route/mountain", tok),
            ("route/zones 점 없음", "POST", "/api/route/zones", tok),
        ]:
            st, txt, sec = call(method, path, {} if method == "POST" else None, t)
            record(name, st, None, sec, txt[:60].replace("\n", " "))
        st, _, _ = call("GET", f"/api/scenarios/{sid}", None, tok)
        rows.append(("(확인) 주인은 여전히 조회", st, None, 0, ""))
        if st != 200:
            flags.append(("남의 삭제가 먹혔다", "권한", st))
        for name, path, body in [
            ("detour 점 2000개", "/api/route/detour", {"points": [P(37.5 + (i % 2) * 0.001, 126.5 + i * 0.0005) for i in range(2000)], "structure": "ELEVATED"}),
            ("mountain 서울→도쿄", "/api/route/mountain", {"points": [SEOUL, P(35.68, 139.69)], "structure": "ELEVATED"}),
            ("zones 점 1개", "/api/route/zones", {"points": [SEOUL]}),
            ("detour 위도 999", "/api/route/detour", {"points": [P(999, 999), P(998, 998)], "structure": "ELEVATED"}),
            ("mountain 구조 FOO", "/api/route/mountain", {"points": [SEOUL, JAMSIL], "structure": "FOO"}),
        ]:
            st, txt, sec = call("POST", path, body, tok, timeout=90)
            record(name, st, None, sec, txt[:60].replace("\n", " "))
        call("DELETE", f"/api/scenarios/{sid}", None, tok)

        print(f"{'케이스':26} {'생성':>5} {'계산':>5} {'초':>6}  메시지")
        for r in rows:
            print(f"{r[0]:26} {str(r[1]):>5} {str(r[2]) if r[2] is not None else '-':>5} {r[3]:>6}  {r[4]}")
        # 이 도구가 일부러 만든 400 변환 로그는 ERROR 가 아니다 — 그 밖의 ERROR 만 센다
        extra = srv.error_log_lines(ignore=("MethodArgumentTypeMismatch", "HttpMessageNotReadable"))
        print(f"\n=== 플래그 ===\n{chr(10).join('  ' + str(f) for f in flags)}\n({len(flags)}건)  기대 외 서버 ERROR 로그 {extra}줄")
    return 1 if flags else 0


if __name__ == "__main__":
    sys.exit(main())
