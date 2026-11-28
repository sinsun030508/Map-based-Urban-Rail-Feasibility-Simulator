// 소스 모양으로 막는다 — 컴포넌트를 띄우는 도구가 없다(`node --test`, 의존성 없음)
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');

test('App 이 지도 영역 안에 하단바를 두고, 지도에 그린 안과 계산 여부를 넘긴다', () => {
  assert.match(read('../App.jsx'),
    /<div className="map-area">\s*<MapView[\s\S]*?<TransitBar points=\{points\} row=\{shown\} ready=\{results\.length > 0\}\s+selected=\{transitNow\?\.index \?\? null\} onSelect=\{setTransitRoute\} \/>\s*<\/div>/);
});

test('경로는 누를 때만 부른다 — 효과 안에서 부르면 점을 찍을 때마다 하루 한도가 준다', () => {
  const src = read('./TransitBar.jsx');
  const effects = src.match(/useEffect\([\s\S]*?\}, \[[^\]]*\]\);/g) || [];
  assert.ok(effects.length > 0, '키가 있는지 묻는 효과는 있어야 한다');
  effects.forEach((e) => assert.doesNotMatch(e, /api\.transit\(/));
  assert.equal(src.match(/api\.transit\(/g).length, 1);
  assert.match(src, /onClick=\{ask\}/);
});

test('옛 노선의 답은 그리지 않고, 늦게 온 앞 요청이 새 답을 덮지 않는다', () => {
  const src = read('./TransitBar.jsx');
  assert.match(src, /isCurrent\(answer, key\)/);
  assert.match(src, /if \(lastAsked\.current !== key\) return;/);
});

test('지도 아래 축척·출처 표시를 가리지 않게 띄운다', () => {
  assert.match(read('../styles.css'), /\.transit-bar \{[^}]*bottom: 34px;/);
});

test('카드는 가로로 나란한 상자다 — 좁으면 옆으로 넘긴다(11-28 사용자 요청)', () => {
  const css = read('../styles.css');
  assert.match(css, /\.transit-list \{[^}]*display: flex;[^}]*overflow-x: auto;/);
  assert.match(css, /\.transit-card \{[^}]*flex: 1 0 220px;[^}]*border: 1px solid/);
});

test('경로 상자를 누르면 지도에 그리고 다시 누르면 지운다 — 닫거나 새로 물으면 지운다', () => {
  const src = read('./TransitBar.jsx');
  assert.match(src, /onSelect\(picked \? null : \{ key, index: i, legs: it\.legs \}\)/);
  assert.match(src, /onKeyDown=/);
  assert.equal(src.match(/onSelect\(null\)/g).length >= 3, true, '닫기·새로 묻기·신설안 상자');
});

test('지도의 경로는 지금 노선의 것일 때만 남는다 — 점이 바뀌면 키가 달라 저절로 지워진다', () => {
  assert.match(read('../App.jsx'),
    /const transitNow = transitRoute && results\.length > 0 && transitRoute\.key === routeKey\(points\) \? transitRoute : null;/);
  assert.match(read('../App.jsx'), /transitLegs=\{transitNow\?\.legs\}/);
  assert.match(read('../map/MapView.jsx'), /transitFeatures\(transitLegs\)/);
});

