const TOKEN_KEY = 'railfeas.token';
const USER_KEY = 'railfeas.user';

/** 세션이 끊겼을 때 App 에게 알리는 창구 — 아래 401 처리 참고 */
export const SESSION_EXPIRED = 'railfeas:session-expired';

export function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

export function getUser() {
  const raw = localStorage.getItem(USER_KEY);
  return raw ? JSON.parse(raw) : null;
}

export function saveSession({ accessToken, userId, nickname, role }) {
  localStorage.setItem(TOKEN_KEY, accessToken);
  localStorage.setItem(USER_KEY, JSON.stringify({ userId, nickname, role }));
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

/** 서버가 {message} 로 주는 오류를 그대로 띄우기 위해 얇게 감싼다. */
async function request(path, { method = 'GET', body } = {}) {
  const token = getToken();
  const res = await fetch(`/api${path}`, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });

  // 401 만 세션 만료로 본다. 403 을 함께 묶으면 서버의 다른 오류까지
  // "로그인이 필요합니다"로 보여 원인을 못 찾는다.
  // 서버가 인증 실패에 401, 권한 부족에 403 을 주도록 맞춰 뒀다
  // (`SecurityConfig` 의 exceptionHandling — 전에는 둘 다 403 이었다)
  if (res.status === 401) {
    clearSession();
    // **저장소만 비우면 화면은 그대로 로그인된 척한다.** localStorage 를 지워도
    // React 는 마운트 때 읽은 user 를 들고 있어서, 목록이 "저장된 시나리오가
    // 없습니다"로 뜬다 — 세션이 끊겼다는 말이 아니라 "당신은 저장한 것이 없다"는
    // 거짓말이다. 저장해 둔 노선이 있는 사람은 지워진 줄 안다.
    // 만료는 아무 호출에서나 터지므로(저장·계산·삭제·관리자) 호출마다 검사하지
    // 않고 한 곳에서 알린다. App 이 듣고 로그인 화면으로 내린다.
    window.dispatchEvent(new Event(SESSION_EXPIRED));
    throw new Error('로그인이 만료되었습니다. 다시 로그인해 주세요');
  }
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    throw new Error(data.message || `요청 실패 (${res.status})`);
  }
  return res.status === 204 ? null : res.json();
}

export const api = {
  signup: (body) => request('/auth/signup', { method: 'POST', body }),
  login: (body) => request('/auth/login', { method: 'POST', body }),
  modes: () => request('/reference/modes'),
  zones: () => request('/reference/zones'),
  // 기준값이 마지막으로 바뀐 시각 — 저장된 결과가 낡았는지 판단한다
  referenceUpdatedAt: () => request('/reference/updated-at'),
  // 3D 인구밀도 — 화면 범위만 받는다. 전국을 다 받으면 응답이 수 MB 다
  population: ({ minLat, minLng, maxLat, maxLng }) =>
    request(`/reference/population?minLat=${minLat}&minLng=${minLng}`
      + `&maxLat=${maxLat}&maxLng=${maxLng}`),
  listScenarios: () => request('/scenarios'),
  getScenario: (id) => request(`/scenarios/${id}`),
  createScenario: (body) => request('/scenarios', { method: 'POST', body }),
  updateScenario: (id, body) => request(`/scenarios/${id}`, { method: 'PUT', body }),
  calculate: (id) => request(`/scenarios/${id}/calculate`, { method: 'POST' }),
  deleteScenario: (id) => request(`/scenarios/${id}`, { method: 'DELETE' }),
  // 관리자 기준값 — 서버가 ROLE_ADMIN 을 요구한다
  adminParameters: () => request('/admin/parameters'),
  updateParameter: (name, body) =>
    request(`/admin/parameters/${encodeURIComponent(name)}`, { method: 'PUT', body }),
  adminModes: () => request('/admin/modes'),
  updateMode: (modeType, body) =>
    request(`/admin/modes/${modeType}`, { method: 'PUT', body }),
};
