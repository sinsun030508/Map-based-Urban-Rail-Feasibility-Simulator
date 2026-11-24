# 실습일지 생성기

`make_journal.py` — A4 2쪽, ReportLab + 맑은 고딕. 1~7호와 같은 틀(색 막대 제목·숫자 칸·표·글머리표).
다음 호는 파일 위쪽 「이번 호」 상수(호수·주차·날짜·지난 일지 날짜)와 `story`(본문)만 고쳐 실행한다.
**숫자는 기록에서 뽑고 지어내지 않는다**(커밋 수는 `git rev-list --count`, 테스트 수는 CLAUDE.md, 측정값은 `docs/engineering-notes.md`).
만든 PDF 는 **눈으로 열어 본다**(내장 브라우저로 `file:///…pdf`). 자세한 사용법은 파일 머리 설명.
