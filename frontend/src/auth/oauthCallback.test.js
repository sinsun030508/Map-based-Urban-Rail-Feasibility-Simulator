// `npm test` (node --test). 소셜 로그인에서 돌아온 주소를 읽는 규칙.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readCallback } from './oauthCallback.js';

test('콜백 주소가 아니면 아무것도 하지 않는다', () => {
  assert.equal(readCallback({ pathname: '/', hash: '#token=abc' }), null);
});

test('# 뒤의 토큰을 읽는다 — 백엔드가 URL 인코딩해 보낸다', () => {
  assert.deepEqual(readCallback({ pathname: '/oauth/callback', hash: '#token=a.b%2Bc' }), { token: 'a.b+c' });
});

test('# 뒤의 오류를 읽고, 둘 다 없으면 다시 시도하라고 한다', () => {
  assert.deepEqual(
    readCallback({ pathname: '/oauth/callback', hash: '#error=%EC%8B%A4%ED%8C%A8' }), { error: '실패' });
  assert.match(readCallback({ pathname: '/oauth/callback', hash: '' }).error, /다시 시도/);
});
