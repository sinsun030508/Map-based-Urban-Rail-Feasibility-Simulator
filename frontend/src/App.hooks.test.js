// `npm test` (node --test, 의존성 없음). React 를 그리지 않고 **소스 모양**만 본다.
//
// 컴포넌트가 일찍 return 하면 그 아래의 훅은 그 화면에서 불리지 않는다 — 훅 개수가 렌더마다
// 달라져 "Rendered fewer hooks" 로 화면이 통째로 깨진다. App 의 호수 우회 useEffect 를 이른
// return(로그인·관리자·시나리오 비교) 아래에 두어 그 세 화면이 깨져 있었다(10-26 ~ 11-01).
// 빌드도 다른 테스트도 통과하고 **그 화면으로 갈 때만** 터져서, 모든 컴포넌트를 훑는다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const SRC = fileURLToPath(new URL('.', import.meta.url));
// 맨 바깥 함수(들여쓰기 0)의 몸통에서, 맨 바깥(들여쓰기 2칸)의 것만 센다 — 안쪽 함수의
// return·호출은 상관없다
const FUNC = /^(export (default )?)?function [A-Za-z]\w*\(/;
const EARLY_RETURN = /^ {2}(if \(.+\) (return\b|\{$)|return\b)/;
const HOOK = /^ {2}(const .+ = )?use[A-Z]\w*\(/;

function sources(dir) {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) return sources(p);
    return /\.jsx?$/.test(name) && !name.includes('.test.') ? [p] : [];
  });
}

/** 파일 하나에서 「이른 return 아래의 훅」 을 `줄: 내용` 으로 */
export function lateHooks(text) {
  const lines = text.split('\n');
  const starts = lines.flatMap((l, i) => (FUNC.test(l) ? [i] : []));
  return starts.flatMap((s, k) => {
    const end = k + 1 < starts.length ? starts[k + 1] : lines.length;
    const first = lines.slice(s + 1, end).findIndex((l) => EARLY_RETURN.test(l));
    if (first < 0) return [];
    return lines.slice(s + 1 + first, end)
      .map((l, i) => [s + 2 + first + i, l])
      .filter(([, l]) => HOOK.test(l))
      .map(([n, l]) => `${n}: ${l.trim()}`);
  });
}

test('어느 컴포넌트에도 이른 return 아래의 훅이 없다', () => {
  const found = sources(SRC).flatMap((p) =>
    lateHooks(readFileSync(p, 'utf8')).map((h) => `${p.slice(SRC.length)}:${h}`));
  assert.deepEqual(found, [], '이른 return 아래에 훅이 있다 — 그 화면으로 가면 훅 개수가 줄어 깨진다');
});

test('검사가 실제로 잡는다 — 고치기 전 App 모양', () => {
  const before = [
    'export default function App() {',
    '  const [user] = useState(null);',
    '  if (!user) return <Login />;',
    '  if (compare) return <Compare />;',
    '  useEffect(() => {}, []);',
    '  return <Main />;',
    '}',
  ].join('\n');
  assert.deepEqual(lateHooks(before), ['5: useEffect(() => {}, []);']);
});
