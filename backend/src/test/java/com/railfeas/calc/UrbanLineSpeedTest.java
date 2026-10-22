package com.railfeas.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 기존 도시철도의 공표 표정속도. 역 좌표 파일과 속도 파일이 **노선 이름을 다르게 적는다**
 * (`2호선` ↔ `서울 2호선`, `부산 도시철도 1호선` ↔ `부산 1호선`). 이름이 조용히 안
 * 맞으면 편익 기준이 시내버스로 돌아가 아무도 모른다 — 실제 파일의 표기로 고정한다.
 */
class UrbanLineSpeedTest {

    private static UrbanLineSpeed from(Path dir, String rows) throws IOException {
        Path file = dir.resolve("rail_speed.csv");
        Files.writeString(file,
                "mode,line,section,length_km,station_count,travel_min,speed_kmh,basis,use_for_median,source\n"
                        + rows, StandardCharsets.UTF_8);
        UrbanLineSpeed speeds = new UrbanLineSpeed(file.toString());
        speeds.load();
        return speeds;
    }

    @Test
    @DisplayName("역 좌표 파일의 노선 이름으로 속도 파일의 행을 찾는다")
    void 이름_표기가_달라도_찾는다(@TempDir Path dir) throws IOException {
        UrbanLineSpeed speeds = from(dir, String.join("\n",
                "HEAVY_METRO,서울 2호선,성수~성수,60.2,,,32.5,실측,1,서울교통공사",
                "HEAVY_METRO,서울 7호선,장암~온수,,,,32.3,실측,1,서울교통공사",
                "HEAVY_METRO,인천 1호선,계양~송도,,,,34.2,실측,1,인천교통공사",
                "HEAVY_METRO,부산 1호선,다대포~노포,,,,30.69,실측,1,부산교통공사",
                "LIGHT_RAIL,김포 골드라인,양촌~김포공항,,,,45.08,실측,1,김포시",
                "DOUBLE_ELEC,신분당선,신사~광교,,,,47.60,실측,1,신분당선(주)", ""));

        // 왼쪽이 station_master.csv · regional_station.csv 의 실제 표기다
        assertThat(speeds.of("2호선")).isEqualTo(32.5);
        assertThat(speeds.of("7호선(인천)")).isEqualTo(32.3);        // 같은 노선의 연장 구간
        assertThat(speeds.of("인천1호선")).isEqualTo(34.2);
        assertThat(speeds.of("부산 도시철도 1호선")).isEqualTo(30.69);
        assertThat(speeds.of("김포골드라인")).isEqualTo(45.08);
        assertThat(speeds.of("신분당선(연장)")).isEqualTo(47.60);
    }

    @Test
    @DisplayName("지선 행은 본선과 섞이지 않는다")
    void 지선은_따로다(@TempDir Path dir) throws IOException {
        UrbanLineSpeed speeds = from(dir, String.join("\n",
                "HEAVY_METRO,서울 2호선,성수~성수,60.2,,,32.5,실측,1,서울교통공사",
                "HEAVY_METRO,서울 2호선 성수지선,성수~신설동,,,,36.0,실측,0,본선과 중복", ""));

        assertThat(speeds.of("2호선")).isEqualTo(32.5);
    }

    @Test
    @DisplayName("실측이 아닌 값(설계·시운전)은 쓰지 않는다")
    void 실측만_쓴다(@TempDir Path dir) throws IOException {
        // 대전 2호선은 미개통이라 설계값이다. 기존 수단의 소요시간으로 쓰면 없는 노선을 탄다
        UrbanLineSpeed speeds = from(dir, String.join("\n",
                "TRAM,대전 2호선,서대전~정부청사,,,,22.06,설계,1,대전시",
                "EXPRESS_RAIL,GTX-A,운정중앙~서울역,,,,90.14,시운전,1,국토교통부", ""));

        assertThat(speeds.of("대전 2호선")).isNull();
        assertThat(speeds.of("GTX-A")).isNull();
    }

    @Test
    @DisplayName("한 노선에 실측 속도가 둘이면 고르지 않는다")
    void 둘이면_고르지_않는다(@TempDir Path dir) throws IOException {
        // GTX-A 는 운정중앙~서울역과 수서~동탄이 따로 적혀 있다. 어느 구간을 그렸는지 모르고
        // 하나를 고르면 그 구간의 값이 노선 전체의 값이 된다
        UrbanLineSpeed speeds = from(dir, String.join("\n",
                "EXPRESS_RAIL,GTX-A,운정중앙~서울역,,,,90.14,실측,1,가",
                "EXPRESS_RAIL,GTX-A,수서~동탄,,,,93.43,실측,1,나", ""));

        assertThat(speeds.of("GTX-A")).isNull();
    }

    @Test
    @DisplayName("모르는 노선과 파일이 없을 때는 null 이다 — 0 이 아니다")
    void 모르면_null(@TempDir Path dir) throws IOException {
        assertThat(from(dir, "").of("9호선")).isNull();

        UrbanLineSpeed missing = new UrbanLineSpeed(dir.resolve("없는파일.csv").toString());
        missing.load();
        assertThat(missing.of("2호선")).isNull();
    }
}
