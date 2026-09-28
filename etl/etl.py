"""
노선 타당성 분석 시뮬레이터 — 노선 사례 데이터 통합 ETL

입력 (포맷·단위가 모두 다름)
  1) 전체_도시철도노선정보_*.xlsx  : 연장=미터, 금액없음, 역=문자열
  2) 국가철도공단_철도건설현황_*.csv : 금액=백만원, 연장없음, cp949
  3) 데이터.txt                     : 연장=km, 금액=억원, 헤더 반복 블록

출력
  reference_line.csv  : 표준 스키마 통합본
  reference_line.sql  : INSERT 문
"""

import re
import sys
import pandas as pd
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent      # 저장소 루트
RAW = ROOT / 'data' / 'raw'                        # 원본 — 수정 금지
SEED = ROOT / 'data' / 'seed'                      # 수기 정리분
BUILD = ROOT / 'data' / 'build'                    # ETL 산출물
DB_SEED = ROOT / 'db' / 'seed'                     # 적재 스크립트

# 원본 파일명 (연월일 접미사가 바뀌면 글롭으로 최신본을 잡는다)
def latest(pattern):
    files = sorted(RAW.glob(pattern))
    if not files:
        raise FileNotFoundError(f'{RAW}/{pattern} 없음')
    return files[-1]

# db/schema/V1__create_tables.sql 과 1:1 대응 (파생 컬럼 제외)
SQL_COLUMNS = [
    'line_name', 'section_name', 'operator',
    'rail_class', 'mode_type', 'region_class', 'raw_type_text',
    'length_km', 'station_count', 'underground_ratio',
    'total_cost', 'base_year', 'base_year_status', 'total_cost_2025', 'cost_status',
    'period_start', 'period_end', 'opened_year',
    'source_name', 'is_outlier', 'note',
]


# ---------------------------------------------------------------
# 공통 정규화 유틸
# ---------------------------------------------------------------

TILDES = '~∼〜～–—-'          # 구간·기간 구분자로 쓰이는 물결/대시 변형
DOT_VARIANTS = '․·ㆍ'          # '문경․경북선' 같은 중점 변형


def clean_text(v):
    if pd.isna(v):
        return None
    s = str(v).strip()
    s = re.sub(f'[{DOT_VARIANTS}]', '·', s)
    s = re.sub(r'\s+', ' ', s)
    return s or None


def to_number(v):
    """'10,719' '43055 ' '-' '' → float | None"""
    if pd.isna(v):
        return None
    s = re.sub(r'[,\s]', '', str(v))
    if s in ('', '-', '미정', 'N/A'):
        return None
    try:
        return float(s)
    except ValueError:
        return None


def to_int(v):
    n = to_number(v)
    return int(n) if n is not None else None


def parse_period(v):
    """'2020~2032' '2025∼2033' → (2020, 2032)"""
    s = clean_text(v)
    if not s:
        return None, None
    years = re.findall(r'(19|20)\d{2}', s)
    nums = re.findall(r'(?:19|20)\d{2}', s)
    if len(nums) >= 2:
        return int(nums[0]), int(nums[1])
    if len(nums) == 1:
        return int(nums[0]), None
    return None, None


def count_stations(v):
    """'A01-서울,A02-공덕,...' → 14"""
    s = clean_text(v)
    if not s:
        return None
    parts = [p for p in s.split(',') if p.strip()]
    return len(parts) or None


# ---------------------------------------------------------------
# 유형 분류
# ---------------------------------------------------------------

# 경전철로 알려진 도시철도 노선 (운영현황·xlsx 모두 유형 표기가 없음)
# 노선명에 부분 문자열로 포함되면 경전철로 본다.
LIGHT_RAIL_LINES = {
    '우이-신설', '우이신설', '신림선', '의정부', '용인', '김포',
    '부산-김해', '김해', '인천공항자기부상', '동북선', '위례',
    '인천 2호선', '인천지하철 2호선',
}


