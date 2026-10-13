import { useEffect, useState } from 'react';
import { api, getUser, clearSession, SESSION_EXPIRED } from './api/client';
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
  // **"없음"과 "못 불러옴"은 다르다.** 목록이 비었을 때 무조건 "저장된 시나리오가
  // 없습니다"라고 쓰면, 서버가 꺼져 있거나 세션이 끊긴 사람에게 **저장한 것이 사라졌다고**
  // 말하는 셈이다. 실제로 오늘 아침 백엔드가 내려가 있을 때 그렇게 보였다
  const [scenariosLoaded, setScenariosLoaded] = useState(false);
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
  const [existingOption, setExistingOption] = useState(null);
  const [referenceUpdatedAt, setReferenceUpdatedAt] = useState(null);
  // 수단별 권장 연장(max_length_km) — 기본으로 그릴 정차역을 고를 때 쓴다
  const [modes, setModes] = useState([]);
  const [calculating, setCalculating] = useState(false);

  /** 기준값이 마지막으로 바뀐 시각 — 저장된 결과가 낡았는지 판단하는 기준이다 */
  function refreshReference() {
    api.referenceUpdatedAt().then(setReferenceUpdatedAt).catch(() => {});
  }

  /**
   * 세션이 끊기면 **로그인 화면으로 내린다.**
   *
   * `client.js` 의 `clearSession()` 은 localStorage 만 비운다. React 는 마운트 때 읽은
   * `user` 를 그대로 들고 있어서, 그것만으로는 화면이 로그인된 척하며 목록을
   * **"저장된 시나리오가 없습니다"** 로 보여 준다 — 세션이 끊겼다는 말이 아니라
   * "당신은 저장한 것이 없다"는 **거짓말**이다. 저장해 둔 노선이 있는 사람은 지워진 줄 안다.
   *
   * 만료는 아무 호출에서나 터지므로(저장·계산·삭제·관리자) 호출마다 검사하지 않고
   * 여기 한 곳에서 듣는다.
   */
  useEffect(() => {
    const drop = () => setUser(null);
    window.addEventListener(SESSION_EXPIRED, drop);
    return () => window.removeEventListener(SESSION_EXPIRED, drop);
  }, []);

  useEffect(() => {
    if (!user) return;
    // 로그인한 사람이 바뀌면 앞 사람의 오류는 치운다. 낡은 토큰으로 403 을 받고 다시
    // 로그인하면 목록은 정상인데 "요청 실패 (403)" 만 화면에 남아 있었다
    setError(null);
    // 못 불러온 것과 "없음"은 다르다 — 아래 scenariosLoaded 참고
    setScenariosLoaded(false);
    api.listScenarios()
      .then((list) => { setScenarios(list); setError(null); setScenariosLoaded(true); })
      .catch((e) => setError(e.message));
    api.zones().then(setZones).catch(() => {});
    api.modes().then(setModes).catch(() => {});
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
    setExistingOption(null);
  }

  /** 새 노선을 그리기 시작한다 — 고치던 시나리오와의 연결도 끊는다 */
  function clearAll() {
    setPoints([]);
    setTitle('');
    setEditingId(null);
    reset();
  }

  /** 그린 노선이 그 수단의 권장 연장을 넘는가 */
  const overLength = (r) => {
    const limit = modes.find((m) => m.modeType === r.modeType)?.maxLengthKm;
    return limit != null && savedLength != null && savedLength > Number(limit);
  };

  /**
   * 비교표에서 고른 안의 정차역. 안 골랐으면 **권장 연장 안에 드는** 안 중 B/C 최고안이다.
   *
   * 그냥 B/C 최고안을 그렸더니 인천~세종(120km)에서 권장 연장 30km 인 BRT 가 뽑혀
   * 역 60개가 지도를 덮었다. 표에는 그대로 남기고(선택을 막지 않는다) 행을 누르면
   * 그린다 — 기본 그림으로 내세우지만 않는다. 맞는 안이 없으면 선만 그린다.
   */
  const shown = results.length
    ? results.find((r) => resultKey(r) === selectedResult)
      || [...results].filter((r) => !overLength(r))
        .sort((a, b) => (b.bcRatio ?? -1) - (a.bcRatio ?? -1))[0]
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
      setExistingOption(saved.existingOption ?? null);
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
      setExistingOption(s.existingOption ?? null);
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
      setExistingOption(detail.existingOption ?? null);
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
          scenariosLoaded={scenariosLoaded}
          saving={saving}
          error={error}
          savedLength={savedLength}
          savedId={savedId}
          results={results}
          calculating={calculating}
          population={population}
          stale={isStale(calculatedAt, referenceUpdatedAt)}
          existingOption={existingOption}
          // 표의 강조는 지도에 실제로 그린 안을 따른다 — 둘이 다른 안을 가리키면 안 된다
          selectedResult={shown ? resultKey(shown) : null}
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
