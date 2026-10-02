import { useEffect, useState } from 'react';
import { api, getUser, clearSession } from './api/client';
import AdminPage from './admin/AdminPage';
import LoginPage from './auth/LoginPage';
import MapView from './map/MapView';
import { usingVWorld } from './map/mapStyle';
import ScenarioPanel from './scenario/ScenarioPanel';
import { key as resultKey } from './scenario/CostTable';

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
  const [results, setResults] = useState([]);
  const [population, setPopulation] = useState(null);
  const [selectedResult, setSelectedResult] = useState(null);
  const [show3D, setShow3D] = useState(false);
  const [admin, setAdmin] = useState(false);
  const [calculating, setCalculating] = useState(false);

  useEffect(() => {
    if (!user) return;
    api.listScenarios().then(setScenarios).catch((e) => setError(e.message));
    api.zones().then(setZones).catch(() => {});
  }, [user]);

  if (!user) return <LoginPage onLogin={setUser} />;
  if (admin) return <AdminPage onClose={() => setAdmin(false)} />;

  /** 노선이 바뀌면 이전 계산 결과는 더 이상 맞지 않는다 */
  function reset() {
    setSavedLength(null);
    setSavedId(null);
    setResults([]);
    setPopulation(null);
    setSelectedResult(null);
  }

  /** 비교표에서 고른 안의 정차역. 안 골랐으면 B/C 가 가장 높은 안을 보여 준다 */
  const shown = results.length
    ? results.find((r) => resultKey(r) === selectedResult)
      || [...results].sort((a, b) => (b.bcRatio ?? -1) - (a.bcRatio ?? -1))[0]
    : null;

  async function save() {
    setSaving(true);
    setError(null);
    try {
      const saved = await api.createScenario({
        title: title.trim(),
        preferredStructure: structure,
        points: points.map(([lng, lat]) => ({ latitude: lat, longitude: lng })),
      });
      setSavedLength(Number(saved.totalLengthKm));
      setSavedId(saved.id);
      setResults(saved.results || []);
      setPopulation(saved.population1km ?? null);
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
      setResults(s.results || []);
      setPopulation(s.population1km ?? null);
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
    } catch (e) {
      setError(e.message);
    } finally {
      setCalculating(false);
    }
  }

  async function remove(id) {
    try {
      await api.deleteScenario(id);
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
          selectedResult={selectedResult}
          onSelectResult={setSelectedResult}
          onTitle={setTitle}
          onStructure={setStructure}
          onUndo={() => { setPoints((p) => p.slice(0, -1)); reset(); }}
          onClear={() => { setPoints([]); reset(); }}
          onSave={save}
          onCalculate={calculate}
          onLoad={load}
          onDelete={remove}
        />
      </main>
    </div>
  );
}
