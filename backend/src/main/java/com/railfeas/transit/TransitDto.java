package com.railfeas.transit;

import com.railfeas.scenario.ScenarioDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 실제 대중교통 경로 — TMAP 응답에서 **화면이 쓰는 칸만** 옮긴다(DB 에 저장하지 않는다). */
public final class TransitDto {

    private TransitDto() {
    }

    /** DISABLED 키 없음 · LIMIT 하루 한도 · NO_ROUTE TMAP 이 경로 없음 · FAILED 못 불렀거나 모르는 응답 */
    public enum Status {
        OK, DISABLED, LIMIT, NO_ROUTE, FAILED
    }

    public record Request(@NotNull @Valid ScenarioDto.PointRequest from,
                          @NotNull @Valid ScenarioDto.PointRequest to) {
    }

    public record Availability(boolean enabled) {
    }

    /** @param searchedFor 운행 여부를 따진 시각(`2026-11-30T08:00`). 결과가 없으면 null */
    public record Response(Status status, String searchedFor, List<Itinerary> itineraries) {

        static Response empty(Status status) {
            return new Response(status, null, List.of());
        }
    }

    /** @param running 모든 구간이 기준 시각에 다니나(대체 노선 포함) */
    public record Itinerary(int totalSec, int walkSec, int transfers, Integer fare, boolean running, List<Leg> legs) {
    }

    /** @param mode TMAP 그대로 — WALK·BUS·SUBWAY·EXPRESSBUS·TRAIN … @param color `#` 없는 여섯 자리 또는 null */
    public record Leg(String mode, int sec, String route, String color, String from, String to, boolean running) {
    }
}
