import { useEffect, useRef, useState } from 'react';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { mapStyle, INITIAL_VIEW } from './mapStyle';
import { routeThroughStations } from '../geo/routeLine';
import usePopulation3D from './usePopulation3D';

const ROUTE_SOURCE = 'route';
const EXISTING_SOURCE = 'existing-route';
const WATER_SOURCE = 'water-pieces';
const MOUNTAIN_SOURCE = 'mountain-pieces';
const MOUNTAIN_PREVIEW_SOURCE = 'mountain-preview';

/**
 * 지도와 노선 표시. 좌표 상태는 App 이 들고 있고 여기서는 그리기만 한다.
 * onPick(lngLat) — 지도를 클릭하면 좌표를 올려보낸다.
 * stations — 자동 배치된 정차역. 수단마다 다르므로 비교표에서 고른 안의 것을 받는다.
 * show3D — 인구밀도 3D 레이어. 켜면 지도가 기울고 집계구 인구가 높이로 선다.
 * focus — 바뀔 때마다 지도를 노선에 맞춘다. 저장된 시나리오를 열 때만 App 이 올린다.
 * waterPieces — 노선 중 물 위 토막들([[위도,경도],[위도,경도]]). 빨간 선 위에 파랗게 덧그린다.
 * mountainPieces — 35‰ 보다 가팔라 터널이 필요한 토막들(같은 모양). 갈색으로 덧그린다.
 *   0.1km 토막은 점 하나뿐이라 선이 안 그려진다 — 그런 토막은 갈색 동그라미로 찍는다.
 * mountainPreview — 산을 돌아가는 안의 선형([경도,위도] 목록). 바꾸기 전 미리보기라 점선이다.
 */
