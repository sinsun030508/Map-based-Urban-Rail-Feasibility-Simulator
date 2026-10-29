"""
비밀값이 저장소에 올라가지 않는다 — 사람의 주의에 맡기지 않는다.

이미 두 번 올라갔다
  ① 서울 열린데이터광장 키를 `.env.example` 에 적어 올렸다(`dbf8c6a`, 지운 뒤에도 git 기록에 남는다)
  ② DB 비밀번호가 프로젝트 이름과 같았고 `.env.example`·`docker-compose.yml`·`application.yml`
     에 기본값으로 적혀 있었다 — 비밀번호가 공개돼 있던 셈이다
**값은 출력하지 않는다** — 실패 메시지에는 키 이름과 파일만 적는다.

    cd etl && python -m pytest test_no_secrets.py
"""

import re
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
SECRET_NAME = re.compile(r'(PASSWORD|SECRET|KEY|TOKEN)')
EXAMPLES = [ROOT / '.env.example', ROOT / 'frontend' / '.env.example']
ENVS = [ROOT / '.env', ROOT / 'frontend' / '.env']


def env_pairs(path):
    out = []
    for line in path.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        k, v = line.split('=', 1)
        out.append((k.strip(), v.strip().strip('"').strip("'")))
    return out


@pytest.mark.parametrize('path', EXAMPLES, ids=lambda p: p.relative_to(ROOT).as_posix())
def test_예시_파일의_비밀_칸은_비어_있다(path):
    filled = [k for k, v in env_pairs(path) if SECRET_NAME.search(k) and v]
    assert not filled, f'{path.name} 에 값이 적힌 비밀 칸: {filled} — 값은 .env 에만'


@pytest.mark.parametrize('path', ['docker-compose.yml', 'backend/src/main/resources/application.yml'])
def test_비밀값에_기본값이_없다(path):
    # ${X:기본값} · ${X:-기본값} 은 안 된다. 값이 없으면 멈추는 ${X:?안내} 와 ${X} 만 허용
    text = (ROOT / path).read_text(encoding='utf-8')
    bad = re.findall(r'\$\{((?:DB_PASSWORD|JWT_SECRET))(?::-?)(?!\?)[^}]+\}', text)
    assert not bad, f'{path} 에 기본값이 있는 비밀값: {bad}'


def tracked_text_files():
    names = subprocess.run(['git', 'ls-files', '-z'], cwd=ROOT, capture_output=True,
                           check=True).stdout.decode('utf-8').split('\0')
    for n in names:
        p = ROOT / n
        if not n or not p.is_file() or p.stat().st_size > 5_000_000:
            continue
        try:
            yield n, p.read_text(encoding='utf-8')
        except (UnicodeDecodeError, OSError):
            continue


def test_이_PC_의_비밀값이_저장소_파일에_없다():
    secrets = [(path.name, k, v) for path in ENVS if path.exists()
               for k, v in env_pairs(path) if SECRET_NAME.search(k) and len(v) >= 8]
    if not secrets:
        pytest.skip('.env 가 없다 — 이 검사는 키를 가진 PC 에서만 뜻이 있다')
    found = {}
    for name, text in tracked_text_files():
        for env, k, v in secrets:
            if v in text:
                found.setdefault(f'{env}:{k}', []).append(name)
    assert not found, '저장소 파일에 들어간 비밀값(값은 숨김) — ' + ' / '.join(
        f'{key} 파일 {len(files)}개: {", ".join(files[:3])}{" …" if len(files) > 3 else ""}'
        for key, files in sorted(found.items()))
