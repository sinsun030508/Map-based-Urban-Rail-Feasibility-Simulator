import { Fragment, useEffect, useState } from 'react';
import { api } from '../api/client';
import { userActions, deleteMessage } from './adminRules';

const PROVIDER_LABEL = { LOCAL: '이메일', GOOGLE: 'Google', KAKAO: '카카오' };

/**
 * 회원 관리 (제안서 기능 8). 권한은 요청마다 DB 에서 읽으므로 바꾸면 그 사람의 다음 요청부터 적용된다.
 * 실패는 기준값 표처럼 **그 행 아래에** 적는다 — 목록이 길면 맨 위 메시지는 화면 밖에 그려진다.
 */
export default function AdminUsers({ me }) {
  const [rows, setRows] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(null);

  useEffect(() => {
    api.adminUsers().then(setRows).catch((e) => setError({ id: null, message: e.message }));
  }, []);

  async function changeRole(row, role) {
    setError(null);
    setBusy(row.id);
    try {
      const updated = await api.changeUserRole(row.id, role);
      setRows((list) => list.map((x) => (x.id === updated.id ? updated : x)));
    } catch (e) {
      setError({ id: row.id, message: e.message });
    } finally {
      setBusy(null);
    }
  }

  async function remove(row) {
    if (!window.confirm(deleteMessage(row))) return;
    setError(null);
    setBusy(row.id);
    try {
      await api.deleteUser(row.id);
      setRows((list) => list.filter((x) => x.id !== row.id));
    } catch (e) {
      setError({ id: row.id, message: e.message });
    } finally {
      setBusy(null);
    }
  }

  if (error?.id == null && error) return <p className="error">{error.message}</p>;
  if (!rows) return <p className="muted small">불러오는 중입니다</p>;

  return (
    <section>
      <h2>회원 ({rows.length})</h2>
      <p className="muted small">
        권한은 바꾸는 즉시 그 회원의 다음 요청부터 적용됩니다. 회원을 지우면 그 사람의 시나리오도 함께 사라지고
        되돌릴 수 없습니다.
      </p>
      <table className="admin-table">
        <thead>
          <tr><th>닉네임</th><th>이메일</th><th>가입</th><th>가입일</th><th>시나리오</th><th>권한</th><th /></tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const a = userActions(r, me);
            return (
              <Fragment key={r.id}>
                <tr>
                  <td className="name">{r.nickname}{r.id === me && <span className="muted small"> (나)</span>}</td>
                  <td>{r.email}</td>
                  <td className="unit">{PROVIDER_LABEL[r.provider] || r.provider}</td>
                  <td className="unit">{r.createdAt?.slice(0, 10)}</td>
                  <td className="num">{r.scenarioCount}</td>
                  <td className="unit">{r.role === 'ADMIN' ? '관리자' : '일반'}</td>
                  <td className="actions">
                    {a.nextRole && (
                      <button disabled={busy === r.id} onClick={() => changeRole(r, a.nextRole)}>
                        {a.nextRole === 'ADMIN' ? '관리자로 올리기' : '일반으로 내리기'}
                      </button>
                    )}
                    {a.canDelete && (
                      <button className="danger" disabled={busy === r.id} onClick={() => remove(r)}>지우기</button>
                    )}
                    {a.note && <span className="muted small"> {a.note}</span>}
                  </td>
                </tr>
                {error?.id === r.id && (
                  <tr className="row-error"><td /><td colSpan={6} className="error">{error.message}</td></tr>
                )}
              </Fragment>
            );
          })}
        </tbody>
      </table>
    </section>
  );
}