def is_light_rail(name):
    s = clean_text(name) or ''
    return any(k in s for k in LIGHT_RAIL_LINES)

TYPE_RULES = [
    (r'기존선\s*개량|기존선\s*고속화|개량|고속화', 'UPGRADE'),
    (r'복선', 'DOUBLE_ELEC'),
    (r'단선', 'SINGLE_ELEC'),
    (r'트램|노면전차', 'TRAM'),
    (r'경량|경전철|모노레일|AGT', 'LIGHT_RAIL'),
]


def classify_type(text, fallback=None):
    s = clean_text(text) or ''
    for pattern, code in TYPE_RULES:
        if re.search(pattern, s):
            return code
    return fallback


CAPITAL = r'서울|인천|경기|수도권|김포|용인|의정부|하남|남양주|고양|성남|부천|안산|시흥|광명|과천|파주|양주|구리|의왕|수원|화성|평택|김포|신분당|GTX|광역급행'
METRO_C = r'부산|대구|광주|대전|울산|세종'


def classify_region(*texts):
    s = ' '.join(clean_text(t) or '' for t in texts)
    if re.search(CAPITAL, s):
        return 'CAPITAL'
    if re.search(METRO_C, s):
        return 'METRO_C'
    return 'LOCAL'


def split_line_name(name):
    """'신안산선 복선전철' → ('신안산선', '복선전철')"""
    s = clean_text(name) or ''
    m = re.search(r'\s(복선전철|단선전철|복선전철화|단선개량|기존선개량)$', s)
    if m:
        return s[:m.start()].strip(), m.group(1)
    # '경부고속철도철도' 같은 어절 중복 제거
    s = re.sub(r'(철도)\1+', r'\1', s)
    return s, None


# ---------------------------------------------------------------
# 소스 1) 도시철도 표준데이터 (xlsx)
# ---------------------------------------------------------------

def load_metro_xlsx(path):
    df = pd.read_excel(path, header=0)
    rows = []
    for _, r in df.iterrows():
        length_m = to_number(r.get('노선연장'))
        name, _ = split_line_name(r.get('노선명'))
        rtype = classify_type(r.get('노선명'),
                              fallback='LIGHT_RAIL' if is_light_rail(r.get('노선명')) else 'HEAVY_METRO')
        rows.append({
            'line_name': name,
            'section_name': f"{clean_text(r.get('기점명'))}~{clean_text(r.get('종점명'))}",
            'operator': clean_text(r.get('운영기관명')),
            'rail_class': 'METRO',
            'mode_type': rtype,
            'region_class': classify_region(r.get('노선명'), r.get('운영기관명'),
                                            r.get('기점명'), r.get('종점명')),
            'raw_type_text': None,
            'length_km': round(length_m / 1000, 3) if length_m else None,
            'station_count': count_stations(r.get('정거장구성')),
            'total_cost': None,
            'base_year': None,
            'cost_status': 'UNDISCLOSED',
            'period_start': None,
            'period_end': None,
            'opened_year': (pd.to_datetime(r.get('개통일자'), errors='coerce').year
                            if pd.notna(r.get('개통일자')) else None),
            'source_name': '전국도시철도노선정보 표준데이터',
        })
    return pd.DataFrame(rows)


# ---------------------------------------------------------------
# 소스 2) 철도건설현황 (csv, cp949, 금액=백만원)
# ---------------------------------------------------------------

