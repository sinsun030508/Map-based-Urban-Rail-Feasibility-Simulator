"""실습일지 생성기 — A4 2쪽, ReportLab + 맑은 고딕. 1~7호와 같은 틀(색 막대 제목·숫자 칸·표·글머리표).

사용(저장소 루트에서)
  1) 아래 「이번 호」 상수(호수·주차·날짜·지난 일지 날짜)와 `story`(본문)를 고친다 — 본문은 7호 내용이 들어 있어 그대로 틀로 쓴다
  2) PYTHONUTF8=1 python tools/journal/make_journal.py            → docs/실습일지<호수>_노선_타당성_분석_시뮬레이터.pdf
  3) 만든 PDF 를 **눈으로 연다**(쪽 경계에서 블록이 쪼개지는지, 2쪽에 들어가는지) — 내장 브라우저로 `file:///…pdf` 를 열면 설치 없이 보인다

**숫자는 기록에서 뽑는다** — 커밋 수는 `git rev-list --count <지난 일지 커밋>..HEAD`, 테스트 수는 CLAUDE.md 「테스트」 절, 측정값은 docs/engineering-notes.md·research-log.md.
지어내지 말 것(7호도 그렇게 대조했다). 「이번에 한 일」 숫자 칸의 「전 → 후」 의 전은 **지난 일지의 후**다.

**알아 둘 것**
- 7호는 「지금 화면」 대신 **측정값 막대 차트**(`time_chart`)를 넣었다 — 그 주 일이 서버 성능이라서. 지도 화면이 주제면 스크린샷(PIL 로 자른 JPG)을 `RLImage` 로 넣는다(6호 방식).
  이 PC 의 내장 브라우저는 지도 타일이 비어 나오니 실제 화면은 Chrome 으로 찍을 것.
- matplotlib 가 없어 차트는 ReportLab 그래픽(`Drawing`)으로 그린다 — 새 패키지가 필요 없다.
- **윈도우 전용**: 글꼴 경로(`C:/Windows/Fonts/malgun*.ttf`). 다른 OS 면 맑은 고딕 파일 경로를 바꾼다.
"""
from pathlib import Path

from reportlab.graphics.shapes import Drawing, Line, Rect, String
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import KeepTogether, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

ROOT = Path(__file__).resolve().parents[2]

# ── 이번 호 ── 다음 호에는 여기만 고친다
ISSUE = 7                      # 호수
WEEK = 12                      # 심화응용프로젝트 주차
DATE = '2026년 11월 23일'
PREV_DATE = '11월 15일'        # 지난 일지 날짜
OUT = ROOT / 'docs' / f'실습일지{ISSUE}_노선_타당성_분석_시뮬레이터.pdf'

pdfmetrics.registerFont(TTFont('Malgun', r'C:\Windows\Fonts\malgun.ttf'))
pdfmetrics.registerFont(TTFont('MalgunBold', r'C:\Windows\Fonts\malgunbd.ttf'))
pdfmetrics.registerFontFamily('Malgun', normal='Malgun', bold='MalgunBold')

INK = colors.HexColor('#1f2937')
MUTED = colors.HexColor('#6b7280')
LINE = colors.HexColor('#d9dde3')
HEAD = colors.HexColor('#eef2f7')
ACCENT = colors.HexColor('#1a56db')
SOFT = colors.HexColor('#f5f8fd')
BAR_OLD = colors.HexColor('#b8c0cc')
CONTENT_W = 174 * mm

title = ParagraphStyle('t', fontName='MalgunBold', fontSize=16, leading=21, textColor=INK)
sub = ParagraphStyle('s', fontName='Malgun', fontSize=9, leading=13, textColor=MUTED)
h_text = ParagraphStyle('h', fontName='MalgunBold', fontSize=12, leading=15, textColor=INK)
h3 = ParagraphStyle('h3', fontName='MalgunBold', fontSize=10.2, leading=14, textColor=ACCENT, spaceBefore=5, spaceAfter=1)
body = ParagraphStyle('b', fontName='Malgun', fontSize=9.6, leading=14.6, textColor=INK, spaceAfter=2)
bullet = ParagraphStyle('bl', parent=body, leftIndent=11, bulletIndent=2, spaceAfter=1.5)
cap = ParagraphStyle('c', parent=body, fontSize=8.5, leading=12, textColor=MUTED)
cell = ParagraphStyle('cell', parent=body, fontSize=9, leading=12.8, spaceAfter=0)
cellb = ParagraphStyle('cellb', parent=cell, fontName='MalgunBold')
stat_num = ParagraphStyle('sn', fontName='MalgunBold', fontSize=14, leading=17, textColor=ACCENT, alignment=1)
stat_lbl = ParagraphStyle('sl', fontName='Malgun', fontSize=8.5, leading=11, textColor=MUTED, alignment=1)


