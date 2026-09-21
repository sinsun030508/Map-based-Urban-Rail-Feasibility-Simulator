# RailFeas — 노선 타당성 분석 시뮬레이터

지도에 노선을 그리면 건설비·예상 이용객·B/C를 산출하고,
수요에 맞는 교통수단(지하철·경전철·트램·BRT)과 정차역을 추천하는 웹 서비스.

## 빠른 시작

```bash
cp .env.example .env
docker compose up -d          # MySQL + Redis, 첫 기동 시 스키마·시드 자동 적재
```

## 데이터 재생성

```bash
pip install -r etl/requirements.txt
python etl/etl.py             # data/raw → data/build/*.csv + db/seed/S2__reference_line.sql
docker compose down -v && docker compose up -d   # 볼륨 초기화 후 재적재
```

## 문서
- `CLAUDE.md` — 설계 결정, 데이터 규칙, 기준값 (개발 시 필독)
- `docs/` — 제안서, ERD
