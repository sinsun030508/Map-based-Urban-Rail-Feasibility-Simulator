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
 * **소요시간은 넣지 않는다.** 이 줄에 가장 넣고 싶은 값이지만 받을 데가 없다 —
 * 공공데이터포털의 한국철도공사 파일 데이터에는 시간표가 없고(「열차정보」는 차량 연결
 * 순서, 「KTX 운임표」는 운임), 시간표 API 는 인증키가 필요하다. 받아도 **단일 값이
 * 아니다**: 서울~부산이 열차마다 2시간 18분~2시간 40분으로 갈리고 무정차는 2시간 10분대다.
 * 한 열차의 시각을 "KTX 소요시간" 으로 적으면 출처가 공표값이 되어 다음 사람이 의심하지
 * 않는다 — 세종 S-BRT 의 35km/h 를 쓰지 않은 것과 같은 이유다.
 * 다음에 볼 곳은 `docs/research-log.md` 에 적어 뒀다.
 */
@Component
public class ExistingOptions {

    /** 도시철도 판정 */
    public static final String URBAN_RAIL = "URBAN_RAIL";
    /** 도시 간 KTX 판정 */
    public static final String INTERCITY_RAIL = "INTERCITY_RAIL";

    private final ExistingLineFinder urban;
    private final IntercityRailFinder intercity;

    public ExistingOptions(ExistingLineFinder urban, IntercityRailFinder intercity) {
        this.urban = urban;
        this.intercity = intercity;
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
     */
    public record Option(String kind, String label, String detail, String message,
                         Double fromLat, Double fromLng, Double toLat, Double toLng) {

        /** 도시철도 판정은 역을 특정하지 않는다 — 겹침 비율만 본다 */
        static Option urban(String label, String detail, String message) {
            return new Option(URBAN_RAIL, label, detail, message, null, null, null, null);
        }
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
            return new Option(INTERCITY_RAIL, "기존 KTX",
                    String.format("%s · %s역(출발에서 %.0fkm) ~ %s역(도착에서 %.0fkm) — 역까지는 따로 이동",
                            String.join("·", ktx.lines()),
                            ktx.fromStation(), ktx.fromKm(), ktx.toStation(), ktx.toKm()),
                    ktx.message(),
                    ktx.fromLat(), ktx.fromLng(), ktx.toLat(), ktx.toLng());
        }
        return null;
    }
}
