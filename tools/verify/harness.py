"""실제 스택 검증 도구의 공통 하니스 — 백엔드 jar 를 띄우고, 시험 계정을 만들고, 끝나면 정리한다.

**왜 있나** — 단위 테스트(목)는 MySQL 교착·낙관적 잠금·연결 풀 고갈 같은 것을 못 본다. 11-22 에 이 도구들로 실제 서버에 동시 요청을 보내 500 세 가지를 찾았다(노트 「같은 시나리오를 바꾸는 쓰기는 줄을 선다」).
**전제** — `docker compose up -d`(DB·Redis), `backend/target/railfeas-0.1.0.jar`(바꾼 뒤엔 `./mvnw package -DskipTests` 를 다시 — jar 시각을 볼 것), 저장소 루트의 `.env`. 9090 포트가 비어 있어야 한다.

**지키는 것**
- 윈도우 `java` 는 껍데기 → 진짜 JVM(자식) 구조라 `Popen.terminate()` 로는 서버가 안 죽는다 — 남은 서버가 같은 포트를 잡으면 뒤 시험이 **앞 서버의 대답을 받는다**(네 번이 전부 같은 값으로 나왔다).
  그래서 시작 전에 포트가 비었는지 보고, 끝날 때 **듣는 PID 를 직접** 끈다.
- 비밀번호·토큰은 출력하지 않는다(무작위로 만들어 쓰고 버린다). 시험 계정(`<접두사>-<시각>@example.test`)은 끝에 DB 에서 지운다.
"""
import json
import os
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BACKEND = ROOT / "backend"
JAR = BACKEND / "target" / "railfeas-0.1.0.jar"
OUT_DIR = Path(os.environ.get("VERIFY_OUT", ROOT / "tools" / "verify" / "out"))   # 로그·결과 JSON — .gitignore 에 있다
PORT = int(os.environ.get("VERIFY_PORT", "9090"))
BASE = f"http://localhost:{PORT}"


def load_env() -> dict:
    env = dict(os.environ)
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k] = v
    return env


def call(method, path, body=None, token=None, timeout=300):
    """(상태코드, 본문 문자열, 걸린 초). 연결 실패는 -1 로 돌려준다 — 서버가 뜨기를 기다리는 루프가 죽지 않게."""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace"), time.time() - t0
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:300], time.time() - t0
    except Exception as e:  # noqa: BLE001 — 연결 거부·시간 초과도 값으로
        return -1, repr(e)[:150], time.time() - t0


def raw_call(method, path, raw: bytes | None, token=None, timeout=60):
    """본문을 그대로 보낸다 — 깨진 JSON·빈 본문 시험용."""
    req = urllib.request.Request(BASE + path, data=raw, method=method)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:200]
    except Exception as e:  # noqa: BLE001
        return -1, repr(e)[:120]


def listener_pid(port=PORT):
    """그 포트를 듣는 프로세스 id (윈도우). 없으면 None."""
    out = subprocess.run(
        ["powershell", "-NoProfile", "-Command",
         f"(Get-NetTCPConnection -LocalPort {port} -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1).OwningProcess"],
        capture_output=True, text=True).stdout.strip()
    return int(out) if out.isdigit() else None


def delete_test_users(env, prefix):
    """DB 에서 이 도구가 만든 시험 계정을 지운다 — 시나리오·결과·역은 외래키 CASCADE 로 함께 사라진다."""
    pw = env.get("DB_PASSWORD", "")
    subprocess.run(["docker", "exec", "-e", f"MYSQL_PWD={pw}", "railfeas-db", "mysql", "-uroot", "railfeas", "-e",
                    f"DELETE FROM `user` WHERE email LIKE '{prefix}-%@example.test';"], capture_output=True)


