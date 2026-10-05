import { useEffect, useRef } from 'react';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { mapStyle, INITIAL_VIEW } from './mapStyle';
import usePopulation3D from './usePopulation3D';

const ROUTE_SOURCE = 'route';

/**
 * 지도와 노선 표시. 좌표 상태는 App 이 들고 있고 여기서는 그리기만 한다.
 * onPick(lngLat) — 지도를 클릭하면 좌표를 올려보낸다.
 * stations — 자동 배치된 정차역. 수단마다 다르므로 비교표에서 고른 안의 것을 받는다.
 * show3D — 인구밀도 3D 레이어. 켜면 지도가 기울고 집계구 인구가 높이로 선다.
 */
export default function MapView({ points, zones, stations, show3D, onPick }) {
  const containerRef = useRef(null);
  const mapRef = useRef(null);
  const markersRef = useRef([]);
  const stationMarkersRef = useRef([]);
  const onPickRef = useRef(onPick);
  onPickRef.current = onPick;

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
      source.setData({
        type: 'Feature',
        geometry: { type: 'LineString', coordinates: points },
      });
    };
    if (map.isStyleLoaded()) draw();
    else map.once('load', draw);

    markersRef.current.forEach((m) => m.remove());
    markersRef.current = points.map((p, i) => {
      const el = document.createElement('div');
      el.className = 'route-marker';
      el.textContent =
        i === 0 ? '출발' : i === points.length - 1 ? '도착' : String(i);
      return new maplibregl.Marker({ element: el }).setLngLat(p).addTo(map);
    });
  }, [points]);

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
