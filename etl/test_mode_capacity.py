"""S1 의 권장 연장(`max_length_km`)이 근거 자료와 맞는지 본다.

중전철 상한이 35km 인 채로 "권장 연장" 이라 불렸는데, 실제 운영 중인 중전철 21개 노선 중
9개가 35km 를 넘었다. 근거를 묻지 않고 처음 넣은 값이 경고·추천·KTX 판정 세 곳에 쓰이고
있었다. 지금은 수요 하한(실제 운영 노선의 최저 단면)과 같은 규칙으로 **실제 운영 노선의 최장
연장**을 쓰고, 역간격처럼 **운영현황 한 출처만** 쓴다(S1 주석 「max_length_km」).
자료를 다시 만들어 최장이 바뀌었는데 S1 을 안 고치면 여기서 깨진다.
"""

import re
from pathlib import Path

import pandas as pd
import pytest

ROOT = Path(__file__).resolve().parent.parent
S1 = ROOT / 'db' / 'seed' / 'S1__mode_capacity.sql'
REFERENCE = ROOT / 'data' / 'build' / 'reference_line.csv'
OPS = '국토교통부 도시철도 운영현황'

# INSERT 한 행: ('MODE', '표시명', 숫자·NULL 8개, '출처')
# 순서는 pphpd_min, pphpd_max, cost_min, cost_max, spacing_km, speed_kmh, max_length_km
ROW = re.compile(r"\('([A-Z_]+)'\s*,\s*'[^']*'\s*,((?:\s*(?:NULL|[0-9.]+)\s*,){7})")


def s1_max_length():
    out = {}
    for m in ROW.finditer(S1.read_text(encoding='utf-8')):
        value = [v.strip() for v in m.group(2).split(',')][6]
        out[m.group(1)] = None if value == 'NULL' else float(value)
    return out


def ops_longest():
    df = pd.read_csv(REFERENCE)
    ops = df[(df['source_name'] == OPS) & ~df['is_outlier']]
    return ops.groupby('mode_type')['length_km'].max().to_dict()


def test_시드_행을_모두_읽는다():
    # 정규식이 행을 놓치면 아래 비교가 빈 값끼리 맞아 통과한다
    assert set(s1_max_length()) == {'HEAVY_METRO', 'LIGHT_RAIL', 'TRAM', 'BRT_HIGH', 'BRT_LOW',
                                    'DOUBLE_ELEC', 'EXPRESS_RAIL', 'SINGLE_ELEC', 'UPGRADE'}


@pytest.mark.parametrize('mode', ['HEAVY_METRO', 'LIGHT_RAIL'])
def test_도시철도_상한은_운영현황_최장_연장이다(mode):
    assert s1_max_length()[mode] == pytest.approx(ops_longest()[mode]), (
        f'{mode} 권장 연장이 운영현황 최장과 다릅니다 — 자료가 바뀌었으면 S1 과 그 주석을 함께 고칠 것')


def test_도시_간_수단에는_상한이_없다():
    # 상한이 생기면 장거리 노선에서 모든 수단이 「권장 연장 초과」 가 되고, 추천은 길이를
    # 따지지 않는 쪽으로 물러나(`CostCalculator.recommended`) 340km BRT 가 다시 뽑힌다
    s1 = s1_max_length()
    assert [m for m in ('DOUBLE_ELEC', 'EXPRESS_RAIL') if s1[m] is not None] == []
