"""
`CLAUDE.md` 가 인용한 숫자가 코드·자료와 맞는지 본다.

**왜 필요한가.** 이 프로젝트가 되풀이해 겪은 사고가 "같은 값이 두 곳에 있고 한 곳만
바뀌는 것" 이다 — `cost_model.SPACING` 이 S1 과 4.98 vs 4.30 으로 어긋났고,
`mode_speed_check` 에 급행 속도 90.14 가 손으로 박혀 있었다. 문서도 똑같다.
**CLAUDE.md 는 보고서의 바탕이라 틀린 숫자가 그대로 옮겨 적힌다.**

하루에 회귀식(16개 -> 18개)과 복선전철 역간격이 바뀌면서 CLAUDE.md·research-log·S1
주석의 숫자를 열 곳 넘게 고쳐야 했다. 하나라도 빠뜨리면 조용히 남는다.

**이 테스트가 깨지는 두 경우를 가려서 읽을 것.**

1. 값이 바뀌었는데 문서를 안 고쳤다 → **문서를 고친다**
2. 문서의 문장을 다듬어 정규식이 못 찾는다 → **정규식을 고친다.** 다만 그때
   숫자도 함께 바뀌지 않았는지 확인할 것 — 그래서 일부러 "못 찾으면 실패" 로 둔다

    cd etl && python -m pytest test_docs_match_code.py
"""

import re
import statistics as st
from pathlib import Path

import pytest

import cost_model as C
import mode_speed_check as M

CLAUDE = (Path(__file__).resolve().parent.parent / 'CLAUDE.md').read_text(encoding='utf-8')


def one(pattern, what):
    """CLAUDE.md 에서 값을 뽑는다. 못 찾으면 실패시킨다.

    **여러 곳에 있어도 되지만 전부 같아야 한다.** 처음엔 "한 곳에만 있어야 한다"로
    두었는데 그게 너무 셌다 — 기술 절과 「보고서에 명시할 한계」 절에 같은 숫자가
    나오는 것은 자연스럽다. 금지할 일이 아니라 **어긋나는지를 볼 일**이다.
    (실제로 한계 절을 다시 쓰다가 이 테스트에 걸렸고, 그래서 규칙을 고쳤다.)
    """
    hits = re.findall(pattern, CLAUDE)
    assert hits, f'CLAUDE.md 에서 {what} 을 못 찾았다 — 문장이 바뀌었으면 정규식도 고칠 것'
    assert len(set(hits)) == 1, (
        f'{what} 이 {len(hits)}곳에 서로 다르게 적혀 있다 — 한 곳만 고친 것이다: {sorted(set(hits))}')
    return hits[0]


