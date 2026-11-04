"""
광역철도 첨두율 — 코레일 「광역철도 시간대별 승하차인원」 (월 합계) 에서 첨두 1시간 비중을 잰다.

자료
  `data/raw/korail_hourly_2026_09/(2026년9월) 광역철도 시간대별 승하차인원(홈페이지).xlsx`
  (data.go.kr 15154382 → 코레일 정보공개 게시판). 시트 하나, ① 노선별 ② 역별.
  **방향이 없다**(개찰구 태그 기준) — 그래서 복선전철 수요 하한에는 못 쓴다(research-log).

기준 맞추기
  `seoul_peak.py`·`seoul_weekday_peak.py` 와 같이 **(승차+하차)** 로 세고, 첨두는 1시간 칸
  중 최댓값이다. 「06시 이전」·「24시 이후」 는 여러 시간을 묶은 칸이라 첨두 후보에서 뺀다.

**모델 값(0.1159)과 견줄 수 없다.** 그것은 평일 21일의 평균이고 이것은 주말·공휴일이 섞인
월 합계다. 견줄 것은 서울의 월 기준 0.0987 이다. 그리고 2026-09 에는 추석 연휴가 있다 —
평일 공휴일은 첨두를 뭉개므로 이 값은 낮은 쪽으로 치우칠 수 있다.

    python etl/korail_hourly_peak.py
"""

from pathlib import Path

import openpyxl

ROOT = Path(__file__).resolve().parent.parent
FILE = (ROOT / 'data' / 'raw' / 'korail_hourly_2026_09'
        / '(2026년9월) 광역철도 시간대별 승하차인원(홈페이지).xlsx')
LUMPED = ('06시 이전', '24시 이후')     # 여러 시간을 묶은 칸 — 첨두 후보가 아니다


def read_lines(path=FILE):
    """① 노선별 절 → {노선명: {'승차': [...], '하차': [...]}} (시간칸 순서, 끝은 합계)

    노선명은 승차 줄에만 있고 하차 줄은 비어 온다(병합 셀).
    """
    rows = list(openpyxl.load_workbook(path, read_only=True, data_only=True)
                .worksheets[0].iter_rows(values_only=True))
    start = next(i for i, r in enumerate(rows)
                 if any(isinstance(c, str) and c.strip().startswith('1)') for c in r))
    end = next(i for i, r in enumerate(rows)
               if any(isinstance(c, str) and c.strip().startswith('2)') for c in r))
    head = [c for c in rows[start + 1] if c is not None]
    hours = head[2:-1]
    lines, name = {}, None
    for r in rows[start + 2:end]:
        v = [c for c in r if c is not None]
        if not v:
            continue
        if v[1] in ('승차', '하차'):
            name, kind, nums = v[0], v[1], v[2:]
        else:
            kind, nums = v[0], v[1:]
        lines.setdefault(name, {})[kind] = nums
    return hours, lines


def peak_ratios(hours, lines):
    """노선마다 (첨두 시각, 첨두 1시간 비중, 월 합계). 시간칸 합이 합계와 다르면 멈춘다"""
    out = {}
    for name, d in lines.items():
        both = [a + b for a, b in zip(d['승차'], d['하차'])]
        hourly, total = both[:-1], both[-1]
        if sum(hourly) != total:
            raise SystemExit(f'{name}: 시간칸 합 {sum(hourly)} ≠ 합계 {total} — 표가 바뀌었다')
        one = {h: x for h, x in zip(hours, hourly) if h not in LUMPED}
        peak = max(one, key=one.get)
        out[name] = (peak, one[peak] / total, total)
    return out


def main():
    hours, lines = read_lines()
    for name, (peak, ratio, total) in peak_ratios(hours, lines).items():
        print(f'{name:8s} 월 {total:>11,}  첨두 {peak} {ratio:.4f}')


if __name__ == '__main__':
    main()