export default function MapView({
  points, zones, stations, existingOption, show3D, focus, waterPieces, mountainPieces, mountainPreview,
  onPick,
}) {
  const containerRef = useRef(null);
  const mapRef = useRef(null);
  const markersRef = useRef([]);
  const stationMarkersRef = useRef([]);
  const existingMarkersRef = useRef([]);
  // **지도가 준비됐는지를 상태로 둔다.** 'load' 이벤트에 직접 매달면
  // StrictMode 가 지도를 두 번 만드는 개발 환경에서 순서가 어긋나,
  // 지도가 뜨기 전에 시나리오를 연 경우 점이 영영 안 그려졌다.
  // **준비의 기준은 `load` 가 아니라 `style.load` 다.** `load`·`isStyleLoaded()` 는 타일까지 다 받았다는
  // 뜻이라, 타일 하나가 안 끝나면 노선이 통째로 멈추고(11-06), `load` 는 한 번뿐이라 그 뒤 타일을 받는
  // 사이에 미룬 그리기는 영영 안 돈다. 소스·레이어는 스타일만 있으면 만들 수 있다
  const [mapReady, setMapReady] = useState(false);
  const onPickRef = useRef(onPick);
  onPickRef.current = onPick;
  const pointsRef = useRef(points);
  pointsRef.current = points;

  // 지도는 한 번만 만든다 (스타일 로드가 느려 재생성하면 깜빡인다)
  useEffect(() => {
    const map = new maplibregl.Map({
      container: containerRef.current,
      style: mapStyle,
      center: INITIAL_VIEW.center,
      zoom: INITIAL_VIEW.zoom,
      pitch: 0,
    });
    map.addControl(new maplibregl.NavigationControl({ visualizePitch: true }), 'top-right');
    map.addControl(new maplibregl.ScaleControl({ unit: 'metric' }));

    map.on('style.load', () => {
      map.addSource(ROUTE_SOURCE, {
        type: 'geojson',
        data: { type: 'Feature', geometry: { type: 'LineString', coordinates: [] } },
      });
      map.addLayer({
        id: 'route-line',
        type: 'line',
        source: ROUTE_SOURCE,
        paint: { 'line-color': '#e2483a', 'line-width': 4 },
      });

      // 기존 노선(KTX) — 그린 노선과 **다른 색·점선**으로 둔다.
      // 점선인 이유: 가진 것이 두 역의 좌표뿐이라 **실제 선형이 아니다.**
      // 실선으로 그리면 빨간 노선과 같은 급으로 읽혀 "가지도 않는 길" 을 그리는 셈이다.
      map.addSource(EXISTING_SOURCE, {
        type: 'geojson',
        data: { type: 'Feature', geometry: { type: 'LineString', coordinates: [] } },
      });
      map.addLayer({
        id: 'existing-line',
        type: 'line',
        source: EXISTING_SOURCE,
        paint: {
          'line-color': '#0f766e',
          'line-width': 4,
          'line-dasharray': [2, 1.5],
        },
      }, 'route-line');        // 그린 노선 아래로

      // 물 위 토막 — 그린 노선 **위에** 같은 굵기로 덧그려 그 부분만 파랗게 바뀌어 보이게 한다.
      // 패널의 「물 위 구간 한강 1.2km」 가 선의 어디인지 보여 준다(교량 할증이 빠진 자리)
      map.addSource(WATER_SOURCE, {
        type: 'geojson',
        data: { type: 'Feature', geometry: { type: 'MultiLineString', coordinates: [] } },
      });
      map.addLayer({
        id: 'water-piece-line',
        type: 'line',
        source: WATER_SOURCE,
        paint: { 'line-color': '#1d7fc4', 'line-width': 5 },
      });

      // 돌아가는 안 — 바꾸기 전 미리보기라 그린 노선 **아래** 점선. 실선이면 이미 바뀐 것으로 읽힌다
      map.addSource(MOUNTAIN_PREVIEW_SOURCE, {
        type: 'geojson',
        data: { type: 'Feature', geometry: { type: 'LineString', coordinates: [] } },
      });
      map.addLayer({
        id: 'mountain-preview-line',
        type: 'line',
        source: MOUNTAIN_PREVIEW_SOURCE,
        paint: { 'line-color': '#8b5a2b', 'line-width': 3, 'line-dasharray': [1.5, 1.5] },
      }, 'route-line');

      // 터널 토막 — 물 위 토막처럼 그린 노선 위에 덧그린다
      map.addSource(MOUNTAIN_SOURCE, {
        type: 'geojson',
        data: { type: 'FeatureCollection', features: [] },
        attribution: '지형 © Mapzen·SRTM (AWS Terrain Tiles)',
      });
      map.addLayer({
        id: 'mountain-piece-line',
        type: 'line',
        source: MOUNTAIN_SOURCE,
        filter: ['==', ['geometry-type'], 'LineString'],
        paint: { 'line-color': '#8b5a2b', 'line-width': 5 },
      });
      map.addLayer({
        id: 'mountain-piece-dot',
        type: 'circle',
        source: MOUNTAIN_SOURCE,
        filter: ['==', ['geometry-type'], 'Point'],
        paint: { 'circle-color': '#8b5a2b', 'circle-radius': 3.5 },
      });

      setMapReady(true);
    });

    map.on('click', (e) => onPickRef.current?.([e.lngLat.lng, e.lngLat.lat]));
    mapRef.current = map;

    // 컨테이너 크기를 못 재면 MapLibre 는 400×300 으로 만들어 두고 그대로 둔다.
    // 그러면 지도 일부만 그려지고 **빈 영역을 클릭했을 때 좌표까지 어긋난다** —
    // 노선을 찍는 게 이 서비스의 첫 동작이라 그냥 둘 수 없다.
    const resize = new ResizeObserver(() => map.resize());
    resize.observe(containerRef.current);

    return () => {
      resize.disconnect();
      map.remove();
    };
  }, []);

  usePopulation3D(mapRef, show3D);

  // 좌표가 바뀌면 선을 다시 그린다
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady) return;
    // 그린 점만 잇지 않는다 — 역이 선 옆으로 비켜 서 있으면 선도 그리로 돈다.
    // 비용·편익은 이미 돌아간 길이로 계산하므로(CostCalculator 의 detourKm),
    // 지도만 직선이면 화면과 숫자가 서로 다른 노선을 말하게 된다
    map.getSource(ROUTE_SOURCE).setData({
      type: 'Feature',
      geometry: { type: 'LineString', coordinates: routeThroughStations(points, stations) },
    });
  }, [points, stations, mapReady]);

  // 마커는 DOM 이라 스타일을 기다릴 것 없다
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    markersRef.current.forEach((m) => m.remove());
    // 가운데 점은 선이 꺾이는 경유점일 뿐 정차역이 아니다. 번호를 달면 역처럼 읽혀
    // "클릭한 곳이 역이 된다"고 오해한다 — 역은 계산 후 StationPlanner 가 놓는다
    markersRef.current = points.map((p, i) => {
      const el = document.createElement('div');
      const terminal = i === 0 || i === points.length - 1;
      if (terminal) {
        el.className = 'route-marker';
        el.textContent = i === 0 ? '출발' : '도착';
      } else {
        el.className = 'route-waypoint';
        el.title = '경유점 — 노선이 지나는 점 (정차역 아님)';
      }
      return new maplibregl.Marker({ element: el }).setLngLat(p).addTo(map);
    });
  }, [points, stations]);

  // 자동 배치된 정차역. 종점은 사용자가 찍은 점과 겹치므로 작은 점으로만 둔다
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    stationMarkersRef.current.forEach((m) => m.remove());
    stationMarkersRef.current = (stations || []).map((s, i, all) => {
      const el = document.createElement('div');
      const terminal = i === 0 || i === all.length - 1;
      el.className = terminal ? 'station-dot terminal' : 'station-dot';
      // **이름을 모르면 글자를 달지 않는다.** 역 이름은 반경 안 집계구의 행정동인데, 값이 가려진 곳은 칸이 없다.
      // 집계구를 전국으로 받기 전에는 서울~부산에 이런 역이 206개였다. 전에는 순번(5, 6 … 23)을 이름 자리에 찍어
      // 뜻 없는 숫자가 줄지어 보였다(11-05). 순번과 이유는 마우스를 올리면 보인다
      if (!terminal && s.name) {
        const label = document.createElement('span');
        label.textContent = s.name;
        el.appendChild(label);
      }
      // 이름이 없다 = 반경 안에 집계구가 없다 = 이용객을 **모른다**. 그때 「예상 0명/일」 이라고 적으면
      // 「인구 0」 과 「모름」 을 섞는다(CLAUDE.md) — 백엔드는 모를 때도 0 을 싣는다
      el.title = `${s.name || `정차역 ${s.sequence} — 이 근처는 인구 자료(집계구)가 없어 이름·이용객을 모릅니다`}${
        s.name && s.estimatedDailyUsers != null
          ? ` · 예상 ${s.estimatedDailyUsers.toLocaleString()}명/일`
          : ''
      }`;
      return new maplibregl.Marker({ element: el })
        .setLngLat([Number(s.longitude), Number(s.latitude)])
        .addTo(map);
    });
  }, [stations]);

  /**
   * 기존 노선(KTX) 구간 — 먼 거리를 그렸을 때만 나온다.
   *
   * **두 역을 이은 점선이지 실제 KTX 선형이 아니다.** 우리가 가진 것은 역 좌표뿐이고
   * 선형 자료는 없다 (`docs/engineering-notes.md` 의 KTX 절). 점선과 다른 색으로
   * 그려 그 차이를 드러낸다 — 빨간 실선은 "지을 노선", 청록 점선은 "이미 있는 것".
   */
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady) return;

    existingMarkersRef.current.forEach((m) => m.remove());
    existingMarkersRef.current = [];

    const o = existingOption;
    const ends = o && o.fromLat != null && o.toLat != null
      ? [[Number(o.fromLng), Number(o.fromLat)], [Number(o.toLng), Number(o.toLat)]]
      : [];

    map.getSource(EXISTING_SOURCE).setData({
      type: 'Feature',
      geometry: { type: 'LineString', coordinates: ends },
    });
    existingMarkersRef.current = ends.map((c) => {
      const el = document.createElement('div');
      el.className = 'existing-dot';
      el.title = `${o.label} — ${o.detail}`;
      return new maplibregl.Marker({ element: el }).setLngLat(c).addTo(map);
    });
  }, [existingOption, mapReady]);

  /**
   * 저장된 시나리오를 열면 **그 노선으로 지도를 옮긴다.** 전에는 시흥~안양을 열어도 지도가
   * 서울 도심에 그대로 있어 노선을 찾아 끌어와야 했다. 점을 찍을 때는 옮기지 않는다 —
   * 그리는 도중에 지도가 튀면 다음 점을 찍을 수 없다. 그래서 `points` 가 아니라 App 이
   * 열 때만 올리는 `focus` 에 반응하고, 점은 ref 로 읽는다
   */
  useEffect(() => {
    const map = mapRef.current;
    const pts = pointsRef.current;
    if (!map || !mapReady || !focus || pts.length < 2) return;
    const bounds = pts.reduce(
      (b, p) => b.extend(p), new maplibregl.LngLatBounds(pts[0], pts[0]));
    map.fitBounds(bounds, { padding: 80, maxZoom: 14, duration: 0 });
  }, [focus, mapReady]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady) return;
    map.getSource(WATER_SOURCE).setData({
      type: 'Feature',
      geometry: {
        type: 'MultiLineString',
        coordinates: (waterPieces || []).map((piece) => piece.map(([lat, lng]) => [lng, lat])),
      },
    });
  }, [waterPieces, mapReady]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady) return;
    map.getSource(MOUNTAIN_SOURCE).setData({
      type: 'FeatureCollection',
      features: (mountainPieces || []).map((piece) => ({
        type: 'Feature',
        geometry: piece.length > 1
          ? { type: 'LineString', coordinates: piece.map(([lat, lng]) => [lng, lat]) }
          : { type: 'Point', coordinates: [piece[0][1], piece[0][0]] },
      })),
    });
    map.getSource(MOUNTAIN_PREVIEW_SOURCE).setData({
      type: 'Feature',
      geometry: { type: 'LineString', coordinates: mountainPreview || [] },
    });
  }, [mountainPieces, mountainPreview, mapReady]);

  // 규제 구역 — 좌표가 근사치라 원으로만 표시하고 "확인 필요" 안내에 쓴다
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady || !zones?.length) return;
    if (map.getLayer('zone-circle')) return;

    map.addSource('zones', {
      type: 'geojson',
      data: {
        type: 'FeatureCollection',
        features: zones.map((z) => ({
          type: 'Feature',
          properties: { name: z.name, severity: z.severity, radiusKm: z.radiusKm },
          geometry: { type: 'Point', coordinates: [Number(z.centerLng), Number(z.centerLat)] },
        })),
      },
    });
    map.addLayer({
      id: 'zone-circle',
      type: 'circle',
      source: 'zones',
      paint: {
        'circle-radius': ['interpolate', ['linear'], ['zoom'], 8, 4, 14, 26],
        'circle-color': [
          'match', ['get', 'severity'],
          'BLOCKED', '#b3261e',
          '#c88a00',
        ],
        'circle-opacity': 0.25,
        'circle-stroke-width': 1,
        'circle-stroke-color': '#7a5c00',
      },
    });
    map.on('click', 'zone-circle', (e) => {
      const p = e.features[0].properties;
      new maplibregl.Popup()
        .setLngLat(e.lngLat)
        .setHTML(
          `<strong>${p.name}</strong><br/>${
            p.severity === 'BLOCKED' ? '사실상 불가' : '심의·협의 필요'
          } · 반경 약 ${p.radiusKm}km<br/><small>좌표는 근사치입니다</small>`
        )
        .addTo(map);
    });
  }, [zones, mapReady]);

  return <div className="map" ref={containerRef} />;
}