class Server:
    """백엔드 jar 를 띄우는 컨텍스트. `with Server("sweep") as s:` — s.signup() 으로 토큰을 받고, 나올 때 서버·계정을 정리한다.

    env_overrides: 환경 변수 덮어쓰기(예: {"ROAD_FILE": "../data/build/__none__"} — 자료 파일을 없는 경로로 가리키면 그 규칙이 꺼진다,
    {"SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE": "2"} — 연결 풀을 줄여 독점을 드러낸다, {"LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_ORM_JPA": "DEBUG"} — 트랜잭션 시작·커밋 시각)
    """

    def __init__(self, prefix, env_overrides=None):
        self.prefix = prefix
        self.env = load_env()
        self.env["SERVER_PORT"] = str(PORT)
        self.env.update(env_overrides or {})
        self.proc = None
        self.log_path = OUT_DIR / f"{prefix}.log"

    def __enter__(self):
        if not JAR.exists():
            raise SystemExit(f"jar 가 없다 — cd backend && ./mvnw package -DskipTests ({JAR})")
        if listener_pid() is not None:
            raise SystemExit(f"포트 {PORT} 를 다른 프로세스가 잡고 있다 — 먼저 끌 것(남은 시험 서버일 수 있다)")
        OUT_DIR.mkdir(parents=True, exist_ok=True)
        self.log = open(self.log_path, "w", encoding="utf-8")
        self.proc = subprocess.Popen(["java", "-jar", str(JAR)], cwd=BACKEND, env=self.env, stdout=self.log, stderr=subprocess.STDOUT)
        for _ in range(120):
            if call("GET", "/api/auth/providers", timeout=3)[0] == 200:
                return self
            time.sleep(1)
        self.__exit__(None, None, None)
        raise SystemExit(f"서버가 안 켜졌다 — {self.log_path} 를 볼 것")

    def __exit__(self, *exc):
        # 껍데기(Popen pid)와 진짜 JVM(듣는 pid)을 다 끈다
        for pid in {self.proc.pid if self.proc else None, listener_pid()}:
            if pid:
                subprocess.run(["taskkill", "/T", "/F", "/PID", str(pid)], capture_output=True)
        delete_test_users(self.env, self.prefix)
        return False

    def signup(self, nickname="검증") -> str:
        """무작위 비밀번호로 시험 계정을 만들고 토큰을 돌려준다(출력하지 않는다)."""
        email = f"{self.prefix}-{int(time.time() * 1000)}@example.test"
        st, txt, _ = call("POST", "/api/auth/signup", {"email": email, "password": secrets.token_urlsafe(18), "nickname": nickname})
        if st not in (200, 201):
            raise SystemExit(f"가입 실패 {st} {txt[:100]}")
        return json.loads(txt)["accessToken"]

    def error_log_lines(self, ignore=()):
        """서버 로그의 ERROR 줄 수(무시할 부분 문자열 제외)."""
        n = 0
        for line in self.log_path.read_text(encoding="utf-8", errors="replace").splitlines():
            if " ERROR " in line and not any(x in line for x in ignore):
                n += 1
        return n


def P(lat, lng):
    return {"latitude": lat, "longitude": lng}


def zigzag(n, lat0, lat1, lng0, lng1):
    """남북으로 왕복하며 동서로 이동하는 점 n 개 — 길이 상한 근처의 최악 입력을 만든다(서울 구간당 ≈20km, 50점 → ≈980km)."""
    return [P(round(lat0 if i % 2 == 0 else lat1, 6), round(lng0 + (lng1 - lng0) * i / (n - 1), 6)) for i in range(n)]


def create_and_calculate(token, title, points, structure="ELEVATED", timeout=600):
    """(생성 상태, 시나리오 id, 계산 상태, 계산 초). 생성이 실패하면 id 는 None."""
    st, txt, _ = call("POST", "/api/scenarios", {"title": title, "preferredStructure": structure, "points": points}, token)
    if st not in (200, 201):
        return st, None, None, 0.0
    sid = json.loads(txt)["id"]
    cst, _, sec = call("POST", f"/api/scenarios/{sid}/calculate", None, token, timeout=timeout)
    return st, sid, cst, sec


if __name__ == "__main__":
    print(__doc__)
    sys.exit(0)
