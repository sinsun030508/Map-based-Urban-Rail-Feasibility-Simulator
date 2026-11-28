# 배포 (AWS EC2 + Docker Compose)

전체를 컨테이너로 띄운다. 바깥에 여는 것은 `web`(nginx) 하나이고 백엔드·DB·Redis 는 compose 내부망으로만 만난다.

```
브라우저 → web(nginx :80) ─ 화면
                          ├ /api /oauth2 /login/oauth2 → backend:9090 → db · redis
                          └ /vworld → api.vworld.kr (키·Referer 를 여기서 붙인다)
```

**이 절차 중 이 PC 에서 시험한 것** — 이미지 빌드, 포트 8088 로 전체 기동, 화면·지도 타일·API·산 판정·계산, 소셜 로그인
시작 주소(11-06).

**EC2 에 실제로 올렸다(11-26, `13f2a34`)** — 2GB 메모리 + 스왑 2G, Ubuntu, VWorld·소셜 키 없이. 확인한 것: 밖에서 화면·API·보안 헤더,
열린 포트는 22·80 뿐(443·3306·3307·6379·9090 은 닿지 않음), 지형 타일 1,312장, 가입하고 노선 계산까지. 켠 직후 메모리 1.5/1.9GB 사용
(스왑 6MB). **서버에서 시험하지 않은 것** — HTTPS, VWorld 지도, 소셜 로그인, 부하.

## 1. 서버 준비

- 메모리 — **프리티어 1GB 는 빠듯하다.** 이 PC 측정으로 백엔드 419MB(`-Xmx384m`) + DB 383MB + Redis 5MB + nginx 8MB ≈ **815MB** 이고,
  여기에 운영체제가 더 든다. 2GB 인스턴스를 쓰거나, 1GB 면 스왑을 먼저 만든다
  ```bash
  sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
  ```
- 보안 그룹 — 22 와 80 만 연다. **3306·6379·9090 은 열지 않는다.** 80 의 유형은 **HTTP** 다(HTTPS 가 아니다 — 함정 참고). 22 의 소스는
  터미널로 SSH 를 쓰면 「내 IP」, **브라우저 터미널(EC2 Instance Connect)** 을 쓰면 AWS 관리형 접두사 목록
  `com.amazonaws.<리전>.ec2-instance-connect` 다(접속이 내 PC 가 아니라 AWS 대역에서 온다). `0.0.0.0/0` 으로 두지 않는다
- Docker 와 Compose 플러그인을 설치한다
  ```bash
  sudo apt-get update -y && sudo apt-get install -y docker.io docker-compose-v2 git
  sudo systemctl enable --now docker && sudo usermod -aG docker $USER   # 터미널을 다시 접속해야 적용된다
  ```

## 2. 코드와 자료

```bash
git clone https://github.com/sinsun030508/Map-based-Urban-Rail-Feasibility-Simulator.git railfeas
cd railfeas
python3 etl/dem_tiles.py     # 지형 타일 36MB. 안 받으면 산 판정만 꺼진다
```

`data/build` 의 CSV 와 큰길 도로망(`road_graph.csv.gz`, 8.2MB)은 저장소에 있다. 해안선(`coastline.csv.gz`, 976KB — 바다 위 새 도로의 다리값)도 저장소에 있어 `./data` 마운트로 같은 폴더에서 읽고(`COAST_FILE`), 없으면 그 기능만 꺼진다(다시 만들려면 `python etl/osm_coast.py`). `data/cache` 는 없다. 도로망 파일이 없으면 정차역을 큰길로 옮기는 일과 트램·BRT·광역급행의 도로 따라가기가 꺼진다(그린 직선으로 센다).

## 3. `.env`

`.env.example` 을 복사해 채운다. **이 파일은 서버에만 둔다.** 배포에 꼭 필요한 값은 아래이고, 없으면 compose 가 멈춘다.

| 키 | 내용 |
|---|---|
| `DB_PASSWORD` | 무작위로 만든다 |
| `JWT_SECRET` | 32바이트 이상 — `python3 -c "import secrets; print(secrets.token_hex(32))"` |
| `PUBLIC_URL` | 공개 주소, 끝에 `/` 없이 — `http://서버주소` |

지도와 소셜 로그인을 쓰려면 다음도 채운다.

| 키 | 내용 |
|---|---|
| `VWORLD_KEY` · `VWORLD_REFERER` | 키와, **그 키에 등록한 도메인**(`http://서버주소/`). 비우면 OpenFreeMap 으로 떨어진다 |
| `GOOGLE_CLIENT_ID` · `GOOGLE_CLIENT_SECRET` | 콘솔의 리디렉션 URI 에 `PUBLIC_URL/login/oauth2/code/google` 추가 |
| `KAKAO_CLIENT_ID` · `KAKAO_CLIENT_SECRET` | 콘솔의 Redirect URI 에 `PUBLIC_URL/login/oauth2/code/kakao` 추가 |
| `TMAP_APP_KEY` · `TMAP_DAILY_LIMIT` | 「지금 있는 대중교통과 비교」 — SK open API 의 TMAP 대중교통 앱 키. 비우면 버튼이 없다. 상한 기본 10(무료 한도) |

## 4. 띄우기

```bash
docker compose -f docker-compose.yml -f docker-compose.deploy.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.deploy.yml ps
```