def load_history_csv(path):
    df = pd.read_csv(path, encoding='cp949')
    rows = []
    for _, r in df.iterrows():
        cost_mil = to_number(r.get('총사업비'))
        # 0원은 미기재로 간주
        cost_eok = round(cost_mil / 100) if cost_mil else None
        name, type_suffix = split_line_name(r.get('사업명'))
        rows.append({
            'line_name': name,
            'section_name': None,
            'operator': '국가철도공단',
            'rail_class': 'GENERAL',
            'mode_type': classify_type(r.get('사업명'), fallback=None),
            'region_class': classify_region(r.get('사업명')),
            'raw_type_text': type_suffix,
            'length_km': None,
            'station_count': None,
            'total_cost': cost_eok,
            'base_year': None,
            'cost_status': 'DISCLOSED' if cost_eok else 'UNDISCLOSED',
            'period_start': (pd.to_datetime(r.get('사업기간(시작일)'), errors='coerce').year
                             if pd.notna(r.get('사업기간(시작일)')) else None),
            'period_end': (pd.to_datetime(r.get('사업기간(종료일)'), errors='coerce').year
                           if pd.notna(r.get('사업기간(종료일)')) else None),
            'opened_year': None,
            'source_name': '국가철도공단 철도건설현황',
        })
    return pd.DataFrame(rows)


# ---------------------------------------------------------------
# 소스 3) 사업계획 (txt, 헤더 반복 블록)
# ---------------------------------------------------------------

BLOCK_HEADERS = {
    'PLAN':        ('노선명', '사업구간'),
    'METRO_OPS':   ('구분', '노선'),
    'REGIONAL_OPS': ('기관', '유형'),
}


def split_blocks(raw):
    """헤더 행을 만날 때마다 블록을 새로 연다."""
    blocks, kind, buf = [], None, []
    for line in raw.splitlines():
        cols = [c.strip() for c in line.split('\t')]
        matched = next((k for k, sig in BLOCK_HEADERS.items()
                        if cols[:len(sig)] == list(sig)), None)
        if matched:
            if kind and buf:
                blocks.append((kind, buf))
            kind, buf = matched, []
            continue
        if line.strip():
            buf.append(line)
    if kind and buf:
        blocks.append((kind, buf))
    return blocks


def parse_plan(lines):
    """사업계획 블록: 노선명/사업구간/사업내용/연장/총사업비/사업기간"""
    rows = []
    for line in lines:
        cols = line.split('\t')
        if len(cols) < 6:
            continue
        name_raw, section, content, length, cost, period = cols[:6]
        name, _ = split_line_name(name_raw)
        start, end = parse_period(period)
        is_regional = bool(re.search(r'광역|급행|신분당|GTX', name_raw))
        rows.append({
            'line_name': name,
            'section_name': clean_text(section),
            'operator': '국가철도공단',
            'rail_class': 'REGIONAL' if is_regional else 'GENERAL',
            'mode_type': classify_type(content),
            'region_class': classify_region(name_raw, section),
            'raw_type_text': clean_text(content),
            'length_km': to_number(length),
            'station_count': None,
            'total_cost': to_number(cost),
            'base_year': None,
            'cost_status': 'DISCLOSED',
            'period_start': start,
            'period_end': end,
            'opened_year': None,
            'source_name': '국가철도공단 사업계획',
        })
    return rows


def load_mixed_txt(path):
    """한 파일 안에 성격이 다른 표가 섞여 있는 경우를 블록 단위로 처리."""
    raw = Path(path).read_text(encoding='utf-8')
    handlers = {
        'PLAN': parse_plan,
        'METRO_OPS': parse_metro_ops,
        'REGIONAL_OPS': parse_regional_ops,
    }
    rows = []
    for kind, lines in split_blocks(raw):
        rows.extend(handlers[kind](lines))
    return pd.DataFrame(rows)


# ---------------------------------------------------------------
# 소스 4) 도시철도 운영현황 (txt 블록, 연장+역수, 금액 없음)
# ---------------------------------------------------------------

SKIP_ROW = re.compile(r'^(합계|소계|계)$')
REGION_HEAD = re.compile(r'^(서울|부산|대구|인천|광주|대전|울산|세종|경기)(\(\d+\))?$')


