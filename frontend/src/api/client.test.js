// `npm test` (node --test, 의존성 없음). fetch·localStorage·window 를 가짜로 끼워 401 처리를 본다.
//
// 401 은 두 가지다 — **토큰을 보냈는데** 401 이면 세션이 끝난 것이고, **토큰 없이**(로그인·가입)
// 401 이면 이메일·비밀번호가 틀린 것이다. 전에는 둘을 가르지 않아, 없는 계정으로 로그인하면
// 서버가 보낸 "이메일 또는 비밀번호가 올바르지 않습니다" 를 버리고 "로그인이 만료되었습니다" 를
// 띄웠다 — 로그인한 적도 없는 사람에게 (11-04 화면 점검에서 찾았다).
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';

const store = new Map();
const events = [];
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: (k) => store.delete(k),
};
globalThis.window = { dispatchEvent: (e) => events.push(e.type) };

const { api, saveSession, SESSION_EXPIRED } = await import('./client.js');

function reply(status, body) {
  globalThis.fetch = async () => ({
    status,
    ok: status >= 200 && status < 300,
    json: async () => body,
  });
}

beforeEach(() => {
  store.clear();
  events.length = 0;
});

test('토큰 없이 받은 401 은 서버의 안내를 그대로 띄운다 — 로그인 실패', async () => {
  reply(401, { message: '이메일 또는 비밀번호가 올바르지 않습니다' });
  await assert.rejects(api.login({ email: 'a@example.test', password: 'x' }),
    { message: '이메일 또는 비밀번호가 올바르지 않습니다' });
  assert.deepEqual(events, [], '로그인 실패는 세션 만료가 아니다 — 알리면 화면이 로그인 화면으로 다시 내려간다');
});

test('로그인 횟수 제한(429)은 세션 만료가 아니다 — 언제 다시 하면 되는지 서버 문구를 그대로 띄운다', async () => {
  saveSession({ accessToken: 't', userId: 1, nickname: 'n', role: 'USER' });
  reply(429, { message: '비밀번호를 여러 번 틀렸습니다 — 15분 뒤에 다시 시도해 주세요' });
  await assert.rejects(api.login({ email: 'a@example.test', password: 'x' }),
    { message: '비밀번호를 여러 번 틀렸습니다 — 15분 뒤에 다시 시도해 주세요' });
  assert.deepEqual(events, []);
  assert.equal(store.size > 0, true, '세션을 지우면 안 된다');
});

test('토큰이 사라진 채 부른 보호 API 의 401 도 세션 만료다', async () => {
  // 다른 탭에서 로그아웃하면 저장소의 토큰만 지워지고 이 탭은 로그인된 화면 그대로다.
  // 그때 저장을 누르면 토큰 없이 나가 401 이 온다 — 「토큰을 보냈을 때만 만료」 로 가르면
  // 로그인 화면으로 안 내려가 **로그인된 척하는 화면**이 남는다(첫 수정이 그랬다)
  reply(401, { message: '인증이 필요합니다' });
  await assert.rejects(api.listScenarios(), { message: '로그인이 만료되었습니다. 다시 로그인해 주세요' });
  assert.deepEqual(events, [SESSION_EXPIRED]);
});

test('토큰을 보내고 받은 401 은 세션 만료다', async () => {
  saveSession({ accessToken: 'tok', userId: 1, nickname: 'n', role: 'USER' });
  reply(401, { message: '인증이 필요합니다' });
  await assert.rejects(api.listScenarios(), { message: '로그인이 만료되었습니다. 다시 로그인해 주세요' });
  assert.deepEqual(events, [SESSION_EXPIRED]);
  assert.equal(localStorage.getItem('railfeas.token'), null, '끊긴 토큰은 지운다');
});

test('api 함수 이름이 겹치지 않는다 — 겹치면 뒤의 것이 오류 없이 앞의 것을 덮는다(11-12)', async () => {
  // 구역 우회(`/route/zones`)를 `zones` 로 붙였더니 지도 원 목록(`/reference/zones`)을 덮어 원이 하나도 안 그려졌다
  const { readFileSync } = await import('node:fs');
  const src = readFileSync(new URL('./client.js', import.meta.url), 'utf8');
  const body = src.slice(src.indexOf('export const api = {'));
  const keys = [...body.matchAll(/^ {2}(\w+):/gm)].map((m) => m[1]);
  assert.ok(keys.length > 10);
  assert.deepEqual(keys.filter((k, i) => keys.indexOf(k) !== i), []);
});
