"""
서울 열린데이터광장 시간대별 승하차 수집 — 첨두 1시간 비중 산출.

출력
  data/build/station_peak.csv   역별 첨두 시각·첨두 비중·방향 불균형
  표준출력                      수단 모델에 넣을 집계값

왜 필요한가
  `benefit_parameter.peak_hour_ratio` 가 가정값(0.12)이었다. 수송능력 판정
  (`pphpd_min`/`pphpd_max`)이 이 값에 바로 걸려서, 가정값이면 "과잉 투자"
  경고가 쉽게 붙는다. 투자평가지침에는 첨두율 원단위가 없고 수요분석 결과에서
  얻으라고만 하므로(지침 확인) 실측으로 구한다.

기준 맞추기
  수요 모델(`demand_model.py`)의 종속변수는 **일 승차+하차**(`daily_total`)다.
  첨두율도 같은 기준이어야 곱해서 의미가 있으므로 (승차+하차) 로 계산한다.

주의
  - API 는 **월 합계**만 준다. 비중은 척도에 무관하므로 월 합계로 계산해도
    일평균으로 계산한 값과 같다
  - **주말이 섞여 있다.** 평일만 보면 첨두가 더 뾰족하므로 여기서 나온 값은
    평일 설계 첨두보다 낮다(보수적). 수요 모델도 28일 평균(주말 포함)이라
    둘의 기준은 서로 맞는다
  - 역 단위 승하차는 **단면 통행량이 아니다.** 첨두율은 "그 시간에 역을
    오간 사람 비중"이고, 최대 단면 방향 통행량으로 바꾸려면 별도 계수가 필요하다
    (`peak_direction_ratio` — 여전히 가정값)
"""

import io
import json
import time
from pathlib import Path
from urllib.request import urlopen

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'data' / 'build'
BASE = 'http://openapi.seoul.go.kr:8088'
SERVICE = 'CardSubwayTime'
PAGE = 1000
MONTHS = 3           # 한 달만 보면 그 달의 행사·방학에 끌려간다
HOURS = list(range(4, 24)) + [0, 1, 2, 3]
MIN_TOTAL = 1000     # 3개월 합계. 이보다 적으면 자료 오류다 (하루 10명 이하)


def load_key():
    for line in (ROOT / '.env').read_text(encoding='utf-8').splitlines():
        if line.startswith('SEOUL_OPENAPI_KEY='):
            return line.split('=', 1)[1].strip()
    raise SystemExit('SEOUL_OPENAPI_KEY 가 없습니다 (.env 확인)')


def fetch(key, start, end, ym):
    url = f'{BASE}/{key}/json/{SERVICE}/{start}/{end}/{ym}'
    with urlopen(url, timeout=60) as res:
        body = json.load(res)
    if SERVICE not in body:
        raise RuntimeError(f'{ym} 응답 오류: {body.get("RESULT")}')
    payload = body[SERVICE]
    code = payload['RESULT']['CODE']
    if code != 'INFO-000':
        raise RuntimeError(f'{ym} 오류 {code}: {payload["RESULT"]["MESSAGE"]}')
    return payload.get('row', []), payload['list_total_count']


def fetch_month(key, ym):
    rows, total = fetch(key, 1, PAGE, ym)
    while len(rows) < total:
        more, _ = fetch(key, len(rows) + 1, len(rows) + PAGE, ym)
        if not more:
            break
        rows.extend(more)
    return dedupe(rows, ym)


def dedupe(rows, ym):
    """
    **같은 역이 두 번 오는 달이 있다.** 2026-07 은 621개 역이 정확히 두 번씩
    실려 1242건으로 왔다. 그대로 더하면 그 달만 두 배로 가중된다.
    값이 같은 중복만 지우고, 값이 다르면 어느 쪽이 맞는지 알 수 없으므로 멈춘다.
    """
    seen = {}
    for r in rows:
        k = (r['SBWY_ROUT_LN_NM'], r['STTN'])
        if k not in seen:
            seen[k] = r
            continue
        prev = seen[k]
        clash = [c for c in r if c != 'JOB_YMD' and prev.get(c) != r.get(c)]
        if clash:
            raise SystemExit(
                f'{ym} {k} 중복 행의 값이 다릅니다 ({clash[:3]}) - 확인이 필요합니다')
    return list(seen.values()), len(rows) - len(seen)


def recent_months(key, count):
    """최근 달부터 거슬러 올라가며 자료가 있는 달을 count 개 모은다."""
    out = []
    y, m = pd.Timestamp.today().year, pd.Timestamp.today().month
    tried = 0
    while len(out) < count and tried < 18:
        ym = f'{y}{m:02d}'
        try:
            rows, removed = fetch_month(key, ym)
            if rows:
                note = f' (중복 {removed}건 제거 - 값이 같아 안전)' if removed else ''
                print(f'  {ym} {len(rows)}건{note}')
                out.append((ym, rows))
        except RuntimeError as e:
            print(f'  {ym} 건너뜀 - {e}')
        m -= 1
        if m == 0:
            y, m = y - 1, 12
        tried += 1
        time.sleep(0.2)
    if not out:
        raise SystemExit('시간대별 자료를 받지 못했습니다')
    return out