def _metro_region(label):
    return re.sub(r'\(\d+\)', '', label).strip()


def parse_metro_ops(lines):
    """'구분 노선 연장 역수 구간 개통일' 블록. 구분 셀이 병합돼 비는 행이 섞임."""
    rows, region = [], None
    for line in lines:
        cols = [c.strip() for c in line.split('\t')]
        if not cols or SKIP_ROW.match(cols[0]):
            continue
        # 지역 헤더 행 (예: '서울(11)  소계  384.6  346')
        if len(cols) >= 2 and REGION_HEAD.match(cols[0]) and cols[1] in ('소계', '계'):
            region = _metro_region(cols[0])
            continue
        # 지역 + 노선이 한 행에 있는 경우 (예: '광주  1호선  20.5  20  ...')
        if len(cols) >= 4 and REGION_HEAD.match(cols[0]) and to_number(cols[1]) is None:
            region = _metro_region(cols[0])
            cols = cols[1:]
        name = clean_text(cols[0])
        length = to_number(cols[1]) if len(cols) > 1 else None
        stations = to_number(cols[2]) if len(cols) > 2 else None
        if not name or length is None or stations is None:
            continue                      # 줄바꿈으로 흘러나온 조각 행
        full = f'{region} {name}' if region else name
        rows.append({
            'line_name': full,
            'section_name': clean_text(cols[3]) if len(cols) > 3 else None,
            'operator': None,
            'rail_class': 'METRO',
            'mode_type': 'LIGHT_RAIL' if is_light_rail(re.sub(r'\(.*?\)', '', full)) else 'HEAVY_METRO',
            'region_class': classify_region(region or '', name),
            'raw_type_text': None,
            'length_km': length,
            'station_count': int(stations),
            'total_cost': None,
            'base_year': None,
            'cost_status': 'UNDISCLOSED',
            'period_start': None,
            'period_end': None,
            'opened_year': parse_short_year(cols[4]) if len(cols) > 4 else None,
            'source_name': '국토교통부 도시철도 운영현황',
        })
    return rows


# ---------------------------------------------------------------
# 소스 5) 광역철도 운영현황 (txt 블록, 구간별 영업연장만)
# ---------------------------------------------------------------

def parse_regional_ops(lines):
    """'기관 유형 구분 구간 영업연장 개통일' — 병합셀 탓에 열 수가 들쭉날쭉."""
    rows = []
    for line in lines:
        cols = [c.strip() for c in line.split('\t')]
        cols = [c for c in cols if c]
        if not cols or SKIP_ROW.match(cols[0]):
            continue
        # 뒤에서부터 '구간(A~B)'과 '연장(숫자)'을 찾는다
        seg_idx = next((i for i, c in enumerate(cols)
                        if re.search(f'[{TILDES}]', c) and to_number(c) is None), None)
        if seg_idx is None or seg_idx + 1 >= len(cols):
            continue
        length = to_number(cols[seg_idx + 1])
        if length is None:
            continue
        name = clean_text(cols[seg_idx - 1]) if seg_idx > 0 else None
        if not name:
            continue
        rows.append({
            'line_name': name,
            'section_name': clean_text(cols[seg_idx]),
            'operator': clean_text(cols[0]) if seg_idx > 1 else None,
            'rail_class': 'REGIONAL',
            'mode_type': 'DOUBLE_ELEC',
            'region_class': classify_region(name, cols[seg_idx]),
            'raw_type_text': None,
            'length_km': length,
            'station_count': None,
            'total_cost': None,
            'base_year': None,
            'cost_status': 'UNDISCLOSED',
            'period_start': None,
            'period_end': None,
            'opened_year': parse_short_year(cols[seg_idx + 2]) if len(cols) > seg_idx + 2 else None,
            'source_name': '국토교통부 광역철도 운영현황',
        })
    return rows


