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

  if (res.status === 401 || res.status === 403) {
    clearSession();
    throw new Error('로그인이 필요합니다');
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
  listScenarios: () => request('/scenarios'),
  getScenario: (id) => request(`/scenarios/${id}`),
  createScenario: (body) => request('/scenarios', { method: 'POST', body }),
  deleteScenario: (id) => request(`/scenarios/${id}`, { method: 'DELETE' }),
};
