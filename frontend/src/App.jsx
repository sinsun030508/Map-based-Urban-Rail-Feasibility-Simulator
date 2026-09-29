import { useEffect, useState } from 'react';
import { api, getUser, clearSession } from './api/client';
import LoginPage from './auth/LoginPage';
import MapView from './map/MapView';
import { usingVWorld } from './map/mapStyle';
import ScenarioPanel from './scenario/ScenarioPanel';

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

  useEffect(() => {
    if (!user) return;
    api.listScenarios().then(setScenarios).catch((e) => setError(e.message));
    api.zones().then(setZones).catch(() => {});
  }, [user]);

  if (!user) return <LoginPage onLogin={setUser} />;

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
    } catch (e) {
      setError(e.message);
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
        <span className="muted">{user.nickname}</span>
        <button className="link" onClick={logout}>로그아웃</button>
      </header>

      <main>
        <MapView
          points={points}
          zones={zones}
          onPick={(p) => {
            setPoints((prev) => [...prev, p]);
            setSavedLength(null);
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
          onTitle={setTitle}
          onStructure={setStructure}
          onUndo={() => { setPoints((p) => p.slice(0, -1)); setSavedLength(null); }}
          onClear={() => { setPoints([]); setSavedLength(null); }}
          onSave={save}
          onLoad={load}
          onDelete={remove}
        />
      </main>
    </div>
  );
}
