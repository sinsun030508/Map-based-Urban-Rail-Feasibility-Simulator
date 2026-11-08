"""
수도권 밖 도시철도 노선별 일평균 승하차 — 수요 회귀의 표본을 수도권 밖으로 넓힌다.

왜 필요한가
  수요 회귀(`demand_model.py`)는 서울 열린데이터광장의 수도권 노선 26개로만 배웠다. 대전·부산·대구·광주 공사가
  공공데이터포털에 **역별 승차·하차(자동개집표기)** 를 올려 두어 서울 자료와 단위가 같다(역 승차 + 하차의 합).
  11-08 에 견줘 보니 수도권 밖 중전철도 0.68~1.12배로 맞았고, 「수도권 밖」 변수는 표준오차보다 작았다 —
  지역을 따로 두지 않고 합쳐 배운다(research-log 「수도권 밖으로 표본을 넓힌다」).

입력  공공데이터포털 파일데이터 (받은 원본은 data/cache/regional 에 둔다)
  15060761  대전교통공사_역별 수송실적       역 행 × 「n월 승차·하차」 열, 한 해의 지난 달까지
  3033569   부산교통공사_도시철도 역별 수송 수입현황   호선·역 행, 연 일평균 승차·하차
  15060371  대구교통공사_월별승차인원       (년, 월) 행 × 역 열, 1~3호선 역이 순서대로
  15060372  대구교통공사_월별하차인원       같은 모양
  15060048  광주교통공사_역일시간대별 승하차량   (일자, 역, 승차/하차) 행 × 시간대 열, 올해 지난 달까지
  15159353  인천교통공사_일별 역별 시간대별 이용인원현황   (일자, 호선, 역, 승차/하차) 행 × 시간대 열 — 1·2호선만(7호선 인천 구간은 서울 7호선의 일부)
  15109434  경기도 의정부시_경전철 승하차 현황   (일자, 역) 행, 합계(승차)·합계(하차) — 한 달치
  15114113  경기도 용인시_용인경전철 역사별 승하차인원 현황   역 행 × 「n년 승차·하차」 열
출력  data/build/regional_line_ridership.csv   line_name, daily_total, period, source

틀려도 조용히 넘어가는 곳
  ① 대구 파일에는 호선 칸이 없다 — 역 열이 1호선(설화명곡~하양) → 2호선(문양~영남대) → 3호선(칠곡경대병원~용지)
     순서로 놓여 있어 **양 끝 역 이름으로** 구간을 자른다. 환승역은 「반월당1」 「반월당2」 처럼 열이 따로다
  ② 대구 승차·하차 파일은 갱신 시점이 달라 마지막 달이 다를 수 있다 — **둘 다 있는 달**만 12개월 쓴다
  ③ 용인 파일의 2025 하차는 15개 역 모두 2024 하차와 똑같다(11-09 확인 — 승차는 모두 다르다). 지난해 값을 베낀 오류라
     **하차가 전년과 모든 역에서 같은 해는 건너뛰고** 가장 최근의 온전한 해를 쓴다
  ④ 기간이 공사마다 다르다(대전 올해 1~9월, 부산 지난해, 대구 최근 12개월). 연 단위 변화는 수 % 라 섞어 쓴다

    python etl/regional_ridership.py
"""

import calendar
import json
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / 'data' / 'cache' / 'regional'
OUT = ROOT / 'data' / 'build' / 'regional_line_ridership.csv'
PORTAL = 'https://www.data.go.kr'
UA = {'User-Agent': 'railfeas-student-project'}

DAEGU_BLOCKS = {'대구 도시철도 1호선': ('설화명곡', '하양'),
                '대구 도시철도 2호선': ('문양', '영남대'),
                '대구 도시철도 3호선': ('칠곡경대병원', '용지')}


def daejeon(df, days):
    cols = [c for c in df.columns if c.endswith('승차') or c.endswith('하차')]
    return {'대전 도시철도 1호선': float(df[cols].apply(pd.to_numeric, errors='coerce').sum().sum()) / days}


