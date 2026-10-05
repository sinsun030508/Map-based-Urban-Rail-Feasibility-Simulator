import { Fragment, useEffect, useState } from 'react';
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

/**
 * 편익 원단위는 비울 수 없다. `Number('')` 가 0 이라 그대로 두면 빈 칸을 저장했을 때
 * 할인율 같은 값이 **조용히 0 으로 덮어써진다.**
 */
const isNumber = (v) => v !== '' && v != null && Number.isFinite(Number(v));

export default function AdminPage({ onClose }) {
  const [parameters, setParameters] = useState([]);
  const [modes, setModes] = useState([]);
  const [draft, setDraft] = useState({});
  /**
   * 저장 실패는 **실패한 행에 붙여** 보여 준다. 기준값이 25행이라 화면 한 장에 안 들어가는데,
   * 맨 위에 한 줄로 띄우면 아래쪽 행을 저장했을 때 메시지가 화면 밖에 그려져
   * **저장 버튼이 먹지 않는 것처럼 보인다.** (`key: null` 은 행이 없는 불러오기 실패다.)
   */
  const [error, setError] = useState(null);
  const [saved, setSaved] = useState(null);

  useEffect(() => {
    Promise.all([api.adminParameters(), api.adminModes()])
      .then(([p, m]) => { setParameters(p); setModes(m); })
      .catch((e) => setError({ key: null, message: e.message }));
  }, []);

  // 저장 표시는 잠깐만 둔다 — 계속 남으면 '지금 저장했다'는 말이 거짓이 된다
  useEffect(() => {
    if (!saved) {
      return undefined;
    }
    const timer = setTimeout(() => setSaved(null), 3000);
    return () => clearTimeout(timer);
  }, [saved]);

  // 다시 고치기 시작하면 앞선 실패 메시지는 치운다
  const edit = (key, patch) => {
    setError((e) => (e?.key === key ? null : e));
    setDraft((d) => ({ ...d, [key]: { ...d[key], ...patch } }));
  };

  async function saveParameter(p) {
    const d = draft[p.paramName] || {};
    setError(null);
    setSaved(null);          // 같은 행을 다시 저장해도 표시가 되살아나게
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
      setError({ key: p.paramName, message: e.message });
    }
  }

  async function saveMode(m) {
    const d = draft[m.modeType] || {};
    setError(null);
    setSaved(null);
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
      setError({ key: m.modeType, message: e.message });
    }
  }

  const sourceOf = (key, fallback) => draft[key]?.source ?? fallback ?? '';

  /** 실패한 행 바로 아래에 한 줄. 열 너비를 흔들지 않게 별도 행으로 둔다 */
  const rowError = (key, span) => (error?.key === key ? (
    <tr className="row-error">
      <td />
      <td colSpan={span} className="error">{error.message}</td>
    </tr>
  ) : null);

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

      {/* 행이 없는 실패(불러오기)만 위에 띄운다 — 저장 실패는 해당 행 아래로 간다 */}
      {error && error.key == null && <p className="error">{error.message}</p>}

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
                <Fragment key={p.paramName}>
                <tr className={saved === p.paramName ? 'just-saved' : ''}>
                  <td className="name">{p.paramName}</td>
                  <td className="value">
                    <input
                      type="number" step="any"
                      value={d.value ?? p.value}
                      onChange={(e) => edit(p.paramName, { value: e.target.value })}
                    />
                  </td>
                  <td className="muted small unit">{p.unit}</td>
                  <td className="source">
                    <input
                      value={source}
                      onChange={(e) => edit(p.paramName, { source: e.target.value })}
                      placeholder="어디서 온 값인지 적어 주세요"
                    />
                  </td>
                  <td>
                    <button
                      onClick={() => saveParameter(p)}
                      disabled={!source.trim() || !isNumber(d.value ?? p.value)}
                    >
                      저장
                    </button>
                  </td>
                </tr>
                {rowError(p.paramName, 4)}
                </Fragment>
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
                <Fragment key={m.modeType}>
                <tr className={saved === m.modeType ? 'just-saved' : ''}>
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
                  <td className="source">
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
                {rowError(m.modeType, MODE_FIELDS.length + 2)}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </section>
    </div>
  );
}
