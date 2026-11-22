// `npm test` (node --test, 의존성 없음). 지도 팝업은 **HTML 문자열이 아니라 DOM 노드**로 만든다 — 소스 모양만 본다(WebGL 이 없다).
//
// 11-22 `npm audit` 가 maplibre-gl ≤ 6.4.0 의 critical(GHSA-jrc7-96c5-q579, `DOM.sanitize()` 우회 XSS)을 알렸다. `Popup.setHTML` 이 그 살균기를 쓴다.
// 지금 팝업에 들어가는 구역 이름은 DB 시드에서만 오고 관리자 화면에도 편집이 없어 악용할 길은 없었다 — 그래도 `${p.name}` 을 HTML 문자열에 끼우고 라이브러리 살균기에 기댄
// 모양이라, 구역 편집이 생기는 날 바로 취약해진다. 메이저 업그레이드(4 → 6, deck.gl 연동) 없이 살균기를 아예 안 쓰게 `setDOMContent` + `textContent` 로 바꿨다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const SRC = fileURLToPath(new URL('..', import.meta.url));
const strip = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');   // 주석에 적힌 이름은 세지 않는다

function sources(dir = SRC) {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    if (statSync(p).isDirectory()) return sources(p);
    return /\.(jsx?|mjs)$/.test(f) && !f.endsWith('.test.js') ? [p] : [];
  });
}

test('어디서도 Popup.setHTML 을 쓰지 않는다 — 문자열을 살균기에 맡기지 않는다', () => {
  const offenders = sources().filter((p) => /\.setHTML\s*\(/.test(strip(readFileSync(p, 'utf8'))));
  assert.deepEqual(offenders, [], 'setHTML 은 maplibre 의 DOM.sanitize() 에 기댄다');
});

test('구역 팝업은 setDOMContent 로 노드를 넘기고, 이름은 textContent 로 넣는다', () => {
  const src = strip(readFileSync(join(SRC, 'map', 'MapView.jsx'), 'utf8'));
  assert.match(src, /\.setDOMContent\(/);
  assert.match(src, /\.textContent\s*=\s*p\.name/, '구역 이름은 textContent 로 넣는다');
});

test('팝업 코드에 HTML 문자열 끼워 넣기(`<strong>${…`)가 없다', () => {
  const src = strip(readFileSync(join(SRC, 'map', 'MapView.jsx'), 'utf8'));
  assert.doesNotMatch(src, /<(strong|small|br)[^>]*>\s*\$\{/);
  assert.doesNotMatch(src, /\$\{p\.name\}/);
});