def parse_short_year(v):
    """'’74.08.15' '‘24.12.28.' → 1974 / 2024"""
    s = clean_text(v)
    if not s:
        return None
    m = re.search(r'(\d{2})\.', s)
    if not m:
        return None
    yy = int(m.group(1))
    return 1900 + yy if yy >= 50 else 2000 + yy



# ---------------------------------------------------------------
# 소스 6) BRT 시드 (수기 정리 csv)
# ---------------------------------------------------------------

BRT_REGION = {'수도권': 'CAPITAL', '부산울산권': 'METRO_C',
              '대전권': 'METRO_C', '대구권': 'METRO_C', '광주권': 'METRO_C'}


def load_brt_seed(path):
    df = pd.read_csv(path)
    rows = []
    for _, r in df.iterrows():
        note = clean_text(r.get('note')) or ''
        rows.append({
            'line_name': clean_text(r['line_name']),
            'section_name': clean_text(r.get('section_name')),
            'operator': None,
            'rail_class': 'BRT',
            'mode_type': 'BRT_HIGH' if r['brt_grade'] == 'HIGH' else 'BRT_LOW',
            'region_class': BRT_REGION.get(clean_text(r.get('region')), 'LOCAL'),
            'raw_type_text': clean_text(r.get('brt_grade')),
            'length_km': to_number(r.get('length_km')),
            'station_count': (int(to_number(r['station_count']))
                              if to_number(r.get('station_count')) else None),
            'total_cost': to_number(r.get('total_cost')),
            'base_year': None,
            'cost_status': 'DISCLOSED',
            'period_start': (int(to_number(r['period_start']))
                             if to_number(r.get('period_start')) else None),
            'period_end': (int(to_number(r['period_end']))
                           if to_number(r.get('period_end')) else None),
            'opened_year': None,
            'source_name': clean_text(r.get('source_name')),
            'is_outlier': '이상치' in note,
            'note': note or None,
        })
    return pd.DataFrame(rows)


# ---------------------------------------------------------------
# 소스 7) 도시철도 비용 시드 (수기 정리 csv)
# 원본 3종엔 지하철·경전철 금액이 없어서, 공개 자료(인천교통공사 등)와
# 건설현황 금액+xlsx 연장을 구간 단위로 직접 맞춘 건만 모은다.
# ---------------------------------------------------------------

def load_metro_seed(path):
    df = pd.read_csv(path)
    rows = []
    for _, r in df.iterrows():
        note = clean_text(r.get('note')) or ''
        rows.append({
            'line_name': clean_text(r['line_name']),
            'section_name': clean_text(r.get('section_name')),
            'operator': None,
            'rail_class': 'METRO',
            'mode_type': clean_text(r['mode_type']),
            'region_class': clean_text(r['region_class']),
            'raw_type_text': None,
            'length_km': to_number(r.get('length_km')),
            'station_count': int(to_number(r['station_count'])),
            'underground_ratio': to_number(r.get('underground_ratio')),
            'total_cost': to_number(r.get('total_cost')),
            'base_year': to_int(r.get('base_year')),
            'cost_status': 'DISCLOSED',
            'period_start': to_int(r.get('period_start')),
            'period_end': to_int(r.get('period_end')),
            'opened_year': None,
            'source_name': clean_text(r.get('source_name')),
            'is_outlier': '이상치' in note,
            'note': note or None,
        })
    return pd.DataFrame(rows)


# ---------------------------------------------------------------
# 알려진 이상치 — 지우지 않고 is_outlier 플래그만 남긴다 (CLAUDE.md 참고).
# BRT는 brt_seed.csv 의 note 에 '이상치'가 있으면 load_brt_seed 에서 이미 처리됨.
# 여기서는 그 외 출처(사업계획/건설현황)에서 온 알려진 사례만 다룬다.
# ---------------------------------------------------------------

