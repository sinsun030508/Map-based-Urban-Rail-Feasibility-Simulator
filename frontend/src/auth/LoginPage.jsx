import { useState } from 'react';
import { api, saveSession } from '../api/client';

export default function LoginPage({ onLogin }) {
  const [mode, setMode] = useState('login');
  const [form, setForm] = useState({ email: '', password: '', nickname: '' });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

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
      </form>
    </div>
  );
}
