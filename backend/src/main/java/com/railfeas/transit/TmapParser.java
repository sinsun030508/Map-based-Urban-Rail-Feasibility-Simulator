package com.railfeas.transit;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * TMAP 대중교통 응답 → 화면용 경로 최대 {@value #MAX}개.
 *
 * <p><b>`service` 0 을 그대로 믿지 않는다</b> — 대표 노선이 0 인데 대체 노선(`Lane`, 대문자 L)의 급행은 1 인 구간이 있었다
 * (11-28 실제 호출). 다니는 대체 노선이 있으면 그 노선을 보인다. 대안까지 0 인 구간이 든 경로는 뒤로 보낸다 —
 * TMAP 은 새벽에도 같은 경로 목록을 주고 운행 여부만 바꾼다.
 */
final class TmapParser {

    static final int MAX = 3;

    private TmapParser() {
    }

    static List<TransitDto.Itinerary> parse(JsonNode root) {
        List<TransitDto.Itinerary> list = new ArrayList<>();
        for (JsonNode it : root.path("metaData").path("plan").path("itineraries")) {
            List<TransitDto.Leg> legs = new ArrayList<>();
            for (JsonNode leg : it.path("legs")) {
                legs.add(leg(leg));
            }
            if (legs.isEmpty() || !it.hasNonNull("totalTime")) {
                continue;
            }
            JsonNode fare = it.path("fare").path("regular").path("totalFare");
            list.add(new TransitDto.Itinerary(it.path("totalTime").asInt(), it.path("totalWalkTime").asInt(),
                    it.path("transferCount").asInt(), fare.isNumber() ? fare.asInt() : null,
                    legs.stream().allMatch(TransitDto.Leg::running), legs));
        }
        // 정렬은 안정적이다 — 다니는 경로끼리, 안 다니는 경로끼리는 TMAP 순서 그대로
        list.sort(Comparator.comparing(TransitDto.Itinerary::running).reversed());
        return List.copyOf(list.subList(0, Math.min(MAX, list.size())));
    }

    private static TransitDto.Leg leg(JsonNode leg) {
        String route = text(leg.path("route"));
        String color = text(leg.path("routeColor"));
        boolean running = !(leg.hasNonNull("service") && leg.path("service").asInt() == 0);
        if (!running) {
            for (JsonNode lane : leg.path("Lane")) {
                if (lane.path("service").asInt() == 1) {
                    route = text(lane.path("route"));
                    color = text(lane.path("routeColor"));
                    running = true;
                    break;
                }
            }
        }
        return new TransitDto.Leg(leg.path("mode").asText(), leg.path("sectionTime").asInt(), route, color,
                text(leg.path("start").path("name")), text(leg.path("end").path("name")), running, line(leg));
    }

    /** 탑승 구간은 `passShape.linestring`, 도보는 `steps[].linestring` 을 차례로 잇는다 */
    private static List<List<Double>> line(JsonNode leg) {
        List<List<Double>> points = new ArrayList<>();
        append(points, leg.path("passShape").path("linestring").asText(""));
        if (points.isEmpty()) {
            for (JsonNode step : leg.path("steps")) {
                append(points, step.path("linestring").asText(""));
            }
        }
        return List.copyOf(points);
    }

    /** "경도,위도 경도,위도 …" — 못 읽는 토막은 버리고, 앞 토막의 끝과 같은 점은 한 번만 담는다 */
    private static void append(List<List<Double>> points, String linestring) {
        for (String token : linestring.trim().split("\\s+")) {
            int comma = token.indexOf(',');
            if (comma < 0) {
                continue;
            }
            try {
                List<Double> point = List.of(Double.parseDouble(token.substring(0, comma)),
                        Double.parseDouble(token.substring(comma + 1)));
                if (points.isEmpty() || !points.get(points.size() - 1).equals(point)) {
                    points.add(point);
                }
            } catch (NumberFormatException e) {
                // 깨진 토막 하나 때문에 경로를 버리지 않는다
            }
        }
    }

    private static String text(JsonNode node) {
        String value = node.asText("");
        return value.isBlank() ? null : value;
    }
}
