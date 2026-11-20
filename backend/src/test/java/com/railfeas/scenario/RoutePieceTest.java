package com.railfeas.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 노선 조각이 응답으로 나갈 때 — 새 도로는 왜 새 도로인지도 함께 나간다(11-19). 화면이 패널에 그대로 적는다 */
class RoutePieceTest {

    @Test
    @DisplayName("새 도로 조각은 이유를 싣고 기존 도로 조각은 이유가 없다")
    void 새_도로만_이유가_있다() {
        String stored = "R 37.50000,126.90000 37.51000,126.90000|N 37.51000,126.90000 37.52000,126.90000\t기존 도로가 1.7배(7.6km)라 육지는 도로를 따르고 물 위만 새 다리";

        List<ScenarioDto.RoutePiece> pieces = ScenarioDto.RoutePiece.of(stored);

        assertThat(pieces).extracting(ScenarioDto.RoutePiece::road).containsExactly(true, false);
        assertThat(pieces.get(0).reason()).isNull();
        assertThat(pieces.get(1).reason()).isEqualTo("기존 도로가 1.7배(7.6km)라 육지는 도로를 따르고 물 위만 새 다리");
        assertThat(pieces.get(1).points()).hasSize(2);
    }

    @Test
    @DisplayName("이유 없이 저장된 옛 결과도 읽힌다 — 이유가 null 이다")
    void 옛_저장() {
        List<ScenarioDto.RoutePiece> pieces = ScenarioDto.RoutePiece.of("R 37.50000,126.90000 37.51000,126.90000|N 37.51000,126.90000 37.52000,126.90000");

        assertThat(pieces).extracting(ScenarioDto.RoutePiece::reason).containsOnlyNulls();
    }

    @Test
    @DisplayName("저장된 노선이 없으면(철도 행) 조각이 없다")
    void 노선_없음() {
        assertThat(ScenarioDto.RoutePiece.of(null)).isEmpty();
    }
}
