import { useEffect, useState } from 'react';
import { api } from '../api/client';

/**
 * 관리자 기준값 CRUD (제안서 기능 8).
 *
 * 값만 고치고 끝내지 않는다 — **근거를 함께 받는다.** 이 프로젝트에서 숫자보다 중요한 것이
 * 출처라서, 근거를 비우면 저장 버튼이 눌리지 않는다.
 *
 * 추가·삭제는 없다. 기준값 목록은 ETL·시드가 만들고, 행이 사라지면 계산이 바로 멈춘다.
 */
const MODE_LABEL = {
  HEAVY_METRO: '지하철', LIGHT_RAIL: '경전철', TRAM: '트램',
  BRT_HIGH: 'BRT 고급형', BRT_LOW: 'BRT 저급형', DOUBLE_ELEC: '복선전철',
  SINGLE_ELEC: '단선전철', UPGRADE: '기존선 개량',
};

const MODE_FIELDS = [
  ['pphpdMin', '수요 하한'],
  ['pphpdMax', '수송능력 상한'],
  ['spacingKm', '역간격 km'],
  ['speedKmh', '표정속도'],
  ['maxLengthKm', '권장 연장'],
];

/** 빈 칸은 '안 바꿈'이 아니라 '지움'이다 — 수요 하한은 근거가 없으면 비우는 게 정상이다 */
const toNumber = (v) => (v === '' || v == null ? null : Number(v));

export default function AdminPage({ onClose }) {
  const [parameters, setParameters] = useState([]);
  const [modes, setModes] = useState([]);
  const [draft, setDraft] = useState({});
  const [error, setError] = useState(null);
  const [saved, setSaved] = useState(null);

  useEffect(() => {
    Promise.all([api.adminParameters(), api.adminModes()])
      .then(([p, m]) => { setParameters(p); setModes(m); })
      .catch((e) => setError(e.message));
  }, []);

  const edit = (key, patch) =>
    setDraft((d) => ({ ...d, [key]: { ...d[key], ...patch } }));

  async function saveParameter(p) {
    const d = draft[p.paramName] || {};
    setError(null);
    try {
      const updated = await api.updateParameter(p.paramName, {
        value: Number(d.value ?? p.value),
        source: d.source ?? p.source,
      });
      setParameters((list) =>
        list.map((x) => (x.paramName === updated.paramName ? updated : x)));
      setDraft((x) => ({ ...x, [p.paramName]: undefined }));
      setSaved(p.paramName);
    } catch (e) {
      setError(e.message);
    }
  }

  async function saveMode(m) {
    const d = draft[m.modeType] || {};
    setError(null);
    try {
      const body = { source: d.source ?? m.source };
      MODE_FIELDS.forEach(([f]) => {
        body[f] = toNumber(d[f] !== undefined ? d[f] : m[f]);
      });
      const updated = await api.updateMode(m.modeType, body);
      setModes((list) =>
        list.map((x) => (x.modeType === updated.modeType ? updated : x)));
      setDraft((x) => ({ ...x, [m.modeType]: undefined }));
      setSaved(m.modeType);
    } catch (e) {
      setError(e.message);
    }
  }

  const sourceOf = (key, fallback) => draft[key]?.source ?? fallback ?? '';

  return (
    <div className="admin">
      <header>
        <strong>기준값 관리</strong>
        <span className="muted small">
          값을 바꾸면 근거도 함께 남겨야 합니다. 저장 즉시 다음 계산부터 적용됩니다.
        </span>
        <span className="spacer" />
        <button onClick={onClose}>지도로 돌아가기</button>
      </header>

      {error && <p className="error">{error}</p>}

      <section>
        <h2>편익 원단위 ({parameters.length})</h2>
        <p className="muted small">
          <code>demand_</code>로 시작하는 값은 <strong>회귀 결과</strong>입니다
          (<code>etl/demand_model.py</code>). 손으로 고치면 모델과 어긋나므로,
          바꾸려면 회귀를 다시 돌려 S6을 재생성하는 쪽이 맞습니다.
        </p>
        <table className="admin-table">
          <thead>
            <tr><th>이름</th><th>값</th><th>단위</th><th>근거</th><th /></tr>
          </thead>
          <tbody>
            {parameters.map((p) => {
              const d = draft[p.paramName] || {};
              const source = sourceOf(p.paramName, p.source);
              return (
                <tr key={p.paramName} className={saved === p.paramName ? 'just-saved' : ''}>
                  <td className="name">{p.paramName}</td>
                  <td>
                    <input
                      type="number" step="any"
                      value={d.value ?? p.value}
                      onChange={(e) => edit(p.paramName, { value: e.target.value })}
                    />
                  </td>
                  <td className="muted small">{p.unit}</td>
                  <td>
                    <input
                      value={source}
                      onChange={(e) => edit(p.paramName, { source: e.target.value })}
                      placeholder="어디서 온 값인지 적어 주세요"
                    />
                  </td>
                  <td>
                    <button onClick={() => saveParameter(p)} disabled={!source.trim()}>저장</button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </section>

      <section>
        <h2>수단 기준값 ({modes.length})</h2>
        <p className="muted small">
          수요 하한은 <strong>실제 운영 노선의 최저 단면</strong>입니다. 근거가 없으면
          비워 두세요 — 비우면 과잉 투자 경고를 띄우지 않습니다.
        </p>
        <table className="admin-table">
          <thead>
            <tr>
              <th>수단</th>
              {MODE_FIELDS.map(([f, label]) => <th key={f}>{label}</th>)}
              <th>근거</th><th />
            </tr>
          </thead>
          <tbody>
            {modes.map((m) => {
              const d = draft[m.modeType] || {};
              const source = sourceOf(m.modeType, m.source);
              return (
                <tr key={m.modeType} className={saved === m.modeType ? 'just-saved' : ''}>
                  <td className="name">{MODE_LABEL[m.modeType] || m.modeType}</td>
                  {MODE_FIELDS.map(([f]) => (
                    <td key={f}>
                      <input
                        type="number" step="any" className="narrow"
                        value={d[f] !== undefined ? d[f] : (m[f] ?? '')}
                        onChange={(e) => edit(m.modeType, { [f]: e.target.value })}
                      />
                    </td>
                  ))}
                  <td>
                    <input
                      value={source}
                      onChange={(e) => edit(m.modeType, { source: e.target.value })}
                      placeholder="어디서 온 값인지"
                    />
                  </td>
                  <td>
                    <button onClick={() => saveMode(m)} disabled={!source.trim()}>저장</button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </section>
    </div>
  );
}