class Test수단기준값:
    """CLAUDE.md 의 「추천 기준값」이 S1 과 같은 값을 말하는지."""

    @pytest.mark.parametrize('code,spacing,speed', [
        ('HEAVY_METRO', 1.06, 32.4),
        ('LIGHT_RAIL', 1.06, 29.3),
        ('TRAM', 0.86, 22.1),
        ('DOUBLE_ELEC', 2.62, 47.6),
        ('EXPRESS_RAIL', 6.92, 91.8),
        ('BRT_HIGH', 1.16, 25.0),
        ('BRT_LOW', 1.16, 18.0),
    ])
    def test_S1_이_CLAUDE_md_와_같은_값을_갖는다(self, code, spacing, speed):
        s1 = {c: (sp, sd) for c, _, sp, sd in M.s1_modes()}
        assert code in s1, f'{code} 가 S1 에서 사라졌다'
        got_sp, got_sd = s1[code]
        assert got_sp == pytest.approx(spacing), f'{code} 역간격이 S1 에서 바뀌었다'
        assert got_sd == pytest.approx(speed), f'{code} 속도가 S1 에서 바뀌었다'

    def test_CLAUDE_md_가_적은_표정속도_요약과_맞는다(self):
        # `(?s)` 로 줄바꿈을 넘는다 — 요약이 두 줄로 접혀 있다
        txt = one(r'(?s)\*\*표정속도\(`speed_kmh`\)\*\*(.{0,160})', '표정속도 요약 줄')
        s1 = {c: sd for c, _, _, sd in M.s1_modes()}
        for label, code in (('중전철', 'HEAVY_METRO'), ('경전철', 'LIGHT_RAIL'),
                            ('트램', 'TRAM'), ('복선전철', 'DOUBLE_ELEC'),
                            ('광역급행', 'EXPRESS_RAIL')):
            m = re.search(label + r'\s*(\d+(?:\.\d+)?)', txt)
            assert m, f'요약 줄에 {label} 이 없다: {txt!r}'
            assert float(m.group(1)) == pytest.approx(s1[code]), f'{label} 속도가 어긋난다'

    def test_CLAUDE_md_가_적은_권장_연장이_S1_과_맞는다(self):
        import test_mode_capacity as MC
        txt = one(r'(?s)\*\*권장 연장\(`max_length_km`\)\*\*(.{0,80})', '권장 연장 요약 줄')
        s1 = MC.s1_max_length()
        for label, codes in (('중전철', ['HEAVY_METRO']), ('경전철', ['LIGHT_RAIL']),
                             ('트램', ['TRAM']), ('BRT', ['BRT_HIGH', 'BRT_LOW'])):
            m = re.search(label + r'\s*(\d+(?:\.\d+)?)', txt)
            assert m, f'요약 줄에 {label} 이 없다: {txt!r}'
            for code in codes:
                assert float(m.group(1)) == pytest.approx(s1[code]), f'{label} 권장 연장이 어긋난다'

    def test_KTX_문턱이_코드와_맞는다(self):
        # 권장 연장과 다른 값이라 따로 본다 — 둘이 한 값이던 때가 있었다
        java = (Path(__file__).resolve().parent.parent / 'backend/src/main/java/com/railfeas/calc'
                / 'IntercityRailFinder.java').read_text(encoding='utf-8')
        code = re.search(r'MIN_ROUTE_KM = (\d+(?:\.\d+)?);', java)
        assert code, 'IntercityRailFinder 에서 MIN_ROUTE_KM 을 못 찾았다'
        doc = one(r'KTX 를 찾는 문턱 (\d+(?:\.\d+)?)km', 'KTX 문턱')
        assert float(doc) == pytest.approx(float(code.group(1)))


class Test회귀식:
    def test_식과_R2_가_지금_자와_같다(self):
        a_doc, b_doc, n_doc, r2_doc = one(
            r'속도 = (\d+(?:\.\d+)?) \+ (\d+(?:\.\d+)?) × 역간격`+ \(노선 (\d+)개, \*\*R² (\d+(?:\.\d+)?)\*\*\)',
            '역간격-속도 회귀식')
        pairs, _ = M.load_pairs()
        a, b, r2 = M.fit([(p[2], p[3]) for p in pairs])
        assert int(n_doc) == len(pairs), '자에 쓰는 노선 수가 바뀌었다'
        assert float(a_doc) == pytest.approx(a, abs=0.005), '절편이 바뀌었다'
        assert float(b_doc) == pytest.approx(b, abs=0.005), '기울기가 바뀌었다'
        assert float(r2_doc) == pytest.approx(r2, abs=0.0005), 'R² 가 바뀌었다'

    def test_적합_구간이_실제_표본_범위와_같다(self):
        lo_doc, hi_doc = one(r'역간격 (\d+(?:\.\d+)?)~(\d+(?:\.\d+)?)km 에서 수단을 구분하지 않아도',
                             '적합 구간')
        pairs, _ = M.load_pairs()
        xs = [p[2] for p in pairs]
        assert float(lo_doc) == pytest.approx(min(xs), abs=0.005)
        assert float(hi_doc) == pytest.approx(max(xs), abs=0.005)


