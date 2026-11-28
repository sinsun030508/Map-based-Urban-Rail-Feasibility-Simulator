package com.railfeas.transit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** TMAP 응답 모양의 **손으로 만든** 자료로 본다 — 실제 응답을 저장소에 넣으면 24시간 저장 제한에 걸린다. */
class TmapParserTest {

    private static List<TransitDto.Itinerary> parse(String json) throws Exception {
        return TmapParser.parse(new ObjectMapper().readTree(json));
    }

    private static String plan(String... itineraries) {
        return "{\"metaData\":{\"plan\":{\"itineraries\":[" + String.join(",", itineraries) + "]}}}";
    }

    private static String itinerary(int total, String... legs) {
        return "{\"totalTime\":" + total + ",\"totalWalkTime\":300,\"transferCount\":1,"
                + "\"fare\":{\"regular\":{\"totalFare\":1650}},\"legs\":[" + String.join(",", legs) + "]}";
    }

    private static final String WALK = "{\"mode\":\"WALK\",\"sectionTime\":300,"
            + "\"start\":{\"name\":\"출발지\"},\"end\":{\"name\":\"가역\"}}";

    private static String ride(String route, int service, String lane) {
        return "{\"mode\":\"SUBWAY\",\"sectionTime\":600,\"route\":\"" + route + "\",\"routeColor\":\"009D3E\","
                + "\"service\":" + service + ",\"start\":{\"name\":\"가역\"},\"end\":{\"name\":\"나역\"}"
                + (lane == null ? "" : ",\"Lane\":[" + lane + "]") + "}";
    }

    @Test
    void 도보와_탑승_구간의_칸을_옮긴다() throws Exception {
        TransitDto.Itinerary it = parse(plan(itinerary(900, WALK, ride("가선", 1, null)))).get(0);

        assertThat(it.totalSec()).isEqualTo(900);
        assertThat(it.walkSec()).isEqualTo(300);
        assertThat(it.transfers()).isEqualTo(1);
        assertThat(it.fare()).isEqualTo(1650);
        assertThat(it.running()).isTrue();
        assertThat(it.legs()).containsExactly(
                new TransitDto.Leg("WALK", 300, null, null, "출발지", "가역", true),
                new TransitDto.Leg("SUBWAY", 600, "가선", "009D3E", "가역", "나역", true));
    }

    @Test
    void 대표_노선이_안_다녀도_대체_노선이_다니면_그_노선을_보인다() throws Exception {
        String lane = "{\"route\":\"가선(급행)\",\"routeColor\":\"F5A200\",\"service\":1}";

        TransitDto.Itinerary it = parse(plan(itinerary(900, ride("가선", 0, lane)))).get(0);

        assertThat(it.running()).isTrue();
        assertThat(it.legs().get(0).route()).isEqualTo("가선(급행)");
        assertThat(it.legs().get(0).color()).isEqualTo("F5A200");
        assertThat(it.legs().get(0).running()).isTrue();
    }

    @Test
    void 대안까지_안_다니는_경로는_표시하고_뒤로_보낸다() throws Exception {
        String lane = "{\"route\":\"가선(급행)\",\"service\":0}";

        List<TransitDto.Itinerary> list = parse(plan(
                itinerary(100, ride("심야", 0, lane)),
                itinerary(200, ride("가선", 1, null)),
                itinerary(300, ride("나선", 1, null))));

        assertThat(list).extracting(TransitDto.Itinerary::totalSec).containsExactly(200, 300, 100);
        assertThat(list.get(2).running()).isFalse();
        assertThat(list.get(2).legs().get(0).running()).isFalse();
        assertThat(list.get(2).legs().get(0).route()).isEqualTo("심야");
    }

    @Test
    void 경로는_셋까지만_담는다() throws Exception {
        assertThat(parse(plan(itinerary(1, WALK), itinerary(2, WALK), itinerary(3, WALK), itinerary(4, WALK))))
                .extracting(TransitDto.Itinerary::totalSec).containsExactly(1, 2, 3);
    }

    @Test
    void 요금_노선_운행여부가_없어도_터지지_않는다() throws Exception {
        TransitDto.Itinerary it = parse(plan("{\"totalTime\":60,\"legs\":[{\"mode\":\"BUS\",\"sectionTime\":60}]}"))
                .get(0);

        assertThat(it.fare()).isNull();
        assertThat(it.walkSec()).isZero();
        assertThat(it.legs().get(0)).isEqualTo(new TransitDto.Leg("BUS", 60, null, null, null, null, true));
    }

    @Test
    void 경로가_없는_응답은_빈_목록이다() throws Exception {
        assertThat(parse("{\"result\":{\"status\":11}}")).isEmpty();
    }
}
