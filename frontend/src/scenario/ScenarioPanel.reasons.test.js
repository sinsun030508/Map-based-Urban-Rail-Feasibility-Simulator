// `npm test` (node --test, 의존성 없음). React 를 그리지 않고 **소스 모양**만 본다.
//
// 새 도로의 까닭(`newRoadReasons`, 11-19)을 패널이 실제로 적는지 본다. 판정(중복·옛 저장·50m 미만)은 `routeLine.test.js` 가 고정한다 —
// 여기서는 import 만 남고 줄이 지워지면 직선이 된 까닭이 서버에는 있는데 화면에는 안 나오는 것을 막는다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const text = readFileSync(fileURLToPath(new URL('./ScenarioPanel.jsx', import.meta.url)), 'utf8');

test('패널이 새 도로의 까닭을 가져와 「새로 놓는 구간」 줄 아래에 적는다', () => {
  assert.match(text, /import \{ newRoadReasons, roadSummary \} from '\.\.\/geo\/routeLine'/);
  assert.ok(text.includes('newRoadReasons(mountainRow.routeLine)'), '패널이 까닭을 모으지 않는다');
  assert.ok(text.includes('새로 놓는 까닭 —'), '까닭 줄이 없다');
});

test('새로 놓는 구간이 있을 때만 까닭을 적는다 — 구간 줄과 같은 50m 기준', () => {
  assert.match(text, /s\.newKm > 0\.05 \? newRoadReasons/);
});

test('패널이 계산 전 물 안내의 덧말을 가져와 물 위 구간 줄 아래에 적는다(11-22)', () => {
  assert.match(text, /import \{ bridgeCostIncluded, preCalcWaterHint \} from '\.\/roadWater'/);
  assert.ok(text.includes('preCalcWaterHint(Boolean(results?.length), spans.length)'), '덧말 판정을 안 쓴다');
  assert.ok(text.includes('{hint && <p className="water-span small muted">{hint}</p>}'), '덧말 줄이 없다');
});

