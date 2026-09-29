import { useEffect, useRef } from 'react';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { mapStyle, INITIAL_VIEW } from './mapStyle';

const ROUTE_SOURCE = 'route';

/**
 * 지도와 노선 표시. 좌표 상태는 App 이 들고 있고 여기서는 그리기만 한다.
 * onPick(lngLat) — 지도를 클릭하면 좌표를 올려보낸다.
 */
export default function MapView({ points, zones, onPick }) {
  const containerRef = useRef(null);
  const mapRef = useRef(null);
  const markersRef = useRef([]);
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
    return () => map.remove();
  }, []);

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