KNOWN_OUTLIERS = {
    ('용산~강남', '용산~강남'):
        '7.8km 대심도 구간에 고정비 집중 — km당 단가 이상치',
    ('장항선', '신창~대야'):
        '118.6km 개량사업 — 장거리 개량이라 km당 단가 이상치',
    ('충청권 광역철도(옥천연장)', '오정~옥천'):
        '기존선 활용 구간 — km당 단가 이상치',
    ('태화강~북울산 광역철도', '태화강~북울산'):
        '기존선 활용 구간 — km당 단가 이상치',
    # xlsx 원본 오류 — 역간격 통계에 섞이지 않도록 제외
    ('수도권 경량도시철도 에버라인', '기흥(백남준아트센터)~전대·에버랜드'):
        'xlsx 원본 오류 — 연장 0.018km (실제 18.1km, 단위 오기로 추정)',
    ('신분당선', '신사역~광교(경기대)'):
        'xlsx 원본 오류 — 역수 1·개통 2028 (정거장구성 미기재로 추정)',
}


# ---------------------------------------------------------------
# 출처 간 동일 사업 — 이름·구간 표기가 달라 노선명+구간 중복 제거에 안 걸리는 건.
# 등급이 낮은 쪽 행을 지우고, 남는 행 note 에 지운 행의 원문을 남긴다.
# (지울 행 line_name, 지울 행 출처, 남길 행 line_name)
# ---------------------------------------------------------------

HISTORY = '국가철도공단 철도건설현황'
REGIONAL_OPS = '국토교통부 광역철도 운영현황'

SAME_PROJECT = [
    ('김포도시철도 건설사업', HISTORY, '김포도시철도'),
    ('별내선', REGIONAL_OPS, '별내선(8호선 연장)'),
    ('하남선', REGIONAL_OPS, '하남선(5호선 연장)'),
    ('당고개~진접', HISTORY, '진접선'),
    ('진접선', REGIONAL_OPS, '진접선'),
    ('충청권 광역철도 옥천연장(대전~옥천)', HISTORY, '충청권 광역철도(옥천연장)'),
    ('석문산단 인입철도', HISTORY, '석문산단 인입철도'),
    ('수서~광주', HISTORY, '수서광주선'),
]


def drop_same_projects(df):
    for dup, dup_src, keep in SAME_PROJECT:
        dup_mask = (df['line_name'] == dup) & (df['source_name'] == dup_src)
        cand = df[(df['line_name'] == keep) & ~dup_mask].sort_values('data_grade')
        # 원본이 바뀌어 매칭이 어긋나면 조용히 넘어가지 말고 멈춘다
        if dup_mask.sum() != 1 or cand.empty:
            raise ValueError(f'SAME_PROJECT 매칭 실패: {dup} ({dup_src}) → {keep}')
        keep_idx = cand.index[0]
        if df.loc[dup_mask, 'data_grade'].iloc[0] <= df.loc[keep_idx, 'data_grade']:
            raise ValueError(f'SAME_PROJECT 등급 역전: {dup} 가 {keep} 보다 등급이 높거나 같음')
        memo = f"{dup_src} '{dup}'과 동일 사업 (중복 행 제거)"
        old = df.loc[keep_idx, 'note']
        df.loc[keep_idx, 'note'] = f'{old} / {memo}' if pd.notna(old) else memo
        df = df[~dup_mask]
    return df.reset_index(drop=True)


def flag_known_outliers(df):
    for (name, section), reason in KNOWN_OUTLIERS.items():
        mask = (df['line_name'] == name) & (df['section_name'] == section)
        if not mask.any():
            continue
        df.loc[mask, 'is_outlier'] = True
        empty_note = df.loc[mask, 'note'].isna()
        df.loc[mask & empty_note, 'note'] = reason
    return df


# ---------------------------------------------------------------
# 물가 환산 — 기준연도가 제각각이라 그대로 회귀하면 옛 사업이 싸게 잡힌다.
# 기준연도가 명시된 건은 그 값을, 없으면 사업 종료연도 → 개통연도 → 착공연도 순으로
# 대체하고 base_year_status='ASSUMED' 로 구분한다.
# ---------------------------------------------------------------

