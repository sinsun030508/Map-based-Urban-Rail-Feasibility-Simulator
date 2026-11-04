"""광역철도 첨두율 — research-log 「광역철도 첨두율」 의 숫자를 고정한다."""

import pytest

import korail_hourly_peak as K


@pytest.fixture(scope='module')
def ratios():
    return K.peak_ratios(*K.read_lines())


def test_노선별_절을_모두_읽는다(ratios):
    # 하차 줄은 노선명이 비어 온다 — 앞 줄 이름을 못 이으면 노선이 반으로 줄거나 깨진다
    assert len(ratios) == 17
    assert '광역전철 합계' in ratios and '대경선' in ratios


def test_노선망_첨두는_18시_0_0912(ratios):
    peak, ratio, total = ratios['광역전철 합계']
    assert (peak, round(ratio, 4), total) == ('18시', 0.0912, 129_793_286)


def test_노선별_범위(ratios):
    lines = {k: v[1] for k, v in ratios.items() if k != '광역전철 합계'}
    assert round(min(lines.values()), 4) == 0.0838      # 장항선
    assert round(max(lines.values()), 4) == 0.1089      # 과천선


def test_묶음칸은_첨두가_될_수_없다():
    # 「06시 이전」 이 몇 시간 치를 담아도 1시간 첨두로 뽑히면 안 된다
    hours = ['06시 이전', '07시', '24시 이후']
    lines = {'가': {'승차': [900, 100, 800, 1800], '하차': [0, 0, 0, 0]}}
    assert K.peak_ratios(hours, lines)['가'][0] == '07시'