class Test쌍봉분리:
    def test_두_집단_중앙값이_CLAUDE_md_와_같다(self):
        de = M.double_elec_split()
        일반 = st.median(sorted(de[~de.급행].avg_spacing_km))
        assert 일반 == pytest.approx(2.62, abs=0.005)
        assert M.express_spacing() == pytest.approx(6.92, abs=0.005)

    def test_급행_속도가_csv_의_중앙값과_같다(self):
        s1 = {c: sd for c, _, _, sd in M.s1_modes()}
        assert s1['EXPRESS_RAIL'] == pytest.approx(round(M.express_speed(), 1), abs=0.05)


class Test비용잔차검정:
    def test_t_와_검출한계가_CLAUDE_md_와_같다(self):
        """**검출 한계가 더 중요하다.** t 만 적어 두면 "차이가 없다"로 읽힌다."""
        t_doc = float(one(r'잔차 차이는 1\.02배\(t=\+(\d+(?:\.\d+)?)\)', '잔차 t'))
        lim_doc = float(one(r'\*\*검출 한계가 (\d+(?:\.\d+)?)배\*\*', '검출 한계'))
        t, lim = M.cost_residual_t('name')
        assert t == pytest.approx(t_doc, abs=0.01)
        assert lim == pytest.approx(lim_doc, abs=0.01)


class Test생성기와시드:
    def test_cost_model_SPACING_이_S1_과_같다(self):
        """ETL 이 돌 때도 검사하지만, 테스트로도 박는다 — 실제로 어긋나 있었다."""
        C.check_spacing_matches_s1()

    def test_CLAUDE_md_가_가리키는_명령이_실재한다(self):
        """문서가 없는 명령을 안내하면 다음 사람이 헤맨다."""
        for cmd, mod, func in (
                ('--candidates', C, 'candidate_splits'),
                ('--preflight', None, None)):
            assert cmd in CLAUDE, f'{cmd} 안내가 CLAUDE.md 에서 사라졌다'
            if mod is not None:
                assert hasattr(mod, func), f'{cmd} 가 쓰는 {func} 가 없다'
        import sejong_brt_speed
        assert hasattr(sejong_brt_speed, 'preflight')


class Test테스트수:
    """CLAUDE.md·README 에 적은 테스트 수가 실제와 맞는지 — README 가 「14건」 으로 한참
    낡아 있었다(실제 백엔드 116). 문서끼리도, 실제와도 맞아야 한다."""

    ROOT = Path(__file__).resolve().parent.parent
    README = (ROOT / 'README.md').read_text(encoding='utf-8')

    def actual(self):
        import subprocess
        import sys
        out = subprocess.run([sys.executable, '-m', 'pytest', '--collect-only', '-q'],
                             cwd=self.ROOT / 'etl', capture_output=True, text=True,
                             encoding='utf-8').stdout
        etl = int(re.search(r'(\d+) tests? collected', out).group(1))
        java = [p.read_text(encoding='utf-8') for p in (self.ROOT / 'backend/src/test/java').rglob('*.java')]
        # @Test 하나가 테스트 하나다. 매개변수 테스트가 생기면 이 셈이 틀어진다 — 그때는
        # surefire 보고서로 세도록 고칠 것
        assert not any(re.search(r'@(ParameterizedTest|RepeatedTest|TestFactory)\b', t) for t in java), \
            '@Test 개수로는 못 센다 — 셈 방법을 고칠 것'
        backend = sum(len(re.findall(r'^\s*@Test\b', t, re.M)) for t in java)
        front = sum(len(re.findall(r'^test\(', p.read_text(encoding='utf-8'), re.M))
                    for p in (self.ROOT / 'frontend/src').rglob('*.test.js'))
        return {'etl': etl, 'backend': backend, 'frontend': front}

    def test_CLAUDE_md_와_README_의_테스트_수가_실제와_같다(self):
        real = self.actual()
        docs = {
            'CLAUDE.md': {'etl': one(r'python -m pytest` \((\d+)개', 'ETL 테스트 수'),
                          'backend': one(r'mvnw test` \((\d+)개', '백엔드 테스트 수'),
                          'frontend': one(r'npm test` \((\d+)개', '프론트 테스트 수')},
            'README.md': {k: re.search(pat, self.README).group(1) for k, pat in (
                ('etl', r'python -m pytest\s+# (\d+)개'),
                ('backend', r'mvnw test\s+# (\d+)개'),
                ('frontend', r'npm test\s+# (\d+)개'))},
        }
        for doc, got in docs.items():
            assert {k: int(v) for k, v in got.items()} == real, f'{doc} 의 테스트 수가 실제와 다르다'


