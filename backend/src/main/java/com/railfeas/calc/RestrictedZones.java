package com.railfeas.calc;

import com.railfeas.reference.RestrictedZone;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 확인 필요 구역(`restricted_zone` — 궁궐·상수원·군사)을 노선이 지나는가, 피해 가는 선형은 무엇인가(11-12 사용자 요청).
 *
 * <p><b>찍은 점이 아니라 선분으로 본다.</b> 전에는 찍은 점이 원 안에 있는지만 봐서, 서대문구청 쪽에서 한성대입구 쪽으로 점 둘만 찍으면
 * 선이 경복궁 중심 0.4km 를 지나도 아무 말이 없었다(양 끝은 3km 밖). 계산 결과의 경고와 그리는 즉시 뜨는 안내가 이 판정 하나를 쓴다.
 *
 * <p><b>피해 가는 선형은 호수 우회({@link WaterDetour})를 그대로 쓴다</b> — 원을 {@value #SIDES}각형으로 바꿔 장애물로 넘긴다. 1.5배 상한도
 * 같다(가정값). <b>지하도 제안한다</b> — 호수와 달리 문화재·상수원 구역은 굴착이 문제다(3호선이 경복궁을 곡선으로 비껴갔다).
 * 좌표·반경은 눈대중 근사치라 경계를 단정하지 않는다 — 화면이 그렇게 적는다.
 */
public final class RestrictedZones {

    /** 원을 이 각형으로 — 48각형은 반지름의 0.2% 안쪽까지 원과 같다 */
    static final int SIDES = 48;
    private static final double KM_PER_LAT = 110.574;

    static final WaterDetour.Wording WORDING = new WaterDetour.Wording(
            "구역을 피해 갈 길을 찾지 못했습니다 — 끝점이 구역 안이거나 둘레가 막혔습니다",
            "피해 가면 %.1f배(%.1fkm 더)라 1.5배 안에 돌아갈 수 없습니다");

    /** severity — REVIEW(심의·협의) · BLOCKED(사실상 불가) */
    public record Zone(String name, String severity, String note, double lat, double lng, double radiusKm) {
        public static Zone of(RestrictedZone z) {
            return new Zone(z.getName(), z.getSeverity() == null ? null : z.getSeverity().name(), z.getNote(),
                    z.getCenterLat().doubleValue(), z.getCenterLng().doubleValue(), z.getRadiusKm().doubleValue());
        }
    }

    private RestrictedZones() {
    }

    public static List<Zone> of(List<RestrictedZone> zones) {
        return zones.stream().map(Zone::of).toList();
    }

    /** 노선 선분이 원과 만나는 구역들 — 노선을 따라 처음 닿는 순서로 */
    public static List<Zone> crossed(List<double[]> route, List<Zone> zones) {
        record Hit(Zone zone, double at) {
        }
        List<Hit> hits = new ArrayList<>();
        for (Zone z : zones) {
            double kmPerLng = 111.320 * Math.cos(Math.toRadians(z.lat()));
            double along = 0;
            for (int i = 1; i < route.size(); i++) {
                // 구역 중심을 원점으로 한 km 평면 — 반경 수 km 라 평면 근사로 충분하다
                double ay = (route.get(i - 1)[0] - z.lat()) * KM_PER_LAT, ax = (route.get(i - 1)[1] - z.lng()) * kmPerLng;
                double by = (route.get(i)[0] - z.lat()) * KM_PER_LAT, bx = (route.get(i)[1] - z.lng()) * kmPerLng;
                double dx = bx - ax, dy = by - ay;
                double len = Math.hypot(dx, dy);
                double t = len == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / (len * len)));
                if (Math.hypot(ax + t * dx, ay + t * dy) <= z.radiusKm()) {
                    hits.add(new Hit(z, along + t * len));
                    break;
                }
                along += len;
            }
        }
        return hits.stream().sorted(Comparator.comparingDouble(Hit::at)).map(Hit::zone).toList();
    }

    /** 구역을 장애물로 한 우회 탐색 */
    public static WaterDetour detour(List<Zone> zones) {
        WaterBodies obstacles = new WaterBodies("(확인 필요 구역)");
        for (Zone z : zones) {
            double kmPerLng = 111.320 * Math.cos(Math.toRadians(z.lat()));
            double[][] ring = new double[SIDES + 1][];
            for (int k = 0; k <= SIDES; k++) {
                double a = 2 * Math.PI * k / SIDES;
                ring[k] = new double[]{z.lat() + z.radiusKm() * Math.sin(a) / KM_PER_LAT,
                        z.lng() + z.radiusKm() * Math.cos(a) / kmPerLng};
            }
            obstacles.add(z.name(), WaterBodies.LAKE, List.<double[][]>of(ring));
        }
        return new WaterDetour(obstacles, WORDING);
    }
}