def P(text, style=body):
    return Paragraph(text, style)


def B(*items):
    return [Paragraph(t, bullet, bulletText='•') for t in items]


def heading(text):
    """제목 왼쪽에 색 막대 — 절이 바뀌는 곳이 눈에 띄게"""
    t = Table([[None, Paragraph(text, h_text)]], colWidths=[2.2 * mm, CONTENT_W - 2.2 * mm])
    t.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (0, 0), ACCENT),
        ('LEFTPADDING', (0, 0), (0, 0), 0), ('RIGHTPADDING', (0, 0), (0, 0), 0),
        ('LEFTPADDING', (1, 0), (1, 0), 7),
        ('TOPPADDING', (0, 0), (-1, -1), 1), ('BOTTOMPADDING', (0, 0), (-1, -1), 1),
        ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
    ]))
    return [Spacer(1, 7), t, Spacer(1, 4)]


def table(rows, widths, first_bold=True):
    data = []
    for i, r in enumerate(rows):
        data.append([P(c, cellb if i == 0 or (j == 0 and first_bold) else cell) for j, c in enumerate(r)])
    t = Table(data, colWidths=widths)
    t.setStyle(TableStyle([
        ('GRID', (0, 0), (-1, -1), 0.5, LINE),
        ('BACKGROUND', (0, 0), (-1, 0), HEAD),
        ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
        ('LEFTPADDING', (0, 0), (-1, -1), 6), ('RIGHTPADDING', (0, 0), (-1, -1), 6),
        ('TOPPADDING', (0, 0), (-1, -1), 3.5), ('BOTTOMPADDING', (0, 0), (-1, -1), 3.5),
    ]))
    return t


def stats(items):
    """숫자 칸 — 큰 숫자 + 작은 이름"""
    w = CONTENT_W / len(items)
    t = Table([[P(n, stat_num) for n, _ in items], [P(l, stat_lbl) for _, l in items]], colWidths=[w] * len(items))
    t.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, -1), SOFT),
        ('LINEAFTER', (0, 0), (-2, -1), 0.5, LINE),
        ('TOPPADDING', (0, 0), (-1, 0), 5), ('BOTTOMPADDING', (0, -1), (-1, -1), 5),
        ('TOPPADDING', (0, 1), (-1, 1), 0),
    ]))
    return t


def time_chart():
    """최악 노선(서울 지그재그 981km, 점 50개, 역 7,475행) 계산 한 건의 걸린 시간 — 이번 주에 잰 값만"""
    rows = [('처음', 19.3), ('① 역 저장을 JDBC 배치로', 6.7), ('② 거리 계산의 삼각함수 제거', 2.3), ('③ 경로 탐색을 이분 탐색으로', 1.7)]
    label_w, bar_max_w, bar_h, gap = 52 * mm, 96 * mm, 9 * mm, 4.5 * mm
    height = len(rows) * (bar_h + gap) + 4 * mm
    d = Drawing(CONTENT_W, height)
    vmax = rows[0][1]
    y = height - bar_h - 2 * mm
    for i, (name, sec) in enumerate(rows):
        last = i == len(rows) - 1
        w = max(bar_max_w * sec / vmax, 1.2 * mm)
        d.add(String(label_w - 3 * mm, y + 2.6 * mm, name, fontName='MalgunBold' if last else 'Malgun', fontSize=9, fillColor=INK, textAnchor='end'))
        d.add(Rect(label_w, y, w, bar_h, fillColor=ACCENT if last else BAR_OLD, strokeColor=None))
        d.add(String(label_w + w + 2.5 * mm, y + 2.6 * mm, f'{sec:.1f}초', fontName='MalgunBold', fontSize=9.5, fillColor=ACCENT if last else INK))
        y -= bar_h + gap
    d.add(Line(label_w, 0, label_w, height - 1 * mm, strokeColor=LINE, strokeWidth=0.8))
    return d