def to_frame(months):
    """역×시간대 (승차, 하차) 합계. 여러 달을 더해 한 표로 만든다."""
    acc = {}
    for ym, rows in months:
        for r in rows:
            k = (r['SBWY_ROUT_LN_NM'], r['STTN'])
            cur = acc.setdefault(k, {h: [0.0, 0.0] for h in HOURS})
            for h in HOURS:
                cur[h][0] += float(r.get(f'HR_{h}_GET_ON_NOPE') or 0)
                cur[h][1] += float(r.get(f'HR_{h}_GET_OFF_NOPE') or 0)
    recs = []
    for (line, name), hourly in acc.items():
        rec = {'line_name': line, 'station_name': name}
        for h in HOURS:
            rec[f'on_{h}'] = hourly[h][0]
            rec[f'off_{h}'] = hourly[h][1]
        recs.append(rec)
    return pd.DataFrame(recs)


def summarize(df):
    """역별 첨두 시각·비중과 방향 불균형."""
    total = sum(df[f'on_{h}'] + df[f'off_{h}'] for h in HOURS)
    hourly = pd.DataFrame({h: df[f'on_{h}'] + df[f'off_{h}'] for h in HOURS})
    df = df.assign(
        day_total=total,
        peak_hour=hourly.idxmax(axis=1),
        peak_volume=hourly.max(axis=1),
    )
    noise = df[(df.day_total > 0) & (df.day_total < MIN_TOTAL)]
    if len(noise):
        print(f'  이용객이 거의 없는 {len(noise)}개 행 제외 (자료 오류로 본다)')
        print(noise[['line_name', 'station_name', 'day_total']].to_string(index=False))
    df = df[df.day_total >= MIN_TOTAL].copy()
    df['peak_ratio'] = (df.peak_volume / df.day_total).round(4)
    # 방향 불균형: 첨두시각의 |승차-하차| / (승차+하차).
    # 1 에 가까우면 그 시간에 한쪽으로만 흐른다(출근 방향), 0 이면 양방향 균형.
    on_p = df.apply(lambda r: r[f'on_{int(r.peak_hour)}'], axis=1)
    off_p = df.apply(lambda r: r[f'off_{int(r.peak_hour)}'], axis=1)
    df['peak_imbalance'] = ((on_p - off_p).abs() / (on_p + off_p)).round(4)
    return df


def main():
    key = load_key()
    print(f'시간대별 승하차 수집 (최근 {MONTHS}개월)')
    months = recent_months(key, MONTHS)
    df = summarize(to_frame(months))

    BUILD.mkdir(parents=True, exist_ok=True)
    cols = ['line_name', 'station_name', 'day_total', 'peak_hour',
            'peak_volume', 'peak_ratio', 'peak_imbalance']
    df[cols].sort_values('day_total', ascending=False).to_csv(
        BUILD / 'station_peak.csv', index=False, encoding='utf-8-sig')
    print(f'역 {len(df)}개 → station_peak.csv')

    # 노선 단위 모델에 넣을 값은 역별 중앙값이 아니라 **전체 합계 기준**이다.
    # 노선은 여러 역의 합이므로, 역을 다 더한 뒤 첨두를 잡아야 같은 성격이 된다.
    agg = {h: (df[f'on_{h}'] + df[f'off_{h}']).sum() for h in HOURS}
    grand = sum(agg.values())
    peak_h = max(agg, key=agg.get)
    print(f'\n전체 합계 첨두 {peak_h}시, 비중 {agg[peak_h] / grand:.4f}')
    print('시간대별 비중 (상위 6)')
    for h in sorted(agg, key=agg.get, reverse=True)[:6]:
        print(f'  {h:2d}시 {agg[h] / grand * 100:5.2f}%')

    print(f'\n역별 첨두 비중  중앙값 {df.peak_ratio.median():.4f}'
          f' / 평균 {df.peak_ratio.mean():.4f}'
          f' / 사분위 {df.peak_ratio.quantile(.25):.4f}~{df.peak_ratio.quantile(.75):.4f}')
    print('첨두 시각 분포')
    print(df.peak_hour.value_counts().sort_index().to_string())
    print(f'\n첨두시 방향 불균형  중앙값 {df.peak_imbalance.median():.4f}'
          f' / 평균 {df.peak_imbalance.mean():.4f}')
    print('\n첨두 비중이 가장 높은 역 (한 시간에 몰리는 곳)')
    print(df.nlargest(8, 'peak_ratio')[
        ['line_name', 'station_name', 'peak_hour', 'peak_ratio', 'day_total']
    ].to_string(index=False))


if __name__ == '__main__':
    main()
