const TOKEN_KEY = 'railfeas.token';
const USER_KEY = 'railfeas.user';

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
  // "로그인이 필요합니다"로 보여 원인을 못 찾는다
  if (res.status === 401) {
    clearSession();
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
  // 3D 인구밀도 — 화면 범위만 받는다. 전국을 다 받으면 응답이 수 MB 다
  population: ({ minLat, minLng, maxLat, maxLng }) =>
    request(`/reference/population?minLat=${minLat}&minLng=${minLng}`
      + `&maxLat=${maxLat}&maxLng=${maxLng}`),
  listScenarios: () => request('/scenarios'),
  getScenario: (id) => request(`/scenarios/${id}`),
  createScenario: (body) => request('/scenarios', { method: 'POST', body }),
  calculate: (id) => request(`/scenarios/${id}/calculate`, { method: 'POST' }),
  deleteScenario: (id) => request(`/scenarios/${id}`, { method: 'DELETE' }),
};
