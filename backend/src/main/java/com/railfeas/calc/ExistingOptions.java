package com.railfeas.calc;

import com.railfeas.common.ModeType;
import com.railfeas.reference.ModeCapacity;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 이미 있는 교통수단을 **비교표의 한 줄로** 내놓는다.
 *
 * 찾는 코드는 전부터 둘 있었다 — 도시 규모는 {@link ExistingLineFinder}, 도시 간 거리는
 * {@link IntercityRailFinder}. 그런데 둘의 결과가 **경고 문구 한 줄**로만 쓰여 비교표
 * 위에 떠 있었다. 서울~부산(직선 324km)을 그리면 324km 트램까지 후보로 계산되는데,
 * 정작 가장 중요한 비교 대상 — **이미 KTX 가 있고 추가 건설비가 0 이다** — 은 표 밖에
 * 문장으로만 있었다. 비교는 표에서 일어나므로 표 안에 있어야 한다.
 *
 * 둘 중 어느 쪽을 쓸지 고르는 자리를 **여기 하나로 둔다.** 두 곳에 생기면 화면이 보여
 * 주는 안과 `scenario.existing_line` 에 저장된 문구가 서로 다른 말을 할 수 있다 —
 * 같은 값이 두 곳에 있고 한 곳만 바뀌는 것이 이 프로젝트가 되풀이해 겪은 사고다.
 *
 * **도시철도가 우선이다.** 둘 다 걸리면 역간격 1km 짜리 판정(출발·도착이 역 1km 안이고
 * 노선의 80% 가 역세권)이 KTX 판정(양 끝에서 20km 안)보다 훨씬 촘촘해 더 강한 근거다.
 *
 * **소요시간은 미리 받아 둔 파일에서 읽는다.** TAGO 「열차정보」로 받아
 * `data/build/ktx_travel_time.csv` 에 적어 두고({@code etl/korail_travel_time.py}),
 * {@link KtxTravelTime} 이 기동할 때 메모리에 올린다. **요청할 때 외부 API 를 부르지
 * 않는다** — API 가 느리거나 죽은 날 비교 화면 전체가 같이 멈출 이유가 없다.
 *
 * **값 하나가 아니라 계통을 가른다.** 같은 구간에 경로가 둘인 일이 흔하다 — 서울~부산은
 * 고속선 계통과 더 싸고 느린 우회 계통으로 갈린다. 한 열차의 시각을 "KTX 소요시간" 으로
 * 적으면 출처가 공표값이 되어 다음 사람이 의심하지 않는다(세종 S-BRT 의 35km/h 를 쓰지
 * 않은 것과 같은 이유다). 그래서 좁은 표 칸에는 **빠른 계통의 중앙값** 하나만 넣고,
 * 계통별 편수와 범위는 {@link #spread} 가 표 아래 설명 줄로 돌린다.
 *
 * **모르는 구간은 비운다.** 직통 KTX 가 없거나 역 이름을 못 맞춘 구간은 {@code null} 이
 * 오고 화면이 빈 칸과 그 이유를 적는다 — 0 을 넣으면 "0분" 으로 읽힌다.
 * 자세한 것은 `docs/research-log.md`.
 */
@Component
public class ExistingOptions {

    /** 도시철도 판정 */
    public static final String URBAN_RAIL = "URBAN_RAIL";
    /** 도시 간 KTX 판정 */
    public static final String INTERCITY_RAIL = "INTERCITY_RAIL";

    private final ExistingLineFinder urban;
    private final IntercityRailFinder intercity;
    private final KtxTravelTime times;

    public ExistingOptions(ExistingLineFinder urban, IntercityRailFinder intercity,
                           KtxTravelTime times) {
        this.urban = urban;
        this.intercity = intercity;
        this.times = times;
    }

    /**
     * 비교표에 붙일 기존 수단 한 줄.
     *
     * @param kind    {@link #URBAN_RAIL} 또는 {@link #INTERCITY_RAIL}
     * @param label   표의 수단 칸에 들어갈 **짧은** 이름. 비교표 수단 칸은 가장 긴 이름에
     *                맞춰 폭이 잡히므로(`table-layout: auto`) 노선 목록을 여기 넣지 않는다
     * @param detail  표 아래 한 줄 설명. 접근 거리·겹침 비율이 여기 들어간다
     * @param message `scenario.existing_line`(VARCHAR 150)에 저장하는 기존 문구.
     *                목록 API 가 이 문자열만 싣는다 — 바꾸면 요약 화면이 함께 바뀐다
     * @param accessKm 그린 양 끝에서 두 역까지 직선 거리의 합. 도시철도는 null
     */
    public record Option(String kind, String label, String detail, String message,
                         Double fromLat, Double fromLng, Double toLat, Double toLng,
                         Integer travelMin, String travelDetail, Double accessKm) {

        /** 도시철도 판정은 역을 특정하지 않는다 — 겹침 비율만 본다 */
        static Option urban(String label, String detail, String message) {
            return new Option(URBAN_RAIL, label, detail, message,
                    null, null, null, null, null, null, null);
        }

        /**
         * 그린 출발점에서 그린 도착점까지 이 수단으로 걸리는 시간(시간 단위). 편익의
         * <b>전환 전 시간</b>으로 쓴다. 모르면 null — 그때 편익은 시내버스만 기준으로 본다.
         *
         * 표의 {@link #travelMin} 은 역에서 역까지라 그대로 쓰면 KTX 가 유리해진다.
         * 신설안은 그린 점에서 그린 점까지를 재기 때문이다. 그래서 역까지의 직선 거리를
         * 전환 전 속도({@code accessSpeedKmh}, 시내버스)로 더한다. 실제 도로는 직선보다
         * 길어 이 값은 짧은 쪽으로 치우친다 — 신설안의 편익을 덜 세는 방향이다.
         *
         * 도시철도는 겹침 비율만 보고 소요시간을 재지 않아 늘 null 이다.
         */
        public Double doorToDoorHours(double accessSpeedKmh) {
            if (travelMin == null || accessKm == null || accessSpeedKmh <= 0) {
                return null;
            }
            return accessKm / accessSpeedKmh + travelMin / 60.0;
        }
    }

    /**
     * 계통이 갈리는 폭을 한 줄로. 좁은 칸에는 빠른 계통의 중앙값만 들어가므로
     * <b>"그 한 값이 전부가 아니다" 를 여기서 말한다.</b> 모르면 null.
     *
     * <b>단위를 표와 맞춘다 — 분이다.</b> 전에는 칸이 {@code 164분} 인데 이 줄이
     * {@code 2시간 23분~2시간 56분} 이어서 읽는 사람이 둘을 잇지 못했다. 표의 신설안
     * 행도 모두 분이라, 비교하려면 같은 단위여야 한다.
     *
     * <b>계통마다 중앙값도 적는다.</b> 칸의 한 숫자가 어느 계통의 것인지 보이게 한다.
     */
    static String spread(List<KtxTravelTime.Leg> legs) {
        if (legs.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < legs.size(); i++) {
            KtxTravelTime.Leg leg = legs.get(i);
            if (i > 0) {
                sb.append(" · 경로가 다른 ");
            }
            sb.append(leg.trains()).append("편 ")
                    .append(leg.minMin()).append('~').append(leg.maxMin())
                    .append("분(중앙 ").append(leg.medianMin()).append("분)");
        }
        return sb.toString();
    }

    /**
     * 도시철도 수단 권장 연장의 최댓값(지금 지하철 35km). KTX 판정의 기준 길이다 —
     * 새 상수를 두지 않고 **관리자가 고치는 값**을 따른다.
     *
     * 계산 경로({@link CostCalculator})와 조회 경로(`ScenarioService`)가 모두 이 값을
     * 쓰므로 집을 여기 하나만 둔다. 두 곳에서 따로 구하면 저장할 때와 다시 볼 때
     * 판정이 달라질 수 있다.
     */
    public static double urbanMaxKm(Map<ModeType, ModeCapacity> capacities) {
        return capacities.values().stream()
                .filter(c -> c.getMaxLengthKm() != null)
                .mapToDouble(c -> c.getMaxLengthKm().doubleValue())
                .max().orElse(Double.MAX_VALUE);
    }

    /**
     * @param route      그린 노선의 꼭짓점
     * @param lengthKm   그린 길이
     * @param urbanMaxKm 도시철도 수단 권장 연장의 최댓값. KTX 판정은 이보다 길 때만 한다
     */
    public Option find(List<double[]> route, double lengthKm, double urbanMaxKm) {
        ExistingLineFinder.Match line = urban.find(route);
        if (line != null) {
            return Option.urban("기존 " + line.lineName(),
                    String.format("노선의 %.0f%% 가 기존 역 1km 안입니다", line.coverage() * 100),
                    line.message());
        }
        IntercityRailFinder.Match ktx = intercity.find(route, lengthKm, urbanMaxKm);
        if (ktx != null) {
            List<KtxTravelTime.Leg> legs = times.legs(ktx.fromStation(), ktx.toStation());
            return new Option(INTERCITY_RAIL, "기존 KTX",
                    String.format("%s · %s역(출발에서 %.0fkm) ~ %s역(도착에서 %.0fkm) — 역까지는 따로 이동",
                            String.join("·", ktx.lines()),
                            ktx.fromStation(), ktx.fromKm(), ktx.toStation(), ktx.toKm()),
                    ktx.message(),
                    ktx.fromLat(), ktx.fromLng(), ktx.toLat(), ktx.toLng(),
                    times.fastestMedian(ktx.fromStation(), ktx.toStation()), spread(legs),
                    ktx.fromKm() + ktx.toKm());
        }
        return null;
    }
}