class Test모델식:
    """README·engineering-notes 에 적은 비용·수요 식이 지금 모델과 같은지. 수요 계수가 10-08
    (인구 자료를 지방까지)에 바뀌었는데 두 문서가 11-02 까지 옛 식(2.608·0.437…)이었다."""

    ROOT = Path(__file__).resolve().parent.parent
    # 클래스 본문의 컴프리헨션은 위의 ROOT 를 못 본다(파이썬 규칙) — 경로를 다시 쓴다
    DOCS = {n: (Path(__file__).resolve().parent.parent / n).read_text(encoding='utf-8')
            for n in ('README.md', 'docs/engineering-notes.md')}
    # 수요 식은 CLAUDE.md 의 규칙에도 적혀 있다(비용 식은 없다)
    DEMAND_DOCS = {**DOCS, 'CLAUDE.md': (Path(__file__).resolve().parent.parent / 'CLAUDE.md').read_text(encoding='utf-8')}
    NUM = r'(-?[0-9]+\.[0-9]+)'

    def test_수요_식이_S6_시드와_같다(self):
        seed = (self.ROOT / 'db/seed/S6__demand_model.sql').read_text(encoding='utf-8')
        want = [float(re.search(r"\('demand_%s',\s*(-?[0-9.]+)" % k, seed).group(1))
                for k in ('intercept', 'pop_elasticity', 'worker_elasticity', 'light_rail_coef')]
        # 경전철 항은 음수라 문서에 「- 0.638·경전철」 로 적는다
        pat = (r'log\(일이용객\) = ' + self.NUM + r' \+ ' + self.NUM + r'·log\(인구\) \+ ' + self.NUM
               + r'·log\(종사자\) - ' + self.NUM + r'·경전철')
        for name, text in self.DEMAND_DOCS.items():
            m = re.search(pat, text)
            assert m, f'{name} 에서 수요 식을 못 찾았다 — 문장이 바뀌었으면 정규식도 고칠 것'
            got = [float(g) for g in m.groups()]
            got[3] = -got[3]
            assert got == pytest.approx(want, abs=0.0006), f'{name} 의 수요 식이 S6 과 다르다'

    def test_비용_식이_지금_적합과_같다(self):
        _, model = C.residuals()
        beta = dict(zip(model['names'], model['beta']))
        want = [beta['절편'], beta['log(연장)'], beta['지하비율'], beta['log(역수)']]
        pat = (r'log\(사업비\) = ' + self.NUM + r' \+ ' + self.NUM + r'·log\(연장\) \+ ' + self.NUM
               + r'·지하비율 \+ ' + self.NUM + r'·log\(역수\)')
        for name, text in self.DOCS.items():
            m = re.search(pat, text)
            assert m, f'{name} 에서 비용 식을 못 찾았다 — 문장이 바뀌었으면 정규식도 고칠 것'
            assert [float(g) for g in m.groups()] == pytest.approx(want, abs=0.0006), f'{name} 의 비용 식이 모델과 다르다'
