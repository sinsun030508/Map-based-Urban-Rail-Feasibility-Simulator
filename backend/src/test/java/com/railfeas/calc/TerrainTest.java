package com.railfeas.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.OptionalDouble;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 지형 타일 읽기 — 저장소에 넣은 추풍령 부근 z11 타일 2장(1752/802·803, AWS Terrain Tiles)으로 본다.
 * CI 에는 받은 캐시가 없으므로 이 두 장만 쓴다.
 */
class TerrainTest {

    private static final Path DEM = Path.of("src/test/resources/dem");

    @Test
    void 산의_높이를_읽는다() {
        Terrain t = new Terrain(DEM);
        assertThat(t.available()).isTrue();
        // 추풍령역 해발 약 220m — 산지 한가운데라 100m 는 넘어야 한다
        OptionalDouble h = t.at(36.2189, 127.9950);
        assertThat(h).isPresent();
        assertThat(h.getAsDouble()).isBetween(100.0, 600.0);
    }

    @Test
    void 두_타일의_경계를_넘어도_읽는다() {
        Terrain t = new Terrain(DEM);
        // 802 와 803 사이 경계(위도 약 36.17°) 바로 위아래 — 양쪽 모두 값이 있다
        assertThat(t.at(36.1720, 127.99)).isPresent();
        assertThat(t.at(36.1700, 127.99)).isPresent();
    }

    @Test
    void 타일이_없는_곳은_모른다() {
        // 서울은 테스트 타일 밖 — 0m(평지)가 아니라 「모름」 이어야 한다
        assertThat(new Terrain(DEM).at(37.5665, 126.9780)).isEmpty();
    }

    @Test
    void 폴더가_없으면_기능이_꺼진다() {
        Terrain t = new Terrain(Path.of("src/test/resources/no-such-dem"));
        assertThat(t.available()).isFalse();
        assertThat(t.at(36.2189, 127.9950)).isEmpty();
    }

    @Test
    @DisplayName("켠 뒤에 받은 타일도 읽는다 — 없던 칸을 「없음」 으로 기억하면 재시작 전까지 모른다")
    void 나중에_받은_타일(@org.junit.jupiter.api.io.TempDir Path dir) throws java.io.IOException {
        Path col = java.nio.file.Files.createDirectories(dir.resolve("11/1752"));
        Terrain t = new Terrain(dir);
        assertThat(t.at(36.2189, 127.9950)).isEmpty();

        java.nio.file.Files.copy(DEM.resolve("11/1752/802.png"), col.resolve("802.png"));

        assertThat(t.at(36.2189, 127.9950)).isPresent();
    }
}
