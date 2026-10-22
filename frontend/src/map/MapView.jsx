import { useEffect, useRef, useState } from 'react';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { mapStyle, INITIAL_VIEW } from './mapStyle';
import { routeThroughStations } from '../geo/routeLine';
import usePopulation3D from './usePopulation3D';

const ROUTE_SOURCE = 'route';
const EXISTING_SOURCE = 'existing-route';

/**
 * 지도와 노선 표시. 좌표 상태는 App 이 들고 있고 여기서는 그리기만 한다.
 * onPick(lngLat) — 지도를 클릭하면 좌표를 올려보낸다.
 * stations — 자동 배치된 정차역. 수단마다 다르므로 비교표에서 고른 안의 것을 받는다.
 * show3D — 인구밀도 3D 레이어. 켜면 지도가 기울고 집계구 인구가 높이로 선다.
 * focus — 바뀔 때마다 지도를 노선에 맞춘다. 저장된 시나리오를 열 때만 App 이 올린다.
 */
export default function MapView({ points, zones, stations, existingOption, show3D, focus, onPick }) {
  const containerRef = useRef(null);
  const mapRef = useRef(null);
  const markersRef = useRef([]);
  const stationMarkersRef = useRef([]);
  const existingMarkersRef = useRef([]);
  // **지도가 준비됐는지를 상태로 둔다.** 'load' 이벤트에 직접 매달면
  // StrictMode 가 지도를 두 번 만드는 개발 환경에서 순서가 어긋나,
  // 지도가 뜨기 전에 시나리오를 연 경우 점이 영영 안 그려졌다.
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

    map.on('load', () => {
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

  // 좌표가 바뀌면 선과 마커를 다시 그린다
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    const draw = () => {
      const source = map.getSource(ROUTE_SOURCE);
      if (!source) return;
      // 그린 점만 잇지 않는다 — 역이 선 옆으로 비켜 서 있으면 선도 그리로 돈다.
      // 비용·편익은 이미 돌아간 길이로 계산하므로(CostCalculator 의 detourKm),
      // 지도만 직선이면 화면과 숫자가 서로 다른 노선을 말하게 된다
      source.setData({
        type: 'Feature',
        geometry: { type: 'LineString', coordinates: routeThroughStations(points, stations) },
      });
    };
    if (map.isStyleLoaded()) draw();
    else map.once('load', draw);

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
      if (!terminal) {
        const label = document.createElement('span');
        label.textContent = s.name || String(s.sequence);
        el.appendChild(label);
      }
      el.title = `${s.name || `정차역 ${s.sequence}`}${
        s.estimatedDailyUsers != null
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

  // 규제 구역 — 좌표가 근사치라 원으로만 표시하고 "확인 필요" 안내에 쓴다
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !zones?.length) return;

    const add = () => {
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
    };
    if (map.isStyleLoaded()) add();
    else map.once('load', add);
  }, [zones]);

  return <div className="map" ref={containerRef} />;
}
