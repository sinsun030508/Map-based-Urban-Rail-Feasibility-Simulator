"""전국 대표 노선 훑기 — 서버 한 번, 노선 여러 개. 오류·역 수·B/C 범위·경고 길이·시간 이상과, **서버 추천을 응답 값으로 독립 재계산해 대조**한다.

사용: python tools/verify/sweep.py   (결과 표는 stdout, 서버 로그는 tools/verify/out/sweep.log)
**언제 돌리나** — 계산·추천 규칙, 기준값, 도로·해안선 자료, 의존성을 바꾼 뒤. 바꾸기 전후의 「서버 추천」 목록을 `diff` 하면 규칙 변경의 영향 범위가 한눈에 나온다(11-22 역간격 규칙: 31개 중 5개).
**추천 규칙 상수**(`MAXLEN`·`SPACING`)는 CLAUDE.md 의 값이다 — 기준값을 바꾸면 여기도 고친다. 어긋나면 「추천 불일치」 로 잡힌다(그게 이 검사의 목적이다).
"""
import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from harness import P, Server, call  # noqa: E402

# (이름, 시작, 끝). 도심 단거리·중거리·장거리·섬·산지·바다·인구 없음·무의미 입력을 섞었다
PAIRS = [
    ("서울 강남~잠실 4km", (37.4979, 127.0276), (37.5133, 127.1001)),
    ("서울시청~잠실", (37.5663, 126.9779), (37.5133, 127.1001)),
    ("서울 신촌~홍대 1.3km", (37.5551, 126.9368), (37.5563, 126.9236)),
    ("서울 짧은 0.6km", (37.5665, 126.9780), (37.5700, 126.9820)),
    ("수원~성남 15km", (37.2636, 127.0286), (37.4200, 127.1267)),
    ("인천 송도~부평 20km", (37.3800, 126.6400), (37.4896, 126.7240)),
    ("송도~영종 섬", (37.3800, 126.6400), (37.4930, 126.4930)),
    ("김포~강화(바다)", (37.6500, 126.6900), (37.7300, 126.4500)),
    ("대전 시청~유성 10km", (36.3504, 127.3845), (36.3622, 127.3560)),
    ("대구 동성로~수성구 8km", (35.8714, 128.6014), (35.8580, 128.6300)),
    ("부산 서면~해운대 14km", (35.1580, 129.0600), (35.1631, 129.1639)),
    ("광주~나주 25km", (35.1595, 126.8526), (35.0160, 126.7108)),
    ("울산~경주 35km", (35.5384, 129.3114), (35.8562, 129.2247)),
    ("춘천~홍천 산지 40km", (37.8813, 127.7298), (37.6970, 127.8890)),
    ("강릉~속초 산·해안 80km", (37.7519, 128.8761), (38.2070, 128.5918)),
    ("서울~대전 140km", (37.5547, 126.9706), (36.3322, 127.4346)),
    ("서울~강릉 170km", (37.5547, 126.9706), (37.7519, 128.8761)),
    ("대구~부산 100km", (35.8714, 128.6014), (35.1151, 129.0422)),
    ("광주~목포 70km", (35.1595, 126.8526), (34.8118, 126.3922)),
    ("목포~제주(바다)", (34.8118, 126.3922), (33.4996, 126.5312)),
    ("제주시~서귀포 산", (33.4996, 126.5312), (33.2541, 126.5600)),
    ("거제~통영 다리", (34.8800, 128.6200), (34.8544, 128.4330)),
    ("여수~순천 30km", (34.7604, 127.6622), (34.9506, 127.4872)),
    ("전주~군산 55km", (35.8242, 127.1480), (35.9676, 126.7368)),
    ("울릉도 내 5km", (37.4800, 130.9000), (37.5200, 130.8700)),
    ("강원 인제 무인 산지 30km", (38.0697, 128.1703), (38.1900, 128.4000)),
    ("새만금 간척지 20km", (35.8000, 126.6000), (35.7500, 126.4500)),
    ("한강 횡단 3km(염창~상암)", (37.5500, 126.8700), (37.5700, 126.8900)),
    ("서울 외곽 북한산", (37.6600, 126.9800), (37.6400, 126.9400)),
    ("점 하나(같은 좌표)", (37.5, 127.0), (37.5, 127.0)),
]
MAX_STATIONS_SANE = 400
# 추천 규칙의 경계(CLAUDE.md): 권장 연장 위로, 역간격 아래로 나가면 기본으로 내세우지 않는다
MAXLEN = {"HEAVY_METRO": 60.2, "LIGHT_RAIL": 29.1, "TRAM": 25.0, "BRT_HIGH": 30.0, "BRT_LOW": 30.0}
SPACING = {"HEAVY_METRO": 1.06, "LIGHT_RAIL": 1.06, "TRAM": 0.86, "BRT_HIGH": 1.16, "BRT_LOW": 1.16, "DOUBLE_ELEC": 2.62, "EXPRESS_RAIL": 6.92}
SHORT = {"HEAVY_METRO": "중", "LIGHT_RAIL": "경", "TRAM": "트", "BRT_HIGH": "BH", "BRT_LOW": "BL", "EXPRESS_RAIL": "급", "DOUBLE_ELEC": "복"}


