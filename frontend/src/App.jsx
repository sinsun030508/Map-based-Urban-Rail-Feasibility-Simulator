import { useEffect, useRef, useState } from 'react';
import { api, getUser, clearSession, saveSession, SESSION_EXPIRED, SESSION_EXPIRED_MESSAGE } from './api/client';
import { readCallback } from './auth/oauthCallback';
import AdminPage from './admin/AdminPage';
import LoginPage from './auth/LoginPage';
import MapView from './map/MapView';
import { usingVWorld } from './map/mapStyle';
import ComparePage from './scenario/ComparePage';
import { gainsNothing } from './scenario/gainsNothing';
import { mountainFor } from './scenario/mountainNote';
import { isStale } from './scenario/stale';
import ScenarioPanel from './scenario/ScenarioPanel';
import { resultKey } from './scenario/resultKey';

export default function App() {
  const [user, setUser] = useState(getUser);
  // 세션이 끊겨 로그인 화면으로 내려왔는가 — 아래 SESSION_EXPIRED 참고
  const [expired, setExpired] = useState(false);
  // 소셜 로그인이 실패해 돌아왔을 때의 안내(`#error=`) — 로그인 화면에 띄운다
  const [socialError, setSocialError] = useState(null);
  // 마지막으로 로그인해 있던 사람 — 다시 로그인한 사람이 다르면 앞 사람의 작업을 비운다(onLogin)
  const lastUserId = useRef(user?.userId ?? null);
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
  const [standardChanged, setStandardChanged] = useState(false);
  // 저장된 시나리오를 열 때마다 1씩 올린다 — 지도가 그 노선으로 옮겨 간다(`MapView` 의 `focus`)
  const [focus, setFocus] = useState(0);
  // 그린 선이 호수·저수지를 지나는지. 점이 바뀌면 다시 묻고, 묻는 사이 점이 또 바뀌면 버린다
  const [water, setWater] = useState(null);
  const [mountain, setMountain] = useState(null);
  const [zone, setZone] = useState(null);
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
   *
   * **왜 내려왔는지 로그인 화면에 적는다**(`expired`). 전에는 작업하다 말고 아무 말 없는
   * 로그인 화면이 떠서, 저장이 됐는지 왜 튕겼는지 알 수 없었다. 직접 로그아웃하면 적지 않는다
   */
  useEffect(() => {
    const drop = () => { setUser(null); setExpired(true); };
    window.addEventListener(SESSION_EXPIRED, drop);
    return () => window.removeEventListener(SESSION_EXPIRED, drop);
  }, []);

  /**
   * 소셜 로그인에서 돌아왔다 — `/oauth/callback#token=…`(`auth/oauthCallback.js`).
   *
   * **토큰을 주소에서 먼저 지운다** — `#` 뒤라 서버로는 안 가지만 주소창·방문 기록에는 남는다.
   * 그다음 그 토큰으로 `/me` 를 물어 닉네임·권한을 받고, 일반 로그인과 **같은 처리**(`signedIn`)를 탄다.
   * 개발 모드(StrictMode)는 이 효과를 두 번 부르는데, 두 번째는 주소가 이미 `/` 라 아무것도 안 한다
   */
  useEffect(() => {
    const back = readCallback(window.location);
    if (!back) return;
    window.history.replaceState(null, '', '/');
    // 실패는 로그인 화면(socialError)과 **지도 패널(error) 둘 다**에 둔다 — 이미 다른 계정으로 로그인해 있으면
    // 로그인 화면이 안 뜨므로, 패널에 안 두면 실패가 아무 말 없이 사라져 「로그인됐다」 로 보였다(11-05 카카오)
    if (back.error) {
      setSocialError(back.error);
      setError(back.error);
      return;
    }
    api.me(back.token)
      .then((me) => {
        const session = { accessToken: back.token, ...me };
        saveSession(session);
        signedIn(session);
      })
      // 토큰이 틀렸거나 그 사이 계정이 지워졌다. 「로그인이 필요합니다」 는 막 로그인한 사람에게 어색하다
      .catch(() => {
        const message = '소셜 로그인 확인에 실패했습니다 — 다시 시도해 주세요';
        setSocialError(message);
        setError(message);
      });
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

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

  /**
   * 점이나 구조가 바뀌면 0.6초 뒤 호수·저수지를 지나는지 묻는다(`/api/route/detour`).
   * **지하는 서버가 우회를 제안하지 않는다** — 물 밑으로 지나면 된다. 그래서 구조를 바꾸면
   * 다시 묻는다(지하 → 고가로 바꾸는 순간 제안이 떠야 한다).
   * **보조 기능이라 실패해도 그리기를 막지 않는다** — 안내만 사라진다.
   * 답이 오는 사이 점·구조가 또 바뀌었으면 그 답은 쓰지 않는다(`key` 로 맞춰 본다).
   *
   * **아래의 이른 `return`(로그인·관리자·시나리오 비교)보다 위에 있어야 한다.** 훅은 렌더마다
   * 같은 개수로 불려야 하는데, 처음엔 아래에 두어 시나리오 비교·관리자·로그아웃을 누르면
   * "Rendered fewer hooks" 로 화면이 통째로 깨졌다(`App.hooks.test.js` 가 막는다)
   */
  const waterKey = JSON.stringify([points, structure]);
  useEffect(() => {
    if (points.length < 2) {
      setWater(null);
      return undefined;
    }
    const key = waterKey;
    const t = setTimeout(() => {
      api.detour(points.map(([lng, lat]) => ({ latitude: lat, longitude: lng })), structure)
        .then((r) => setWater({ key, ...r }))
        .catch(() => setWater(null));
    }, 600);
    return () => clearTimeout(t);
  }, [points, structure]); // eslint-disable-line react-hooks/exhaustive-deps

  /**
   * 산 구간(`/api/route/mountain`) — 호수와 같은 틀이다(0.6초 뒤, `key` 로 낡은 답을 버린다, 실패해도
   * 그리기를 막지 않는다). 긴 노선은 몇 초 걸린다(서울~부산 3초). **이 훅도 아래의 이른 `return` 보다 위에 있어야 한다**
   *
   * **구조 선택창과 상관없이 땅 위 기준으로 묻는다**(11-12). 선택창의 구조로 물었더니 지하를 고른 채 계산한 뒤 표에서 트램·고가
   * 행을 골라도 산 안내가 안 떴다 — 그 행들의 건설비엔 터널비가 들어 있는데. 보일지는 지도에 그린 행의 구조로 정한다(아래 `mountainShown`)
   */
  const pointsKey = JSON.stringify(points);
  useEffect(() => {
    if (points.length < 2) {
      setMountain(null);
      return undefined;
    }
    const key = pointsKey;
    const t = setTimeout(() => {
      api.mountain(points.map(([lng, lat]) => ({ latitude: lat, longitude: lng })), 'ELEVATED')
        .then((r) => setMountain({ key, ...r }))
        .catch(() => setMountain(null));
    }, 600);
    return () => clearTimeout(t);
  }, [points]); // eslint-disable-line react-hooks/exhaustive-deps

  /**
   * 궁궐·상수원·군사 구역(`/api/route/zones`, 11-12 사용자 요청) — 선이 원을 지나면 알리고 피해 가는 선형을 제안한다. 호수와 같은 틀이고
   * **구조와 상관없다**(문화재 구역은 지하 굴착이 문제다). **이 훅도 아래의 이른 `return` 보다 위에 있어야 한다**
   */
  useEffect(() => {
    if (points.length < 2) {
      setZone(null);
      return undefined;
    }
    const key = pointsKey;
    const t = setTimeout(() => {
      api.zoneDetour(points.map(([lng, lat]) => ({ latitude: lat, longitude: lng })))
        .then((r) => setZone({ key, ...r }))
        .catch(() => setZone(null));
    }, 600);
    return () => clearTimeout(t);
  }, [points]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!user) {
    return (
      <LoginPage
        notice={expired ? SESSION_EXPIRED_MESSAGE : socialError}
        onLogin={signedIn}
      />
    );
  }
  // **권한을 본다.** 관리자 화면에서 세션이 끊기면 `admin` 이 켜진 채 남아, 그 자리에서 일반
  // 사용자가 로그인하면 관리자 화면이 그려졌다(서버가 요청은 막지만 화면이 보였다)
  if (admin && user.role === 'ADMIN') {
    // 관리자가 기준값을 고쳤을 수 있다. 다시 받지 않으면 낡은 결과를 알릴 수 없다
    return <AdminPage me={user.userId} onClose={() => { setAdmin(false); refreshReference(); }} />;
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
    setStandardChanged(false);
    setExistingOption(null);
  }

  /** 새 노선을 그리기 시작한다 — 고치던 시나리오와의 연결도 끊는다 */
  /**
   * 로그인했다 — 이메일 로그인(`LoginPage`)과 소셜 로그인 콜백이 함께 쓴다.
   *
   * **같은 사람이면 하던 작업을 그대로 둔다** — 만료로 튕긴 뒤 다시 들어오면 그리던 노선이
   * 남아 있어야 한다. **다른 사람이면 비운다** — 앞 사람이 고치던 시나리오 번호가 남아
   * 「수정 저장」 이 남의 시나리오로 가고, 그리던 노선·비교 화면도 남의 것이다
   */
  function signedIn(session) {
    if (session.userId !== lastUserId.current) {
      clearAll();
      setCompare(false);
      setAdmin(false);
    }
    lastUserId.current = session.userId;
    setExpired(false);
    setSocialError(null);
    setUser(session);
  }

  function clearAll() {
    setPoints([]);
    setTitle('');
    setEditingId(null);
    reset();
  }

  const waterNow = water && water.key === waterKey ? water : null;
  // 지도에 덧그릴 물 위 토막 — 패널과 같은 기준(50m 이상)으로 거른다
  const waterPieces = (waterNow?.waterKm || [])
    .filter((w) => w.km >= 0.05)
    .flatMap((w) => w.pieces || []);

  /** 제안된 우회 선형으로 바꾼다 — 바꾸는 것은 사용자다(그린 선이 설계 의도) */
  function applyDetour() {
    setPoints(waterNow.points.map((p) => [Number(p.longitude), Number(p.latitude)]));
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
   *
   * B/C 0 인 안(절감 시간 없음)도 내세우지 않는다 — 비교표의 굵은 행·백엔드 추천과 같은
   * 규칙이다. 기존 KTX 보다 느린 서울~부산 신설안이 전부 0 이면 선만 그린다.
   */
  const shown = results.length
    ? results.find((r) => resultKey(r) === selectedResult)
      || [...results].filter((r) => !overLength(r) && !gainsNothing(r))
        .sort((a, b) => (b.bcRatio ?? -1) - (a.bcRatio ?? -1))[0]
    : null;

  // 비교표에서 고른 행의 수단을 따로 따진 안이 있으면(트램) 그것을 쓴다 — 지도 토막·점선, 패널 문구, 「바꾸기」 가 같은 안을 본다.
  // **지하는 이미 땅속이라 산을 안내하지 않는다** — 계산 뒤엔 지도에 그린 행의 구조, 계산 전엔 선택창의 구조로 가른다
  const mountainStructure = shown ? shown.structureType : structure;
  const mountainNow = mountainStructure === 'UNDERGROUND' ? null
    : mountainFor(mountain && mountain.key === pointsKey ? mountain : null, shown);
  const zoneNow = zone && zone.key === pointsKey ? zone : null;
  // 터널 토막(갈색)과 돌아가는 안의 미리보기(점선) — 돌아갈 수 있는 구간이 있을 때만 미리보기를 그린다
  const mountainPieces = (mountainNow?.mountains || []).flatMap((m) => m.pieces || []);
  const mountainPreview = (mountainNow?.mountains || []).some((m) => m.detoured)
    ? mountainNow.points.map((p) => [Number(p.longitude), Number(p.latitude)])
    : null;

  /** 구역을 피해 가는 선형으로 바꾼다 — 피할 수 있는 구역만 돌고 나머지는 그대로다 */
  function applyZoneDetour() {
    setPoints(zoneNow.points.map((p) => [Number(p.longitude), Number(p.latitude)]));
    reset();
  }

  /** 산을 돌아가는 선형으로 바꾼다 — 돌 수 있는 구간만 돌고 나머지는 그대로다 */
  function applyMountainDetour() {
    setPoints(mountainNow.points.map((p) => [Number(p.longitude), Number(p.latitude)]));
    reset();
  }

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
      setStandardChanged(Boolean(saved.standardChanged));
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
      setStandardChanged(Boolean(s.standardChanged));
      setExistingOption(s.existingOption ?? null);
      setFocus((f) => f + 1);
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
      setStandardChanged(Boolean(detail.standardChanged));
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
          existingOption={existingOption}
          show3D={show3D}
          focus={focus}
          waterPieces={waterPieces}
          mountainPieces={mountainPieces}
          mountainPreview={mountainPreview}
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
          stale={isStale(calculatedAt, referenceUpdatedAt, standardChanged)}
          existingOption={existingOption}
          overLength={overLength}
          water={waterNow}
          onApplyDetour={applyDetour}
          mountain={mountainNow}
          zone={zoneNow}
          onApplyZoneDetour={applyZoneDetour}
          mountainRow={shown}
          onApplyMountainDetour={applyMountainDetour}
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