첫 기동은 DB 가 시드를 넣느라 1~2분 걸린다. 백엔드는 DB 가 준비될 때까지 기다린다.

**2GB 서버에서는 하나씩 빌드한다** — 터미널이 끊겨도 계속 돌고 기록은 `deploy.log` 에 남는다(11-26 에 이 명령으로 올렸다).

```bash
nohup sh -c 'F="-f docker-compose.yml -f docker-compose.deploy.yml"; docker compose $F build backend && docker compose $F build web && docker compose $F up -d' > deploy.log 2>&1 &
```

```bash
tail -n 12 deploy.log
```

**`git pull && nohup … &` 로 한 줄에 잇지 않는다** — 끝의 `&` 는 `&&` 로 이은 전체를 백그라운드로 보내, 중간의 `git log` 가 페이저를 열다
`Stopped (tty output)` 로 멈추고 빌드가 시작되지 않는다. 코드를 받는 일은 따로 한다.

**관리자 만들기** — 가입한 뒤 DB 에서 올린다(화면에는 첫 관리자를 만드는 길이 없다).

```bash
docker exec -it railfeas-db sh -c 'mysql -uroot -p railfeas -e "UPDATE \`user\` SET role=\"ADMIN\" WHERE email=\"가입한 이메일\""'
```

## 5. 지키는 것

- **API 문서는 닫혀 있다**(`SWAGGER_ENABLED: "false"`, 고정). `etl/test_no_secrets.py` 가 본다
- **진짜 VWorld 키는 이미지에 안 들어간다** — 빌드에는 `on` 표시만 넘기고 키는 nginx 환경변수로 들어간다. Vite 는 `VITE_` 값을 코드에 치환하므로
  진짜 키를 빌드에 넘기면 공개되는 JS 에 박힌다
- **`docker compose down -v` 를 쓰지 않는다** — 볼륨이 지워지면 가입 계정과 시나리오가 사라진다
- **로그인은 (이메일, 접속 주소)마다 15분에 10번까지다** — 앞단에 프록시·로드밸런서를 하나 더 두면 접속 주소가 그 장비로 보이니
  `nginx.conf.template` 의 `X-Forwarded-For` 를 함께 고친다
- **가입은 접속 주소마다 한 시간에 10번까지다**(`SIGNUP_MAX_PER_HOUR` 로 바꾼다) — 중복 이메일(409)도 센다
- **nginx 가 보안 헤더와 속도 제한을 건다**(`frontend/nginx.conf.template`, 11-26) — `X-Frame-Options`·CSP·`nosniff` 와, 접속 주소마다
  API 초당 10(burst 50)·로그인/가입 분당 10(burst 10)·VWorld 타일 초당 30(burst 200). 막히면 `{"message": ...}` 429 다.
  **지도에 새 외부 출처(타일·글꼴)를 붙이면 CSP 의 `img-src`·`connect-src` 에 넣는다** — 안 넣으면 지도가 비고 콘솔에만 위반이 뜬다.
  이 PC 에서 배포 이미지로 띄워 로그인·지도(OpenFreeMap)·저장·계산·인구 3D 까지 CSP 위반 0건을 확인했다
- **HTTPS 는 아직 없다** — 80 만 연다. 로그인 토큰이 평문으로 오가므로 공개 운영 전에 도메인과 인증서(예: certbot)를 붙여야 한다.
  그때 `PUBLIC_URL` 과 소셜 콘솔의 주소도 `https` 로 바꾸고, nginx 에 HSTS(`Strict-Transport-Security`)를 더한다

## 함정

- **서버 안에서는 200 인데 밖에서 안 열리면 보안 그룹이다** — 80 이 시간 초과이고 443 이 「연결 거부」 면 HTTP 대신 HTTPS 를 허용한 것이다(11-26)
- **퍼블릭 IP 는 인스턴스를 중지했다 켜면 바뀐다** — `PUBLIC_URL` 과 소셜 콘솔 주소가 어긋난다. 고정하려면 탄력적 IP 를 붙인다
- **계정에 따라 리전이 하나로 묶여 있다** — 다른 리전에서 「리전 사용 불가」 가 뜨면 콘솔의 「고급 기능」 을 켜야 한다. 배정된 리전에 만들어도 된다
- **`.env` 값 끝의 공백** — compose 는 잘라 주지만 셸에서 `grep | cut` 으로 꺼내 넘기면 그대로 남는다. `frontend/.env` 의 키 끝 공백이
  nginx `rewrite` 의 인자를 늘려 `invalid number of arguments` 로 web 이 재시작을 반복했다(CRLF 도 같다)
- **VWorld 키 없이 띄우면 web 이 안 켜졌다**(11-26 에 고침) — `VWORLD_REFERER` 가 비면 `proxy_set_header Referer ;` 가 되어
  `invalid number of arguments` 였다. 지금은 따옴표로 감싸 빈 값도 된다
- **`/v3/api-docs` 가 200 이어도 문서가 아니다** — nginx 가 모르는 주소를 화면(`index.html`)으로 넘긴 것이다. 백엔드 쪽은 401 이다
- **compose 를 겹쳐 쓰면 `db` 설정이 바뀌어 컨테이너가 다시 만들어진다** — 자료는 이름 붙은 볼륨에 있어 그대로다
