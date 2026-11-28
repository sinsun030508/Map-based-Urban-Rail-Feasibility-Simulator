// 소스 모양으로 막는다 — 컴포넌트를 띄우는 도구가 없다(`node --test`, 의존성 없음)
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');

test('App 이 지도 영역 안에 하단바를 두고, 지도에 그린 안과 계산 여부를 넘긴다', () => {
  assert.match(read('../App.jsx'),
    /<div className="map-area">\s*<MapView[\s\S]*?<TransitBar points=\{points\} row=\{shown\} ready=\{results\.length > 0\} \/>\s*<\/div>/);
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
