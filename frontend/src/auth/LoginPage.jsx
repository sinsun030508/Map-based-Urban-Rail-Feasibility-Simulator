import { useEffect, useState } from 'react';
import { api, saveSession } from '../api/client';

const SOCIAL_LABEL = { google: 'Google', kakao: '카카오' };

/** `notice` — 세션이 끊겨 내려왔을 때 왜 내려왔는지(App 이 준다). 처음 그릴 때만 보인다 */
export default function LoginPage({ onLogin, notice = null }) {
  const [mode, setMode] = useState('login');
  const [form, setForm] = useState({ email: '', password: '', nickname: '' });
  const [error, setError] = useState(notice);
  const [busy, setBusy] = useState(false);
  // 켜져 있는 소셜 로그인. **키가 없으면 비어 버튼을 그리지 않는다** — 누르면 404 라서다
  const [providers, setProviders] = useState([]);

  useEffect(() => {
    api.providers().then(setProviders).catch(() => setProviders([]));
  }, []);

  // **안내는 나중에 올 수도 있다.** 세션 만료는 이 화면이 그려질 때 이미 정해져 있지만, 소셜 로그인
  // 실패(`#error=`·`/me` 401)는 이 화면이 뜬 **뒤에** App 이 알게 된다 — 처음 값(useState)만 보면 안 뜬다
  useEffect(() => {
    if (notice) setError(notice);
  }, [notice]);

  const change = (key) => (e) => setForm({ ...form, [key]: e.target.value });

  async function submit(e) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const body =
        mode === 'login'
          ? { email: form.email, password: form.password }
          : form;
      const session = await (mode === 'login' ? api.login(body) : api.signup(body));
      saveSession(session);
      onLogin(session);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login">
      <form className="card" onSubmit={submit}>
        <h1>RailFeas</h1>
        <p className="muted">노선 타당성 분석 시뮬레이터</p>

        <label>
          이메일
          <input type="email" value={form.email} onChange={change('email')} required />
        </label>
        <label>
          비밀번호
          <input
            type="password"
            value={form.password}
            onChange={change('password')}
            minLength={8}
            required
          />
        </label>
        {/* 규칙을 미리 알린다. minLength 는 사용자가 직접 친 값에만 걸려(HTML 규격)
            자동 입력·붙여넣기는 그냥 통과하고, 그때는 서버 400 이 유일한 안내가 된다 */}
        {mode === 'signup' && (
          <p className="muted small">비밀번호는 8자 이상이어야 합니다.</p>
        )}
        {mode === 'signup' && (
          <label>
            닉네임
            <input value={form.nickname} onChange={change('nickname')} required />
          </label>
        )}

        {error && <p className="error">{error}</p>}

        <button type="submit" disabled={busy}>
          {busy ? '처리 중…' : mode === 'login' ? '로그인' : '가입하고 시작'}
        </button>
        <button
          type="button"
          className="link"
          onClick={() => {
            setMode(mode === 'login' ? 'signup' : 'login');
            setError(null);
          }}
        >
          {mode === 'login' ? '계정이 없으신가요? 회원가입' : '이미 계정이 있습니다'}
        </button>

        {/* 백엔드의 OAuth 시작 주소로 그냥 이동한다(Vite 가 /oauth2 를 백엔드로 넘긴다). 돌아올 때는
            `/oauth/callback#token=…` 이고 App 이 받는다(`oauthCallback.js`) */}
        {providers.length > 0 && (
          <div className="social">
            <p className="muted small">또는</p>
            {providers.map((id) => (
              <a key={id} className={`social-button ${id}`} href={`/oauth2/authorization/${id}`}>
                {SOCIAL_LABEL[id] || id}로 계속하기
              </a>
            ))}
          </div>
        )}
      </form>
    </div>
  );
}
