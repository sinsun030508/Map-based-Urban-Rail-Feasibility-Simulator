"""
노선 이용자 ÷ 역 승하차 합 — 편익의 `users_per_ridership`(S7)을 잰다.

왜 필요한가
  수요 모델의 「이용객」 은 역 승차 + 하차의 합이라 한 통행을 두 번 센다. 편익은 통행마다 아낀 시간이므로 **노선 이용자**
  (승차 + 환승유입, 서울교통공사의 「수송인원」)로 바꿔 곱해야 한다(research-log 「승하차 합은 통행 두 번이다」).

입력  공공데이터포털 파일데이터 (원본은 data/cache/regional 에 둔다 — regional_ridership.download 를 같이 쓴다)
  15104835  서울교통공사_1_8호선 역별 일별 승객유형별 수송인원(환승유입인원 포함) — 2025, 승차·환승유입
  15048032  서울교통공사_역별 일별 시간대별 승하차인원 — 2024, 승차·하차(가정 검증용)
출력  data/build/line_users_ratio.csv   line, boardings, transfer_in, ratio, alight_over_board

재는 법
  ratio = (승차 + 환승유입) ÷ (승차 × 2). 분모의 「× 2」 는 **하차 ≈ 승차** 라고 본 것이다 — 첫 파일에는 하차가 없다.
  그 가정은 둘째 파일(한 해 앞)로 노선마다 하차 ÷ 승차를 재 확인한다(0.98~1.03 이면 ±3% 안).
  **한 출처·한 해** 로 잰다 — 11-08 처음 넣은 0.745 는 보도된 2024 수송인원을 열린데이터광장 API 의 2026 승하차로 나눈 값이었다.

    python etl/seoul_line_users.py
"""

import sys
from pathlib import Path

import pandas as pd

import regional_ridership as R

OUT = Path(__file__).resolve().parent.parent / 'data' / 'build' / 'line_users_ratio.csv'


def line_ratios(df):
    g = df.groupby('호선')[['승차인원', '환승유입인원']].sum().reset_index()
    g['ratio'] = (g.승차인원 + g.환승유입인원) / (2 * g.승차인원)
    return g


def alight_over_board(df):
    hours = [c for c in df.columns if '시' in c and c != '수송일자']
    total = df[hours].apply(pd.to_numeric, errors='coerce').sum(axis=1)
    pivot = total.groupby([df['호선'], df['승하차구분']]).sum().unstack()
    return (pivot['하차'] / pivot['승차']).to_dict()


def main():
    users = line_ratios(pd.read_csv(R.download('15104835')[0], encoding='cp949'))
    check = alight_over_board(pd.read_csv(R.download('15048032')[0], encoding='cp949'))
    out = pd.DataFrame({'line': users.호선, 'boardings': users.승차인원, 'transfer_in': users.환승유입인원,
                        'ratio': users.ratio.round(4), 'alight_over_board': users.호선.map(check).round(4)})
    out.to_csv(OUT, index=False, encoding='utf-8')
    print(out.to_string(index=False))
    print(f'\n중앙값 {out.ratio.median():.4f} — S7 users_per_ridership 에 쓴다. 하차/승차 {out.alight_over_board.min():.3f}~'
          f'{out.alight_over_board.max():.3f}')
    print(f'{OUT} 생성')


if __name__ == '__main__':
    sys.stdout.reconfigure(errors='replace')
    main()
