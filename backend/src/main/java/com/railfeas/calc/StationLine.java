package com.railfeas.calc;

import com.railfeas.geo.Haversine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 지도가 그리는 철도 노선 — **그린 선을 따라가되 정차역에 들렀다 온다.** 화면의 `routeThroughStations`(`frontend/src/geo/routeLine.js`)와
 * 같은 규칙이다(테스트도 같은 경우를 본다 — `StationLineTest`).
 *
 * <p>왜 서버에도 있나(11-26 사용자 요청): 정차역은 큰길 위로 옮겨지고(11-12) 노선 길이(`detourKm`)와 지도 선은 그 역들을 지난다. 그런데
 * 터널·구역만 찍은 직선으로 재 지도의 갈색·파란 토막이 선 밖 허공에 떴고, 표의 터널비도 지도와 다른 선의 값이었다. 이 선으로 재면
 * 한 행의 길이·터널비·구역 경고·지도가 같은 선을 말한다.
 *
 * <ul>
 *   <li>양 끝은 <b>종점역</b>이다 — 찍은 점이 건물 위일 수 있어 종점역이 큰길로 옮겨진다</li>
 *   <li>가운데 꼭짓점(꺾임)은 역과 <b>선 위 거리 순</b>으로 엮는다 — 역만 이으면 역간격이 넓은 수단에서 꺾임이 사라진다</li>
 *   <li>겹치는 점은 지운다(길이 0 조각). 역이 둘 미만이면 그린 선 그대로</li>
 * </ul>
 * 점은 [위도, 경도]
 */
public final class StationLine {

    private static final double KM_PER_DEG_LAT = 110.574;
    private static final double KM_PER_DEG_LNG = 111.320;
    private static final double SAME_KM = 1e-6;

    private StationLine() {
    }

    private record Node(double at, double[] p) {
    }

    public static List<double[]> through(List<double[]> points, List<StationPlanner.Placed> stations) {
        if (points == null || points.size() < 2 || stations == null || stations.size() < 2) {
            return points;
        }
        double[] cum = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1), b = points.get(i);
            cum[i] = cum[i - 1] + Haversine.distanceKm(a[0], a[1], b[0], b[1]);
        }

        // 꼭짓점을 먼저 넣고 역을 넣은 뒤 안정 정렬한다 — 같은 거리면 꼭짓점이 앞(화면의 Array.sort 와 같다)
        List<Node> nodes = new ArrayList<>();
        for (int i = 1; i < points.size() - 1; i++) {
            nodes.add(new Node(cum[i], points.get(i)));
        }
        for (int i = 1; i < stations.size() - 1; i++) {
            double[] s = {stations.get(i).lat(), stations.get(i).lng()};
            nodes.add(new Node(alongKm(points, cum, s), s));
        }
        nodes.sort(Comparator.comparingDouble(Node::at));

        StationPlanner.Placed first = stations.get(0), last = stations.get(stations.size() - 1);
        List<double[]> ordered = new ArrayList<>();
        ordered.add(new double[]{first.lat(), first.lng()});
        nodes.forEach(n -> ordered.add(n.p()));
        ordered.add(new double[]{last.lat(), last.lng()});

        List<double[]> out = new ArrayList<>();
        for (double[] p : ordered) {
            double[] prev = out.isEmpty() ? null : out.get(out.size() - 1);
            if (prev == null || Haversine.distanceKm(prev[0], prev[1], p[0], p[1]) > SAME_KM) {
                out.add(p);
            }
        }
        return out;
    }

    /** 점이 그린 선 위 어디쯤인지(시작점에서 km) — 선분마다 수직으로 내려 가장 가까운 자리. 위도 보정한 km 평면에서 잰다(화면과 같다) */
    static double alongKm(List<double[]> points, double[] cum, double[] point) {
        double bestD = Double.POSITIVE_INFINITY, bestAt = 0;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1), b = points.get(i);
            double kx = KM_PER_DEG_LNG * Math.cos(Math.toRadians((a[0] + b[0]) / 2));
            double ax = a[1] * kx, ay = a[0] * KM_PER_DEG_LAT;
            double bx = b[1] * kx, by = b[0] * KM_PER_DEG_LAT;
            double px = point[1] * kx, py = point[0] * KM_PER_DEG_LAT;
            double vx = bx - ax, vy = by - ay;
            double len2 = vx * vx + vy * vy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (py - ay) * vy) / len2));
            double d = Math.hypot(ax + t * vx - px, ay + t * vy - py);
            if (d < bestD) {
                bestD = d;
                bestAt = cum[i - 1] + t * (cum[i] - cum[i - 1]);
            }
        }
        return bestAt;
    }
}
