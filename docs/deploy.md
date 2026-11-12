# 배포 (AWS EC2 + Docker Compose)

전체를 컨테이너로 띄운다. 바깥에 여는 것은 `web`(nginx) 하나이고 백엔드·DB·Redis 는 compose 내부망으로만 만난다.

```
브라우저 → web(nginx :80) ─ 화면
                          ├ /api /oauth2 /login/oauth2 → backend:9090 → db · redis
                          └ /vworld → api.vworld.kr (키·Referer 를 여기서 붙인다)
```

**이 절차 중 이 PC 에서 시험한 것** — 이미지 빌드, 포트 8088 로 전체 기동, 화면·지도 타일·API·산 판정·계산, 소셜 로그인
시작 주소(11-06). **EC2 에 실제로 올려 본 적은 없다** — AWS 계정이 있어야 해서 아래 EC2 단계는 시험하지 못했다.

## 1. 서버 준비

- 메모리 — **프리티어 1GB 는 빠듯하다.** 이 PC 측정으로 백엔드 419MB(`-Xmx384m`) + DB 383MB + Redis 5MB + nginx 8MB ≈ **815MB** 이고,
  여기에 운영체제가 더 든다. 2GB 인스턴스를 쓰거나, 1GB 면 스왑을 먼저 만든다
  ```bash
  sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
  ```
- 보안 그룹 — 22(내 IP 만)와 80 만 연다. **3306·6379·9090 은 열지 않는다**
- Docker 와 Compose 플러그인을 설치한다

## 2. 코드와 자료

```bash
git clone https://github.com/sinsun030508/Map-based-Urban-Rail-Feasibility-Simulator.git railfeas
cd railfeas
python3 etl/dem_tiles.py     # 지형 타일 36MB. 안 받으면 산 판정만 꺼진다
```

`data/build` 의 CSV 와 큰길 도로망(`road_graph.csv.gz`, 7.4MB)은 저장소에 있다. `data/cache` 는 없다. 도로망 파일이 없으면 정차역을 큰길로 옮기는 일과 트램·BRT·광역급행의 도로 따라가기가 꺼진다(그린 직선으로 센다).

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

## 4. 띄우기

```bash
docker compose -f docker-compose.yml -f docker-compose.deploy.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.deploy.yml ps
```

첫 기동은 DB 가 시드를 넣느라 1~2분 걸린다. 백엔드는 DB 가 준비될 때까지 기다린다.

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
- **HTTPS 는 아직 없다** — 80 만 연다. 로그인 토큰이 평문으로 오가므로 공개 운영 전에 도메인과 인증서(예: certbot)를 붙여야 한다.
  그때 `PUBLIC_URL` 과 소셜 콘솔의 주소도 `https` 로 바꾼다

## 함정

- **`.env` 값 끝의 공백** — compose 는 잘라 주지만 셸에서 `grep | cut` 으로 꺼내 넘기면 그대로 남는다. `frontend/.env` 의 키 끝 공백이
  nginx `rewrite` 의 인자를 늘려 `invalid number of arguments` 로 web 이 재시작을 반복했다(CRLF 도 같다)
- **`/v3/api-docs` 가 200 이어도 문서가 아니다** — nginx 가 모르는 주소를 화면(`index.html`)으로 넘긴 것이다. 백엔드 쪽은 401 이다
- **compose 를 겹쳐 쓰면 `db` 설정이 바뀌어 컨테이너가 다시 만들어진다** — 자료는 이름 붙은 볼륨에 있어 그대로다
