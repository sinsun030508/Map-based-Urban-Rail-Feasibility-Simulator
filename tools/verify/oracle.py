"""회귀 정답지 — 노선 여러 개의 **저장된 응답 JSON 전체**(역 이름·좌표·이용객·추천 여부 포함)를 만들어 두고, 코드를 바꾼 뒤 같은 것을 만들어 **바이트 단위로 대조**한다.

**영속성·계산 계층을 바꾸기 전에 반드시 먼저 만든다** — 11-22 정차역 저장을 JDBC 배치로 바꿀 때 이걸로 노선 24개(5.7MB)가 최초 계산·재계산 모두 바이트 동일함을 보였다(노트 「정차역은 JPA 가 아니라 JDBC 배치로」).
단위 테스트(목)로는 DB 에 실제로 무엇이 저장되는지(소수 자릿수·정렬·cascade)를 못 본다.

사용 (`backend/target/railfeas-0.1.0.jar` 가 **그 시점 코드**여야 한다 — jar 시각을 볼 것)
  1) 바꾸기 전 jar:   python tools/verify/oracle.py before.json              (저장 위치: tools/verify/out/)
                      python tools/verify/oracle.py before_recalc.json --recalc   (같은 시나리오를 두 번 계산해 둘째를 저장 — 옛 결과 삭제 후 재삽입 경로)
  2) 코드를 바꾸고 jar 를 다시 빌드
  3) 바꾼 뒤 jar:     python tools/verify/oracle.py after.json   /   ... after_recalc.json --recalc
  4) 대조:            python tools/verify/oracle.py --compare before.json after.json    (같으면 종료 코드 0)

저장하는 것은 **계산 응답(`calc`)과, 저장된 것을 새로 읽은 조회 응답(`get`)** 둘이다 — 저장이 제대로 됐는지가 핵심이라 후자가 중요하다. 비교에서 뺀 키: id·시각·제목
(`StationResponse`·결과 응답이 id 를 내보내지 않아 id 를 빼도 틈이 아니다). **일부러 값이 바뀌는 변경**(규칙·기준값)에는 쓰지 않는다 — 그때는 sweep.py 의 추천 목록 diff 가 맞다.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from harness import OUT_DIR, P, Server, call, zigzag  # noqa: E402

STRIP = {"id", "scenarioId", "createdAt", "updatedAt", "calculatedAt", "title"}

CASES = [
    ("강남~잠실", [P(37.4979, 127.0276), P(37.5133, 127.1001)], "ELEVATED"),
    ("시청~잠실", [P(37.5663, 126.9779), P(37.5133, 127.1001)], "ELEVATED"),
    ("시청~잠실 지하", [P(37.5663, 126.9779), P(37.5133, 127.1001)], "UNDERGROUND"),
    ("신촌~홍대", [P(37.5551, 126.9368), P(37.5563, 126.9236)], "ELEVATED"),
    ("수원~성남", [P(37.2636, 127.0286), P(37.42, 127.1267)], "ELEVATED"),
    ("송도~부평", [P(37.38, 126.64), P(37.4896, 126.724)], "ELEVATED"),
    ("송도~영종", [P(37.38, 126.64), P(37.493, 126.493)], "ELEVATED"),
    ("대전~유성", [P(36.3504, 127.3845), P(36.3622, 127.356)], "ELEVATED"),
    ("부산 서면~해운대", [P(35.158, 129.06), P(35.1631, 129.1639)], "ELEVATED"),
    ("광주~나주", [P(35.1595, 126.8526), P(35.016, 126.7108)], "ELEVATED"),
    ("울산~경주", [P(35.5384, 129.3114), P(35.8562, 129.2247)], "ELEVATED"),
    ("춘천~홍천", [P(37.8813, 127.7298), P(37.697, 127.889)], "ELEVATED"),
    ("서울~대전", [P(37.5547, 126.9706), P(36.3322, 127.4346)], "ELEVATED"),
    ("서울~부산", [P(37.5547, 126.9706), P(35.1151, 129.0422)], "ELEVATED"),
    ("서울~부산 지하", [P(37.5547, 126.9706), P(35.1151, 129.0422)], "UNDERGROUND"),
    ("서울~강릉", [P(37.5547, 126.9706), P(37.7519, 128.8761)], "ELEVATED"),
    ("목포~제주", [P(34.8118, 126.3922), P(33.4996, 126.5312)], "ELEVATED"),
    ("제주~서귀포", [P(33.4996, 126.5312), P(33.2541, 126.56)], "ELEVATED"),
    ("한강 횡단", [P(37.55, 126.87), P(37.57, 126.89)], "ELEVATED"),
    ("점 3개 시청 경유 잠실", [P(37.5663, 126.9779), P(37.54, 127.03), P(37.5133, 127.1001)], "ELEVATED"),
    ("점 5개 강남 순환", [P(37.4979, 127.0276), P(37.51, 127.06), P(37.49, 127.09), P(37.47, 127.05), P(37.4979, 127.0276)], "ELEVATED"),
    ("울릉도", [P(37.48, 130.9), P(37.52, 130.87)], "ELEVATED"),
    ("새만금(인구 없음)", [P(35.8, 126.6), P(35.75, 126.45)], "ELEVATED"),
    ("서울 지그재그 981km (최악)", zigzag(50, 37.45, 37.63, 126.80, 127.20), "ELEVATED"),
]


def clean(o):
    if isinstance(o, dict):
        return {k: clean(v) for k, v in sorted(o.items()) if k not in STRIP}
    if isinstance(o, list):
        return [clean(v) for v in o]
    return o


def build(out_name, recalc):
    result = {}
    with Server("oracle") as srv:
        tok = srv.signup("정답지")
        print(f"{'노선':28} {'계산':>4} {'초':>6}  역 행 합계")
        total = 0.0
        for name, pts, structure in CASES:
            st, txt, _ = call("POST", "/api/scenarios", {"title": "oracle", "preferredStructure": structure, "points": pts}, tok)
            sid = json.loads(txt)["id"]
            cst, ctxt, sec = call("POST", f"/api/scenarios/{sid}/calculate", None, tok, timeout=600)
            if recalc and cst == 200:
                cst, ctxt, sec = call("POST", f"/api/scenarios/{sid}/calculate", None, tok, timeout=600)
            gst, gtxt, _ = call("GET", f"/api/scenarios/{sid}", None, tok)
            d = json.loads(gtxt)
            result[name] = {"get": clean(d), "calc": clean(json.loads(ctxt)) if cst == 200 else {"error": ctxt[:80]}}
            n = sum(len(r.get("stations", [])) for r in d.get("results", []))
            total += sec
            print(f"{name:28} {cst:>4} {sec:6.1f}  {n}")
            call("DELETE", f"/api/scenarios/{sid}", None, tok)
        print(f"계산 시간 합 {total:.1f}s  | 서버 ERROR 로그 {srv.error_log_lines()}줄")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    path = OUT_DIR / out_name
    path.write_text(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=0), encoding="utf-8")
    print(f"저장: {path} ({path.stat().st_size:,}바이트)")


def compare(a, b):
    pa, pb = OUT_DIR / a, OUT_DIR / b
    if pa.read_bytes() == pb.read_bytes():
        print(f"== 정답지 동일 == ({pa.stat().st_size:,}바이트)")
        return 0
    da, db = json.loads(pa.read_text(encoding="utf-8")), json.loads(pb.read_text(encoding="utf-8"))
    diff = [k for k in sorted(set(da) | set(db)) if da.get(k) != db.get(k)]
    print(f"== 다르다 == 노선 {len(diff)}개: {diff}")
    for k in diff[:3]:
        for part in ("calc", "get"):
            if da.get(k, {}).get(part) != db.get(k, {}).get(part):
                print(f"   {k} · {part} 가 다르다")
    return 1


if __name__ == "__main__":
    if len(sys.argv) >= 4 and sys.argv[1] == "--compare":
        sys.exit(compare(sys.argv[2], sys.argv[3]))
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    build(sys.argv[1], "--recalc" in sys.argv)
