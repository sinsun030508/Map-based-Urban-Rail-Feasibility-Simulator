import { useEffect, useState } from 'react';
import { api, getUser, clearSession } from './api/client';
import AdminPage from './admin/AdminPage';
import LoginPage from './auth/LoginPage';
import MapView from './map/MapView';
import { usingVWorld } from './map/mapStyle';
import ComparePage from './scenario/ComparePage';
import { isStale } from './scenario/stale';
import ScenarioPanel from './scenario/ScenarioPanel';
import { resultKey } from './scenario/resultKey';

export default function App() {
  const [user, setUser] = useState(getUser);
  const [points, setPoints] = useState([]);
  const [title, setTitle] = useState('');
  const [structure, setStructure] = useState('UNDERGROUND');
  const [scenarios, setScenarios] = useState([]);
  const [zones, setZones] = useState([]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [savedLength, setSavedLength] = useState(null);
  const [savedId, setSavedId] = useState(null);
  // 불러온(또는 방금 만든) 시나리오. 노선을 고쳐도 유지돼야 '수정 저장'을 할 수 있다
  const [editingId, setEditingId] = useState(null);
  const [results, setResults] = useState([]);
  const [population, setPopulation] = useState(null);
  const [selectedResult, setSelectedResult] = useState(null);
  const [show3D, setShow3D] = useState(false);
  const [admin, setAdmin] = useState(false);
  const [compare, setCompare] = useState(false);
  const [calculatedAt, setCalculatedAt] = useState(null);
  const [referenceUpdatedAt, setReferenceUpdatedAt] = useState(null);
  const [calculating, setCalculating] = useState(false);

  /** 기준값이 마지막으로 바뀐 시각 — 저장된 결과가 낡았는지 판단하는 기준이다 */
  function refreshReference() {
    api.referenceUpdatedAt().then(setReferenceUpdatedAt).catch(() => {});
  }

  useEffect(() => {
    if (!user) return;
    api.listScenarios().then(setScenarios).catch((e) => setError(e.message));
    api.zones().then(setZones).catch(() => {});
    refreshReference();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user]);

  if (!user) return <LoginPage onLogin={setUser} />;
  if (admin) {
    // 관리자가 기준값을 고쳤을 수 있다. 다시 받지 않으면 낡은 결과를 알릴 수 없다
    return <AdminPage onClose={() => { setAdmin(false); refreshReference(); }} />;
  }
  if (compare) return <ComparePage onClose={() => setCompare(false)} />;

  /**
   * 노선이 바뀌면 이전 계산 결과는 더 이상 맞지 않는다.
   * 다만 **어느 시나리오를 고치는 중인지(editingId)는 남긴다** — 이걸 같이 지우면
   * 불러와서 노선을 손본 뒤 저장할 때 같은 제목의 시나리오가 하나 더 생긴다.
   */
  function reset() {
    setSavedLength(null);
    setSavedId(null);
    setResults([]);
    setPopulation(null);
    setSelectedResult(null);
    setCalculatedAt(null);
  }

  /** 새 노선을 그리기 시작한다 — 고치던 시나리오와의 연결도 끊는다 */
  function clearAll() {
    setPoints([]);
    setTitle('');
    setEditingId(null);
    reset();
  }

  /** 비교표에서 고른 안의 정차역. 안 골랐으면 B/C 가 가장 높은 안을 보여 준다 */
  const shown = results.length
    ? results.find((r) => resultKey(r) === selectedResult)
      || [...results].sort((a, b) => (b.bcRatio ?? -1) - (a.bcRatio ?? -1))[0]
    : null;

  /** @param asNew true 면 고치던 시나리오와 별개로 하나 더 만든다 */
  async function save(asNew = false) {
    setSaving(true);
    setError(null);
    try {
      const body = {
        title: title.trim(),
        preferredStructure: structure,
        points: points.map(([lng, lat]) => ({ latitude: lat, longitude: lng })),
      };
      const saved = editingId && !asNew
        ? await api.updateScenario(editingId, body)
        : await api.createScenario(body);
      setSavedLength(Number(saved.totalLengthKm));
      setSavedId(saved.id);
      setEditingId(saved.id);
      setResults(saved.results || []);
      setPopulation(saved.population1km ?? null);
      setCalculatedAt(saved.calculatedAt ?? null);
      setScenarios(await api.listScenarios());
    } catch (e) {
      setError(e.message);
    } finally {
      setSaving(false);
    }
  }

  async function load(id) {
    setError(null);
    try {
      const s = await api.getScenario(id);
      setPoints(s.points.map((p) => [Number(p.longitude), Number(p.latitude)]));
      setTitle(s.title);
      setStructure(s.preferredStructure || 'UNDERGROUND');
      setSavedLength(Number(s.totalLengthKm));
      setSavedId(s.id);
      setEditingId(s.id);
      setResults(s.results || []);
      setPopulation(s.population1km ?? null);
      setCalculatedAt(s.calculatedAt ?? null);
    } catch (e) {
      setError(e.message);
    }
  }

  async function calculate() {
    setCalculating(true);
    setError(null);
    try {
      const detail = await api.calculate(savedId);
      setResults(detail.results || []);
      setPopulation(detail.population1km ?? null);
      setCalculatedAt(detail.calculatedAt ?? null);
    } catch (e) {
      setError(e.message);
    } finally {
      setCalculating(false);
    }
  }

  async function remove(id) {
    try {
      await api.deleteScenario(id);
      if (editingId === id) clearAll();
      setScenarios(await api.listScenarios());
    } catch (e) {
      setError(e.message);
    }
  }

  function logout() {
    clearSession();
    setUser(null);
  }

  return (
    <div className="app">
      <header>
        <strong>RailFeas</strong>
        <span className="muted small">
          {usingVWorld ? 'VWorld 지도' : 'OpenFreeMap 지도 (VWorld 키 미설정)'}
        </span>
        <span className="spacer" />
        <button className="link" onClick={() => setCompare(true)}>시나리오 비교</button>
        {user.role === 'ADMIN' && (
          <button className="link" onClick={() => setAdmin(true)}>기준값 관리</button>
        )}
        <button
          className={show3D ? 'toggle on' : 'toggle'}
          onClick={() => setShow3D((v) => !v)}
          title="집계구 인구를 육각형 높이로 표시합니다"
        >
          인구 3D {show3D ? '끄기' : '보기'}
        </button>
        <span className="muted">{user.nickname}</span>
        <button className="link" onClick={logout}>로그아웃</button>
      </header>

      <main>
        <MapView
          points={points}
          zones={zones}
          stations={shown?.stations}
          show3D={show3D}
          onPick={(p) => {
            setPoints((prev) => [...prev, p]);
            reset();
          }}
        />
        <ScenarioPanel
          points={points}
          title={title}
          structure={structure}
          scenarios={scenarios}
          saving={saving}
          error={error}
          savedLength={savedLength}
          savedId={savedId}
          results={results}
          calculating={calculating}
          population={population}
          stale={isStale(calculatedAt, referenceUpdatedAt)}
          selectedResult={selectedResult}
          onSelectResult={setSelectedResult}
          onTitle={setTitle}
          onStructure={setStructure}
          editingId={editingId}
          onUndo={() => { setPoints((p) => p.slice(0, -1)); reset(); }}
          onClear={clearAll}
          onSave={save}
          onCalculate={calculate}
          onLoad={load}
          onDelete={remove}
        />
      </main>
    </div>
  );
}