def busan(df):
    total = df['승차 일평균(명)'] + df['하차 일평균(명)']
    return {f'부산 도시철도 {int(k)}호선': float(v) for k, v in total.groupby(df['호선']).sum().items()}


def _ym(d):
    return d['년'].astype(int) * 100 + d['월'].astype(int)


def daegu_months(on, off, months=12):
    common = sorted(set(_ym(on)) & set(_ym(off)))
    if len(common) < months:
        raise ValueError(f'대구 승차·하차가 함께 있는 달이 {len(common)}개뿐이다 — {months}개월이 필요하다')
    return common[-months:]


def daegu(on, off, months=12):
    ym = _ym
    keep = daegu_months(on, off, months)
    days = sum(calendar.monthrange(k // 100, k % 100)[1] for k in keep)
    cols = [c for c in on.columns if c not in ('년', '월')]
    out = {}
    for line, (first, last) in DAEGU_BLOCKS.items():
        block = cols[cols.index(first):cols.index(last) + 1]
        total = 0.0
        for d in (on, off):
            sel = d[ym(d).isin(keep)][[c for c in block if c in d.columns]]
            total += float(sel.apply(pd.to_numeric, errors='coerce').sum().sum())
        out[line] = total / days
    return out


def gwangju(df):
    hours = [c for c in df.columns if re.fullmatch(r'\d{2}_\d{2}', c)]
    total = float(df[hours].apply(pd.to_numeric, errors='coerce').sum().sum())
    return {'광주 도시철도 1호선': total / df['일자'].nunique()}


def incheon(df):
    hours = [c for c in df.columns if '시' in c and c != '수송일자']
    days = df['수송일자'].nunique()
    total = df[hours].apply(pd.to_numeric, errors='coerce').sum(axis=1)
    out = {}
    for line, name in ((1, '인천1호선'), (2, '인천2호선')):
        out[name] = float(total[df['호선명'] == line].sum()) / days
    return out


def uijeongbu(df):
    total = df['합계(승차)'].sum() + df['합계(하차)'].sum()
    return {'의정부선': float(total) / df['일자'].nunique()}


def yongin(df):
    """(값, 쓴 해). 하차가 전년과 모든 역에서 같은 해는 베낀 오류라 건너뛴다"""
    years = sorted({int(m.group(1)) for c in df.columns if (m := re.match(r'(\d{4})년 승차', c))}, reverse=True)
    for y in years:
        prev = f'{y - 1}년 하차'
        if prev in df.columns and (df[f'{y}년 하차'] == df[prev]).all():
            continue
        days = 366 if calendar.isleap(y) else 365
        return {'에버라인선': float(df[f'{y}년 승차'].sum() + df[f'{y}년 하차'].sum()) / days}, y
    raise ValueError('용인 파일에 온전한 해가 없다')


def download(pk):
    """공공데이터포털 파일데이터 한 건을 받아 캐시에 둔다. 돌려주는 것은 (경로, 파일 이름)"""
    CACHE.mkdir(parents=True, exist_ok=True)
    path, meta = CACHE / f'{pk}.csv', CACHE / f'{pk}.name'
    if path.exists() and meta.exists():
        return path, meta.read_text(encoding='utf-8')
    page = urllib.request.urlopen(urllib.request.Request(f'{PORTAL}/data/{pk}/fileData.do', headers=UA),
                                  timeout=60).read().decode('utf-8', 'replace')
    detail = re.search(r"fn_fileDataDown\('%s', '([^']+)'" % pk, page).group(1)
    form = urllib.parse.urlencode({'publicDataDetailPk': detail, 'publicDataPk': pk, 'atchFileId': '',
                                   'fileDetailSn': '1', 'publicDataTyCode': 'PR0051'}).encode()
    info = json.loads(urllib.request.urlopen(urllib.request.Request(
        f'{PORTAL}/tcs/dss/selectFileDataDownload.do', data=form, headers=UA), timeout=60).read())
    resp = urllib.request.urlopen(urllib.request.Request(
        f"{PORTAL}/cmm/cmm/fileDownload.do?atchFileId={info['atchFileId']}&fileDetailSn=1", headers=UA), timeout=180)
    name = urllib.parse.unquote(re.search(r'filename="?([^";]+)', resp.headers.get('Content-Disposition', '')).group(1))
    if name.encode('latin-1', 'ignore').decode('latin-1') == name:
        try:
            name = name.encode('latin-1').decode('utf-8')
        except UnicodeError:
            pass
    path.write_bytes(resp.read())
    meta.write_text(name, encoding='utf-8')
    return path, name


def daejeon_days(name, df):
    """파일 이름의 해(…_20260101~20260930.csv)와 열에 있는 달로 날 수를 센다"""
    year = int(re.search(r'(\d{4})\d{4}~', name).group(1))
    months = sorted({int(re.match(r'(\d+)월', c).group(1)) for c in df.columns if re.match(r'\d+월 승차', c)})
    return sum(calendar.monthrange(year, m)[1] for m in months), f'{year}-{months[0]:02d}~{year}-{months[-1]:02d}'


def main():
    rows = []
    path, name = download('15060761')
    dj = pd.read_csv(path, encoding='cp949')
    days, period = daejeon_days(name, dj)
    rows += [(k, v, period, '대전교통공사_역별 수송실적') for k, v in daejeon(dj, days).items()]

    path, name = download('3033569')
    year = re.search(r'(\d{4})년', name).group(1)
    rows += [(k, v, year, '부산교통공사_도시철도 역별 수송 수입현황') for k, v in busan(pd.read_csv(path, encoding='cp949')).items()]

    on = pd.read_csv(download('15060371')[0], encoding='cp949')
    off = pd.read_csv(download('15060372')[0], encoding='cp949')
    on = on.loc[:, ~on.columns.str.startswith('Unnamed')]
    off = off.loc[:, ~off.columns.str.startswith('Unnamed')]
    keep = daegu_months(on, off)
    period = f'{keep[0] // 100}-{keep[0] % 100:02d}~{keep[-1] // 100}-{keep[-1] % 100:02d}'
    rows += [(k, v, period, '대구교통공사_월별승차·하차인원') for k, v in daegu(on, off).items()]

    gj = pd.read_csv(download('15060048')[0], encoding='cp949')
    period = f"{gj['일자'].min()}~{gj['일자'].max()}"
    rows += [(k, v, period, '광주교통공사_역일시간대별 승하차량') for k, v in gwangju(gj).items()]

    ic = pd.read_csv(download('15159353')[0], encoding='cp949')
    period = f"{ic['수송일자'].min()}~{ic['수송일자'].max()}"
    rows += [(k, v, period, '인천교통공사_일별 역별 시간대별 이용인원현황') for k, v in incheon(ic).items()]

    ui = pd.read_csv(download('15109434')[0], encoding='cp949')
    period = f"{ui['일자'].min()}~{ui['일자'].max()}"
    rows += [(k, v, period, '경기도 의정부시_경전철 승하차 현황') for k, v in uijeongbu(ui).items()]

    got, year = yongin(pd.read_csv(download('15114113')[0], encoding='cp949'))
    rows += [(k, v, str(year), '경기도 용인시_용인경전철 역사별 승하차인원 현황') for k, v in got.items()]

    df = pd.DataFrame(rows, columns=['line_name', 'daily_total', 'period', 'source'])
    df['daily_total'] = df.daily_total.round(0).astype(int)
    df.to_csv(OUT, index=False, encoding='utf-8')
    print(df.to_string(index=False))
    print(f'\n{OUT} 생성')


if __name__ == '__main__':
    sys.stdout.reconfigure(errors='replace')
    main()
