// `npm test` (node --test, 의존성 없음). 지도를 그리지 않고 **소스 모양**만 본다 — WebGL 이 없어서다.
//
// MapLibre 의 `load` 와 `isStyleLoaded()` 는 **타일까지 다 받았다**는 뜻이지 「스타일을 쓸 수 있다」 는
// 뜻이 아니다. 여기에 기대면 두 가지가 깨졌다(11-06, CDP 로 타일 하나를 붙잡아 재현했다).
//   ① 타일 하나가 안 끝나면 `load` 가 안 와서 노선·산·물 레이어와 fitBounds 가 통째로 멈춘다
//   ② `load` 는 지도 일생에 **한 번**이다. 그 뒤에 타일을 받는 사이 시나리오를 열면 `isStyleLoaded()`
//      가 거짓이라 `once('load')` 로 미루는데, 그 이벤트는 다시 오지 않아 노선이 영영 안 그려진다
// 그래서 소스·레이어는 `style.load` 에서 만들고, 그리기는 `mapReady` 상태를 기다린다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const SRC = fileURLToPath(new URL('..', import.meta.url));
const TILE_WAIT = /\.(on|once)\(\s*['"]load['"]|\.isStyleLoaded\(/;
const COMMENT = /^\s*(\/\/|\/?\*)/;

function sources(dir) {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) return sources(p);
    return /\.jsx?$/.test(name) && !name.includes('.test.') ? [p] : [];
  });
}

/** 파일 하나에서 「타일을 기다리는 줄」 을 `줄: 내용` 으로 (주석은 뺀다) */
export function tileWaits(text) {
  return text.split('\n')
    .map((line, i) => [i + 1, line])
    .filter(([, line]) => !COMMENT.test(line) && TILE_WAIT.test(line))
    .map(([n, line]) => `${n}: ${line.trim()}`);
}

test('지도 코드가 타일을 다 받기를 기다리지 않는다', () => {
  const found = sources(SRC).flatMap((p) =>
    tileWaits(readFileSync(p, 'utf8')).map((w) => `${p.slice(SRC.length)}:${w}`));
  assert.deepEqual(found, [], "load·isStyleLoaded 는 타일까지 기다린다 — 'style.load' 와 mapReady 를 쓴다");
});

test('검사가 실제로 잡는다 — 고치기 전 MapView 모양', () => {
  const before = [
    "    map.on('load', () => {",
    '    // map.once(\'load\', draw) 는 주석이라 세지 않는다',
    '    if (map.isStyleLoaded()) draw();',
    "    else map.once('load', draw);",
    "    map.on('style.load', () => {});",
  ].join('\n');
  assert.deepEqual(tileWaits(before), [
    "1: map.on('load', () => {",
    '3: if (map.isStyleLoaded()) draw();',
    "4: else map.once('load', draw);",
  ]);
});