def hav(a, b):
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    h = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(b[1] - a[1]) / 2) ** 2
    return 2 * 6371.0088 * math.asin(math.sqrt(h))


def expected_recommendation(results, drawn_km):
    """CostCalculator.recommended 를 응답 값으로 다시 구현한다 — 운행 가능·B/C>0 중 권장 연장 이하·역간격 이상, 하나도 없으면 길이를 무시."""
    usable = [r for r in results if r.get("feasible") and r["bcRatio"] is not None and r["bcRatio"] > 0]
    within = [r for r in usable if SPACING.get(r["modeType"], 0) <= drawn_km <= MAXLEN.get(r["modeType"], 1e9)]
    pool = within or usable
    top = max((r["bcRatio"] for r in pool), default=None)
    return {r["modeType"] for r in pool if r["bcRatio"] == top}, bool(pool)


def main():
    anomalies, recs = [], []
    with Server("sweep") as srv:
        tok = srv.signup("훑기")
        print(f"{'노선':26} {'직선km':>7} {'초':>5}  수단별 최고 B/C(역수)")
        for name, a, b in PAIRS:
            straight = hav(a, b)
            st, txt, _ = call("POST", "/api/scenarios", {"title": "sweep", "preferredStructure": "ELEVATED", "points": [P(*a), P(*b)]}, tok)
            if st not in (200, 201):
                anomalies.append((name, f"생성 {st}", txt[:80]))
                print(f"{name:26} {straight:7.1f}  생성 HTTP {st}")
                continue
            sid = json.loads(txt)["id"]
            cst, ctxt, sec = call("POST", f"/api/scenarios/{sid}/calculate", None, tok)
            if cst != 200:
                anomalies.append((name, f"계산 {cst}", ctxt[:120]))
                print(f"{name:26} {straight:7.1f} {sec:5.1f}  계산 HTTP {cst} {ctxt[:60]}")
                call("DELETE", f"/api/scenarios/{sid}", None, tok)
                continue
            d = json.loads(call("GET", f"/api/scenarios/{sid}", None, tok)[1])
            res = d.get("results", [])
            best = {}
            for r in res:
                m = r["modeType"]
                if r["bcRatio"] is not None and (m not in best or r["bcRatio"] > best[m]["bcRatio"]):
                    best[m] = r
                tag = f'{name} {m}/{r["structureType"]}'
                if r["stationCount"] < 2 or r["stationCount"] > MAX_STATIONS_SANE:
                    anomalies.append((tag, "역 수", r["stationCount"]))
                if r["totalCost"] is None or r["totalCost"] <= 0:
                    anomalies.append((tag, "총사업비", r["totalCost"]))
                if r["bcRatio"] is not None and (r["bcRatio"] < 0 or r["bcRatio"] > 60 or math.isnan(r["bcRatio"])):
                    anomalies.append((tag, "B/C", r["bcRatio"]))
                if r.get("warning") and len(r["warning"]) > 200:
                    anomalies.append((tag, "경고 길이", len(r["warning"])))
                if r.get("travelTimeMin") is not None and r["travelTimeMin"] <= 0 and straight > 0.5:
                    anomalies.append((tag, "소요시간", r["travelTimeMin"]))
                if r.get("estimatedRidership") is not None and r["estimatedRidership"] < 0:
                    anomalies.append((tag, "이용객 음수", r["estimatedRidership"]))
            if sec > 15:
                anomalies.append((name, "느림", f"{sec:.1f}s"))
            drawn = float(d["totalLengthKm"])
            expected, any_pool = expected_recommendation(res, drawn)
            got = d.get("recommendedMode")
            if (got is None) != (not any_pool) or (got is not None and got not in expected):
                anomalies.append((name, "추천 불일치", f"서버 {got} / 기대 {sorted(expected)} (그린 {drawn:.1f}km)"))
            recs.append((name, got, drawn))
            print(f"{name:26} {straight:7.1f} {sec:5.1f}  " + " ".join(f'{SHORT.get(m, m[:3])}{r["bcRatio"]:.2f}({r["stationCount"]})' for m, r in best.items()))
            call("DELETE", f"/api/scenarios/{sid}", None, tok)
        print("\n=== 서버 추천 ===")
        for n, g, dr in recs:
            print(f"  {n:26} {dr:6.1f}km  {g}")
        print("\n=== 이상 징후 ===")
        for x in anomalies:
            print(" ", x)
        print(f"({len(anomalies)}건)  서버 ERROR 로그 {srv.error_log_lines()}줄")
    return 1 if anomalies else 0


if __name__ == "__main__":
    sys.exit(main())