def load_price_index(path):
    df = pd.read_csv(path)
    base = df.loc[df.base_year == df.base_year.max(), 'deflator'].iloc[0]
    df['factor_to_2025'] = (base / df['deflator']).round(4)
    return df


def apply_price_index(df, px):
    factor = dict(zip(px.base_year, px.factor_to_2025))
    lo, hi = px.base_year.min(), px.base_year.max()

    stated = df['base_year'].notna()
    fallback = df['period_end'].fillna(df['opened_year']).fillna(df['period_start'])
    df['base_year'] = df['base_year'].fillna(fallback)
    df['base_year_status'] = stated.map({True: 'STATED', False: 'ASSUMED'})
    df.loc[df['base_year'].isna(), 'base_year_status'] = None

    # 표에 없는 연도는 양 끝 값으로 자른다 (2026년 준공 예정 사업 등)
    yr = df['base_year'].clip(lower=lo, upper=hi)
    df['total_cost_2025'] = (df['total_cost'] * yr.map(factor)).round()
    return df


def price_index_sql(px, path):
    lines = [
        '-- 자동 생성 파일 — data/seed/price_index.csv 를 고치고 etl.py 를 다시 실행할 것',
        '',
        'TRUNCATE TABLE price_index;',
        '',
    ]
    for _, r in px.iterrows():
        lines.append(
            'INSERT INTO price_index (base_year, deflator, factor_to_2025, source) '
            f"VALUES ({int(r.base_year)}, {r.deflator}, {r.factor_to_2025}, '{r.source}');")
    Path(path).write_text(chr(10).join(lines), encoding='utf-8')


# ---------------------------------------------------------------
# 통합 + 파생 지표
# ---------------------------------------------------------------

def build():
    frames = [
        load_metro_xlsx(latest('전체_도시철도노선정보*.xlsx')),
        load_history_csv(latest('국가철도공단_철도건설현황*.csv')),
        load_mixed_txt(latest('데이터*.txt')),
        load_brt_seed(SEED / 'brt_seed.csv'),
        load_metro_seed(SEED / 'metro_seed.csv'),
    ]
    df = pd.concat(frames, ignore_index=True)
    df['is_outlier'] = df.get('is_outlier', False).fillna(False).astype(bool)
    if 'underground_ratio' not in df.columns:
        df['underground_ratio'] = None
    if 'note' not in df.columns:
        df['note'] = None

    # 파생 지표
    df['cost_per_km'] = (df['total_cost'] / df['length_km']).round(1)
    df['avg_spacing_km'] = (df['length_km'] / df['station_count']).round(3)

    # 완결성 등급
    def grade(r):
        has_cost = pd.notna(r['total_cost'])
        has_len = pd.notna(r['length_km'])
        has_st = pd.notna(r['station_count'])
        if has_cost and has_len and has_st:
            return 'A'
        if has_cost and has_len:
            return 'B'
        if has_len and has_st:
            return 'C'
        return 'D'

    df['data_grade'] = df.apply(grade, axis=1)

    # 중복 제거 (노선명+구간 기준, 정보가 더 많은 쪽 유지)
    df['_fill'] = df[['length_km', 'station_count', 'total_cost']].notna().sum(axis=1)
    df = (df.sort_values('_fill', ascending=False)
            .drop_duplicates(subset=['line_name', 'section_name'], keep='first')
            .drop(columns='_fill')
            .sort_values(['data_grade', 'line_name'])
            .reset_index(drop=True))
    df = flag_known_outliers(df)
    df = drop_same_projects(df)
    df = apply_price_index(df, load_price_index(SEED / 'price_index.csv'))
    return df


