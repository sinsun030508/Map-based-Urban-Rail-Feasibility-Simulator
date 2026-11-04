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
월 합계다. 견줄 것은 서울의 월 기준 0.0987 이다. **평일 공휴일은 첨두를 뭉갠다** — 2025년 열두
달로 재 보니 공휴일 없는 달 평균 0.0952, 있는 달 0.0917 이다. 2026-09(추석)의 0.0912 도 그쪽이다.

    python etl/korail_hourly_peak.py
"""

from functools import lru_cache
from pathlib import Path

import openpyxl

ROOT = Path(__file__).resolve().parent.parent
FILE = (ROOT / 'data' / 'raw' / 'korail_hourly_2026_09'
        / '(2026년9월) 광역철도 시간대별 승하차인원(홈페이지).xlsx')
LUMPED = ('06시 이전', '24시 이후')     # 여러 시간을 묶은 칸 — 첨두 후보가 아니다
# 2025년 1~12월 — 시트가 달마다 하나다
FILE_2025 = (ROOT / 'data' / 'raw' / 'korail_hourly_2025'
             / '(2025년12월) 광역철도 시간대별 승하차인원(홈페이지).xlsx')
# 2025년에 평일 공휴일이 없는 달 (설 1월 · 대체 3월 · 어린이날 5월 · 대선·현충일 6월 · 광복절 8월 ·
# 개천절·추석·한글날 10월 · 성탄절 12월). 서울 첨두율(2024-11)도 공휴일 없는 달로 쟀다
NO_HOLIDAY_2025 = (2, 4, 7, 9, 11)


@lru_cache(maxsize=None)
def _book(path):
    # 한 해 치 판(1.6MB)을 달마다 다시 열면 열두 번 연다 — 테스트까지 합쳐 2분을 넘겼다
    return openpyxl.load_workbook(path, read_only=True, data_only=True)


def _section1(path, sheet):
    """① 노선별 절이 끝나는 「2)」 줄까지만 읽는다 — ② 역별 절(650줄)은 쓰지 않는다"""
    rows = []
    for r in _book(path).worksheets[sheet].iter_rows(values_only=True):
        rows.append(r)
        if any(isinstance(c, str) and c.strip().startswith('2)') for c in r):
            break
    return rows


def read_lines(path=FILE, sheet=0):
    """① 노선별 절 → {노선명: {'승차': [...], '하차': [...]}} (시간칸 순서, 끝은 합계)

    `sheet` — 한 해 치 판은 시트가 달마다 하나다(2025년 12월 판 = 1~12월 열두 장).

    노선명은 승차 줄에만 있고 하차 줄은 비어 온다(병합 셀).
    """
    rows = _section1(Path(path), sheet)
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


def network_by_month_2025():
    """{달: 광역전철 합계의 첨두 1시간 비중}"""
    return {m: peak_ratios(*read_lines(FILE_2025, m - 1))['광역전철 합계'][1] for m in range(1, 13)}


def main():
    hours, lines = read_lines()
    for name, (peak, ratio, total) in peak_ratios(hours, lines).items():
        print(f'{name:8s} 월 {total:>11,}  첨두 {peak} {ratio:.4f}')
    print('\n2025년 달별 (광역전철 합계, * 평일 공휴일 없음)')
    months = network_by_month_2025()
    for m, r in months.items():
        print(f'  {m:2d}월{"*" if m in NO_HOLIDAY_2025 else " "} {r:.4f}')
    clean = [months[m] for m in NO_HOLIDAY_2025]
    print(f'공휴일 없는 달 평균 {sum(clean) / len(clean):.4f}')


if __name__ == '__main__':
    main()
