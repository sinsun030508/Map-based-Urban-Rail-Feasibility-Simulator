/**
 * 소셜 로그인에서 돌아온 주소를 읽는다 — `/oauth/callback#token=…` 또는 `#error=…`.
 *
 * **토큰은 `#` 뒤로 온다**(`OAuth2SuccessHandler`). `?token=` 이면 방문 기록·북마크·다음 페이지의
 * Referer 에 남는다 — `#` 뒤는 서버로 가지 않는다. 그래도 주소창에는 남으므로 App 이 **읽자마자
 * 주소에서 지운다**(`history.replaceState`).
 *
 * @returns 콜백 주소가 아니면 null, 아니면 `{ token }` 또는 `{ error }`
 */
export const CALLBACK_PATH = '/oauth/callback';

export function readCallback({ pathname, hash }) {
  if (pathname !== CALLBACK_PATH) return null;
  const params = new URLSearchParams((hash || '').replace(/^#/, ''));
  const token = params.get('token');
  if (token) return { token };
  return { error: params.get('error') || '소셜 로그인 결과를 받지 못했습니다 — 다시 시도해 주세요' };
}