# 같은 노선이 운영현황·xlsx·시드에 중복돼 있어 전체로 집계하면 한 노선이 여러 번 잡힌다.
# 역간격 통계는 운영현황 한 출처만 쓰고, 운영현황에 없는 수단(트램·BRT)만 다른 출처를 쓴다.
SPACING_SOURCE = '국토교통부 도시철도 운영현황'


def spacing_stats(df):
    s = df[df['avg_spacing_km'].notna() & ~df['is_outlier']]
    ops = s[s['source_name'] == SPACING_SOURCE]
    s = pd.concat([ops, s[~s['mode_type'].isin(ops['mode_type'].unique())]])
    return (s.groupby('mode_type')['avg_spacing_km']
             .agg(n='count', 중앙값='median', 최소='min', 최대='max').round(2))


def to_sql(df, path):
    # db/schema/V1__create_tables.sql 의 컬럼과 1:1 대응 — 스키마 변경 시 함께 수정
    cols = SQL_COLUMNS

    def lit(v):
        if isinstance(v, (bool,)) or str(type(v)) == "<class 'numpy.bool_'>":
            return 'TRUE' if v else 'FALSE'
        if pd.isna(v):
            return 'NULL'
        if isinstance(v, (int, float)):
            return str(int(v)) if float(v).is_integer() else str(v)
        return "'" + str(v).replace("'", "''") + "'"

    lines = [
        '-- 자동 생성 파일 — 직접 수정하지 말고 etl/etl.py 를 다시 실행할 것',
        f'-- 생성 시각: {pd.Timestamp.now():%Y-%m-%d %H:%M}',
        f'-- 총 {len(df)}건'
        f" (A {(df.data_grade == 'A').sum()} /"
        f" B {(df.data_grade == 'B').sum()} /"
        f" C {(df.data_grade == 'C').sum()} /"
        f" D {(df.data_grade == 'D').sum()})",
        '',
        'TRUNCATE TABLE reference_line;',
        '',
    ]
    for _, r in df.iterrows():
        vals = ', '.join(lit(r[c]) for c in cols)
        lines.append(f"INSERT INTO reference_line ({', '.join(cols)}) VALUES ({vals});")
    Path(path).write_text('\n'.join(lines), encoding='utf-8')


if __name__ == '__main__':
    # Windows 기본 콘솔(cp949)은 이모지·특수 문장부호를 못 그려 print 에서
    # UnicodeEncodeError 로 죽는다. 파일 출력엔 영향 없지만 exit code가
    # 1로 나가 성공한 실행도 실패처럼 보이므로 깨진 글자로 대체하고 계속한다.
    sys.stdout.reconfigure(errors='replace')

    BUILD.mkdir(parents=True, exist_ok=True)
    DB_SEED.mkdir(parents=True, exist_ok=True)
    df = build()
    df.to_csv(BUILD / 'reference_line.csv', index=False, encoding='utf-8-sig')
    to_sql(df, DB_SEED / 'S2__reference_line.sql')
    price_index_sql(load_price_index(SEED / 'price_index.csv'), DB_SEED / 'S4__price_index.sql')

    print(f'총 {len(df)}건\n')
    print('[완결성 등급]')
    print(df['data_grade'].value_counts().sort_index().to_string(), '\n')
    print('[유형별 km당 단가 — 회귀 학습 가능 표본]')
    m = df[df['cost_per_km'].notna() & ~df['is_outlier']]
    print(f"기준연도 명시 {(m.base_year_status == 'STATED').sum()}건 /"
          f" 추정 {(m.base_year_status == 'ASSUMED').sum()}건")
    print(m.groupby(['rail_class', 'mode_type'])['cost_per_km']
            .agg(n='count', 평균='mean', 최소='min', 최대='max')
            .round(0).to_string(), '\n')
    print('[유형별 역간격(km) — 운영현황 기준 중앙값]')
    print(spacing_stats(df).to_string())
