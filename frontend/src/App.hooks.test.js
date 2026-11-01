// `npm test` (node --test, 의존성 없음). React 를 그리지 않고 **소스 모양**만 본다.
//
// App 은 로그인·관리자·시나리오 비교 화면으로 **이른 return** 을 한다. 그 아래에 훅을 두면
// 그 화면으로 갈 때 훅 개수가 줄어 "Rendered fewer hooks" 로 화면이 통째로 깨진다 —
// 호수 우회의 useEffect 를 아래에 두어 시나리오 비교·관리자·로그아웃이 깨져 있었다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const src = readFileSync(new URL('./App.jsx', import.meta.url), 'utf8');
const lines = src.slice(src.indexOf('export default function App')).split('\n');
// 컴포넌트 맨 바깥(들여쓰기 2칸)의 것만 센다 — 함수 안쪽의 return·호출은 상관없다
const EARLY_RETURN = /^ {2}if \(.+\) (return\b|\{$)/;
const HOOK = /^ {2}(const .+ = )?use[A-Z]\w*\(/;

test('App 의 훅은 모두 첫 이른 return 보다 위에 있다', () => {
  const first = lines.findIndex((l) => EARLY_RETURN.test(l));
  assert.ok(first > 0, '이른 return 을 찾지 못했다 — 정규식이 소스와 어긋났다');
  assert.ok(lines.slice(0, first).some((l) => HOOK.test(l)), '위쪽 훅을 하나도 못 찾았다');
  const late = lines.slice(first).map((l, i) => [first + i, l]).filter(([, l]) => HOOK.test(l));
  assert.deepEqual(late.map(([i, l]) => `${i}: ${l.trim()}`), [],
    '이른 return 아래에 훅이 있다 — 그 화면으로 가면 훅 개수가 줄어 깨진다');
});