story = [
    P('실습일지 — 지도 기반 노선 타당성 분석 시뮬레이터', title),
    P('컴퓨터시스템공학과 3학년 2학기 · 202245079 신선호', sub),
    P(f'심화응용프로젝트 {WEEK}주차 · {DATE} (지난 일지 {PREV_DATE} 이후)', sub),

    *heading('1. 이번 주 핵심 — 가장 무거운 노선의 계산 시간'),
    time_chart(),
    Spacer(1, 2),
    P('서울 도심을 지그재그로 그린 <b>981km·점 50개·역 7,475개</b> 노선 한 건의 계산 시간(이번 주 실측). <b>결과는 한 글자도 바뀌지 않았습니다</b> — '
      '노선 24개의 응답 5.7MB 를 바꾸기 전과 바이트 단위로 비교해 모두 같았습니다.', cap),

    *heading('2. 이번에 한 일'),
    stats([('44개', '커밋'), ('315 → 322', 'ETL 테스트'), ('260 → 348', '백엔드 테스트'), ('80 → 119', '프론트엔드 테스트')]),
    Spacer(1, 5),
    table([
        ['과제', '결말'],
        ['서버 오류 3건', '실제 서버에 동시 요청을 보내 <b>500 오류 세 가지</b>를 찾아 고침 — 단위 테스트로는 안 보이던 것'],
        ['계산 속도', '최악 노선 <b>19.3 → 1.7초</b>. 추정이 아니라 <b>프로파일로 재서</b> 병목을 찾음'],
        ['도로 따라가기', '물만 새 다리로 잇는 혼합 구간, 바다 위 다리값, 새 도로의 <b>까닭을 패널에 표시</b>, 문턱 1.5 → 2.0배'],
        ['추천 규칙', '역간격보다 짧은 노선에서는 광역급행을 추천하지 않음 — 서울~부산 B/C 0.69 → 0.56 의 <b>원인 확정</b>'],
        ['보안', '백엔드 취약점 <b>111건 → 2건</b>(Spring Boot 3.3.4 → 3.5.16), 지도 팝업의 XSS 경로 제거'],
        ['남은 것', '소셜 로그인 왕복 재확인(시큐리티 6.5), 복선전철 수요 하한(KRIC 키), 운영비 근거, EC2 업로드'],
    ], [32 * mm, CONTENT_W - 32 * mm]),

    *heading('3. 주요 작업'),
    P('① 실제 서버에 부딪혀 봐야 나오는 결함', h3),
    *B('같은 시나리오를 <b>동시에 계산</b>하면 8개 중 7개가 500 이었습니다 — DB 가 서로의 삭제·삽입을 기다리는 <b>교착</b>이었습니다',
       '같은 시나리오를 <b>동시에 수정</b>해도 4개 중 3개가 500 → 쓰기의 첫 문장에서 그 행을 잠가(<b>FOR UPDATE</b>) 순서대로 처리하게 함 → 전부 200',
       '한 사용자가 최악 계산 30개를 동시에 보내면 10개가 500 이고 <b>다른 사람의 목록이 51초</b> 막혔습니다 → 동시 계산 한도(사용자당 2개)를 두고 넘으면 즉시 429, 목록은 0.2초'),
    P('② 추정하지 않고 재서 고치기', h3),
    *B('저장이 <b>12.9초 → 0.4초</b>: 역 7,475행을 한 행씩 저장하던 것(키 방식 때문에 묶어 보낼 수 없었음)을 JDBC 묶음 저장으로',
       '계산 CPU 가 느린 이유를 도로 탐색이라고 <b>추정했는데 틀렸습니다</b> — 프로파일(JFR)로 재니 <b>거리 계산의 삼각함수가 57%</b>, 도로 탐색은 4% 였습니다',
       '필요한 건 거리가 아니라 「반경 안인가」 뿐이라 삼각함수 없이 같은 답을 내는 판정으로 바꿈 (6.7 → 2.3초)'),
    KeepTogether([P('③ 바꾸기 전에 정답지를 만들기', h3),
    *B('저장 방식처럼 결과가 <b>같아야 하는</b> 변경은 바꾸기 전에 노선 24개의 응답을 모아 두고, 바꾼 뒤 바이트 단위로 비교했습니다 — 모두 동일',
       '테스트가 일을 하는지 보려고 안전장치를 <b>일부러 망가뜨렸더니</b> 테스트가 통과했습니다 → 경계를 정확히 겨냥하는 테스트를 더함',
       '마지막에 <b>새 눈으로 코드 리뷰</b>를 돌려 결함 2건을 더 찾았습니다 (짧은 노선에서 지도가 비는 것, 꼭짓점 1개 경로의 예외)')]),

    KeepTogether([
        *heading('4. 정한 규칙과 이유'),
        table([
            ['항목', '기준'],
            ['계산 동시 한도', '<b>사용자당 2개</b>, 전체는 CPU 코어의 절반(최소 2). 넘으면 기다리게 하지 않고 <b>즉시 429</b> (판단값)'],
            ['추천 범위', '권장 연장 이하, <b>역간격 이상</b>인 수단만 기본으로 내세움. 모두 벗어나면 범위를 무시 (서버·표·지도가 같은 규칙)'],
            ['쓰기 순서', '시나리오 행 <b>잠금 → 읽기 → 저장</b>. 잠근 뒤에 읽어야 앞 요청이 저장한 결과를 봄'],
        ], [32 * mm, CONTENT_W - 32 * mm]),
    ]),
    Spacer(1, 5),
    table([
        ['상황', '전', '지금'],
        ['최악 노선 저장', '<b>12.9초</b> (역 7,475행)', '<b>0.4초</b>'],
        ['서울~부산 계산', '2.6초', '<b>0.7초</b>'],
        ['최악 계산 30개 동시', '200 20개 · <b>500 10개</b>, 목록 조회 최대 51초', '200 2개 · <b>429 28개</b>, 목록 조회 0.2초'],
        ['백엔드 취약점', '24개 라이브러리 <b>111건</b>', '1개 <b>2건</b> (수정 버전이 아직 없음)'],
    ], [38 * mm, 70 * mm, CONTENT_W - 108 * mm]),
    P('한계', h3),
    *B('동시 한도는 <b>한 서버 안</b>에서만 셉니다 — 서버가 여러 대가 되면 서버마다 따로입니다',
       '짧은 노선에서 광역급행을 내세우지 않을 뿐, 표에는 <b>부풀려진 B/C 가 그대로</b> 남습니다',
       '소셜 로그인은 보안 라이브러리가 바뀐 뒤 <b>실제 왕복을 아직 못 해 봤습니다</b> — 배포 전에 눌러 봐야 합니다'),

    *heading('5. 느낀 점'),
    *B('<b>테스트가 통과해도 실제 서버에서는 깨집니다.</b> 동시 요청을 보내 보니 단위 테스트가 못 보던 500 이 세 가지나 나왔습니다',
       '<b>추정은 틀릴 수 있습니다.</b> 도로 탐색이 느릴 거라 믿었지만 측정하니 삼각함수였고, 재기 전에 고쳤다면 엉뚱한 곳을 손볼 뻔했습니다',
       '<b>바꾸기 전에 정답지를 만들어 둬야 합니다.</b> 그래야 「빨라졌다」 가 「같은 결과로 빨라졌다」 가 됩니다',
       '<b>내가 쓴 코드는 내 눈에 안 보이는 곳이 있습니다.</b> 서버에 규칙을 넣고 화면의 같은 규칙을 빼먹은 것을 새 눈 리뷰가 찾았습니다'),
]


def page_number(canvas, doc):
    canvas.setFont('Malgun', 9)
    canvas.setFillColor(MUTED)
    canvas.drawString(18 * mm, A4[1] - 11 * mm, str(doc.page))


doc = SimpleDocTemplate(str(OUT), pagesize=A4, leftMargin=18 * mm, rightMargin=18 * mm, topMargin=16 * mm, bottomMargin=13 * mm,
                        title=f'실습일지 {ISSUE} — 노선 타당성 분석 시뮬레이터', author='신선호')
doc.build(story, onFirstPage=page_number, onLaterPages=page_number)
print(OUT)
