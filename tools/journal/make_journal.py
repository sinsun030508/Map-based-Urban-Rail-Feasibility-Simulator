"""실습일지 생성기 — A4 2쪽, ReportLab + 맑은 고딕. 1~7호와 같은 틀(색 막대 제목·숫자 칸·표·글머리표).

사용(저장소 루트에서)
  1) 아래 「이번 호」 상수(호수·주차·날짜·지난 일지 날짜)와 `story`(본문)를 고친다 — 본문은 8호 내용이 들어 있어 그대로 틀로 쓴다(7호는 git 기록에)
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
ISSUE = 8                      # 호수
WEEK = 13                      # 심화응용프로젝트 주차
DATE = '2026년 11월 26일'
PREV_DATE = '11월 23일'        # 지난 일지 날짜
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

    *heading('1. 이번 주 핵심 — 서버를 올리기 전에 직접 공격해 보기'),
    P('이 PC 에 띄운 서버를 공격자 입장에서 <b>46가지</b>로 두드렸습니다. <b>41가지는 이미 막혀 있었고</b>, 뚫린 5가지 중 1가지(API 문서 경로)는 '
      '이 PC 의 설정 때문이라 배포에서는 닫혀 있습니다. 남은 실제 약점 넷 중 <b>셋을 고쳤습니다</b>.'),
    Spacer(1, 3),
    table([
        ['분류', '시도한 공격', '결과'],
        ['인증', '서명 없는 토큰(alg:none), 토큰 속 사용자 바꾸기, 흔한 비밀키로 위조, 지워진 계정의 토큰', '모두 막힘'],
        ['권한', '남의 시나리오 읽기·고치기·계산·삭제, 가입 본문에 관리자 권한 끼워 넣기, 일반 회원의 관리자 API', '모두 막힘'],
        ['주입', '로그인·인구 API 에 SQL 주입, 제목에 HTML·SQL', '모두 막힘 (글자 그대로 저장)'],
        ['남용', '비밀번호 무차별 대입, 주소 위조로 잠금 우회, 계산 동시 폭주, <b>가입 15번 연속</b>', '가입만 뚫림 → <b>고침</b>'],
        ['노출', '<b>응답 시간으로 계정 존재 알아내기</b>, 가입 409 로 가입 여부 알아내기', '응답 시간 → <b>고침</b> / 409 는 남음'],
        ['설정', 'CORS(다른 사이트에서 호출), <b>보안 헤더</b>', '헤더 없음 → <b>고침</b>'],
    ], [17 * mm, 112 * mm, CONTENT_W - 129 * mm]),
    P('남은 약점 하나는 <b>HTTPS</b> 입니다. 도메인과 인증서가 있어야 해서 배포할 때 붙입니다(배포 문서에 적어 둠).', cap),

    *heading('2. 이번에 한 일'),
    stats([('4개', '커밋'), ('322 → 322', 'ETL 테스트'), ('348 → 358', '백엔드 테스트'), ('119 → 123', '프론트엔드 테스트')]),
    Spacer(1, 5),
    table([
        ['과제', '결말'],
        ['보안 점검', '공격 46가지 → 약점 셋 고침: <b>로그인 응답 시간</b>, <b>가입 횟수 제한</b>, <b>nginx 보안 헤더·속도 제한</b>'],
        ['코드 리뷰', '결함 2건: 계산 한도 <b>환경 변수가 안 먹던 설정 키</b>, 수송능력 초과 안을 <b>화면만 추천</b>하던 것'],
        ['덤으로 찾은 것', 'VWorld 키 없이 배포하면 <b>웹 서버가 아예 안 켜지던</b> 설정 오류 — 실제 배포 이미지로 띄워 보다 발견'],
        ['이면도로', '도로망에 골목길까지 넣기 전 규모 측정 — 지도 서버(Overpass)가 <b>502 로 거절</b>해 못 잼, 착수 전 확인 목록을 남김'],
        ['남은 것', '가입 409(이메일 인증 필요), HTTPS(도메인), 복선전철 수요 하한(KRIC 키), 운영비 근거, EC2 업로드'],
    ], [32 * mm, CONTENT_W - 32 * mm]),

    *heading('3. 주요 작업'),
    P('① 시간으로 새는 정보', h3),
    *B('없는 계정과 틀린 비밀번호의 <b>문구는 같았습니다</b>. 그런데 없는 계정은 비밀번호 비교(일부러 느린 bcrypt)를 건너뛰어 <b>36ms</b>, 있는 계정은 <b>280ms</b> 였습니다',
       '문구를 안 봐도 <b>시간만 재면</b> 그 이메일이 가입했는지 알 수 있었습니다 → 없는 계정도 가짜 해시와 한 번 비교해 같은 시간을 쓰게 함 → <b>282ms 대 293ms</b>',
       '시간은 실행마다 흔들려 테스트로 잡기 어렵습니다 → 테스트는 시간 대신 <b>비교를 몇 번 했는지</b>를 셉니다'),
    KeepTogether([P('② 막는 장치를 두 겹으로', h3),
    *B('<b>백엔드</b>: 가입을 접속 주소마다 한 시간에 10번까지. 15번 보내면 10번 가입, 나머지는 429',
       '<b>nginx(앞문)</b>: 접속 주소마다 API·로그인·지도 타일을 따로 셉니다. API 120번을 몰아치면 63번만 통과. 다른 사이트가 우리 화면을 끼워 넣지 못하게 하는 헤더와 CSP 도 붙임',
       'CSP 는 잘못 쓰면 <b>지도가 깨집니다</b> → 배포 이미지를 실제로 띄워 로그인·지도·저장·계산·인구 3D 까지 눌러 보고 <b>위반 0건</b>을 확인')]),
    KeepTogether([P('③ 코드 리뷰 — 이름 하나, 규칙 하나의 어긋남', h3),
    *B('계산 한도를 환경 변수로 바꾸게 해 놓고 코드는 <b>다른 이름의 설정 키</b>를 읽고 있어, 무엇을 넣어도 늘 기본값이었습니다. 기존 테스트는 객체를 직접 만들어 이 연결을 못 봤습니다 → <b>설정 파일을 실제로 읽는</b> 테스트를 붙임',
       '서버 추천은 수송능력을 넘는 안을 빼는데 <b>지도·비교표는 안 빼</b>, B/C 1위가 수송능력 초과인 노선에서 화면과 서버의 추천이 달랐습니다 → 두 곳이 한 판정 함수를 함께 쓰게 함')]),

    KeepTogether([
        *heading('4. 정한 규칙과 이유'),
        table([
            ['항목', '기준'],
            ['가입 횟수', '접속 주소마다 <b>한 시간에 10번</b> (판단값). 중복 이메일(409)도 한 번으로 셈 — 가입 여부를 캐는 속도를 늦춤'],
            ['로그인 비교', '없는 계정·소셜 계정도 <b>비밀번호 비교를 꼭 한 번</b> — 세 경우가 같은 일을 해야 시간이 같음'],
            ['막혔을 때', '가입·로그인·nginx 모두 <b>429</b> 와 「언제 다시 하면 되는지」 문구. 보조 장치(Redis)가 죽으면 <b>막지 않음</b>'],
        ], [32 * mm, CONTENT_W - 32 * mm]),
    ]),
    Spacer(1, 5),
    table([
        ['상황', '전', '지금'],
        ['로그인 응답 (없는 / 있는 계정)', '36ms / <b>280ms</b>', '282ms / <b>293ms</b>'],
        ['가입 15번 연속', '<b>15번 모두 가입</b> (4.4초)', '10번 가입 · <b>429 5번</b>'],
        ['API 120번 몰아치기 (nginx)', '제한 없음', '200 63번 · <b>429 57번</b>'],
        ['보안 헤더', '없음, nginx 버전 노출', '<b>5종</b> + 버전 숨김'],
        ['계산 한도 환경 변수', '바꿔도 <b>늘 기본값</b>', '설정대로'],
    ], [48 * mm, 60 * mm, CONTENT_W - 108 * mm]),
    P('한계', h3),
    *B('가입 409(「이미 가입된 이메일」) 응답은 <b>그대로</b>입니다 — 없애려면 가입 메일로 확인하는 <b>이메일 인증</b>이 있어야 합니다',
       '횟수 제한은 <b>접속 주소</b> 단위라 주소를 여러 개 가진 공격자는 그만큼 더 시도할 수 있습니다',
       '<b>HTTPS 가 없어</b> 로그인 토큰이 평문으로 오갑니다 — 공개 운영 전에 반드시 붙여야 합니다'),

    *heading('5. 느낀 점'),
    *B('<b>공격자 입장에서 직접 두드려 봐야 보입니다.</b> 기능 테스트는 「되는가」 만 보고 「새는가」 는 보지 않았습니다',
       '<b>문구가 같아도 정보는 샙니다.</b> 0.25초 차이만으로 가입 여부가 드러난다는 것을 재 보고서야 알았습니다',
       '<b>설정은 실제로 띄워 봐야 합니다.</b> 키 없이 배포하면 웹 서버가 안 켜지는 결함은 문서만 봐서는 몰랐고, 이미지를 띄우자 바로 나왔습니다',
       '<b>같은 규칙이 두 곳에 있으면 언젠가 어긋납니다.</b> 서버와 화면이 판정 함수 하나를 함께 쓰게 하는 것이 가장 확실했습니다'),
]


def page_number(canvas, doc):
    canvas.setFont('Malgun', 9)
    canvas.setFillColor(MUTED)
    canvas.drawString(18 * mm, A4[1] - 11 * mm, str(doc.page))


doc = SimpleDocTemplate(str(OUT), pagesize=A4, leftMargin=18 * mm, rightMargin=18 * mm, topMargin=16 * mm, bottomMargin=13 * mm,
                        title=f'실습일지 {ISSUE} — 노선 타당성 분석 시뮬레이터', author='신선호')
doc.build(story, onFirstPage=page_number, onLaterPages=page_number)
print(OUT)
