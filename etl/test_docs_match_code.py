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
