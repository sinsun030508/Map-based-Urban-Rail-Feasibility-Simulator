package com.railfeas.calc;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 땅 높이 — {@code etl/dem_tiles.py} 가 받은 AWS Terrain Tiles(Terrarium PNG, z11 ≈ 62m/픽셀)를 읽는다.
 *
 * <p>높이 = R·256 + G + B/256 − 32768 (m). <b>바다는 수심이라 음수</b>인데 0 으로 자른다 — 선로가 바다 밑으로
 * 꺼지면 해안 절벽이 터널로 잡힌다. <b>타일이 없는 칸은 0 이 아니라 「모름」</b>({@code empty})이다 — 0 으로 읽으면
 * 「평지」 로 틀린다. 폴더가 없으면 {@link #available()} 이 false 이고 산 판정이 꺼진다(소셜 로그인 키와 같다).
 */
@Component
public class Terrain implements Elevation {

    public static final int ZOOM = 11;
    private static final int SIZE = 256;
    private static final int CACHE_TILES = 256;
    /** 받지 못한 타일 — 다시 디스크를 찾지 않도록 캐시에 둔다 */
    private static final float[] MISSING = new float[0];

    private final Path dir;
    private final Map<Long, float[]> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, float[]> eldest) {
            return size() > CACHE_TILES;
        }
    };

    @Autowired
    public Terrain(@Value("${app.dem-dir}") String dir) {
        this(Path.of(dir));
    }

    public Terrain(Path dir) {
        this.dir = dir;
    }

    /** 받은 타일이 하나라도 있나 */
    public boolean available() {
        Path z = dir.resolve(String.valueOf(ZOOM));
        if (!Files.isDirectory(z)) {
            return false;
        }
        try (Stream<Path> cols = Files.list(z)) {
            return cols.anyMatch(Files::isDirectory);
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public OptionalDouble at(double lat, double lng) {
        double n = (1L << ZOOM) * SIZE;
        // 픽셀 중심이 정수 + 0.5 — 쌍선형 보간의 네 이웃을 고른다
        double px = (lng + 180) / 360 * n - 0.5;
        double py = (1 - Math.log(Math.tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2 * n - 0.5;
        long x0 = (long) Math.floor(px), y0 = (long) Math.floor(py);
        double fx = px - x0, fy = py - y0;
        double h00 = pixel(x0, y0), h10 = pixel(x0 + 1, y0), h01 = pixel(x0, y0 + 1), h11 = pixel(x0 + 1, y0 + 1);
        if (Double.isNaN(h00) || Double.isNaN(h10) || Double.isNaN(h01) || Double.isNaN(h11)) {
            return OptionalDouble.empty();
        }
        double h = (h00 * (1 - fx) + h10 * fx) * (1 - fy) + (h01 * (1 - fx) + h11 * fx) * fy;
        return OptionalDouble.of(Math.max(0, h));
    }

    /** 전역 픽셀 하나의 높이. 타일이 없으면 NaN */
    private double pixel(long gx, long gy) {
        long tx = Math.floorDiv(gx, SIZE), ty = Math.floorDiv(gy, SIZE);
        float[] tile = tile(tx, ty);
        if (tile == MISSING) {
            return Double.NaN;
        }
        return tile[(int) Math.floorMod(gy, SIZE) * SIZE + (int) Math.floorMod(gx, SIZE)];
    }

    private synchronized float[] tile(long tx, long ty) {
        long key = (tx << 32) | (ty & 0xffffffffL);
        return cache.computeIfAbsent(key, k -> read(tx, ty));
    }

    private float[] read(long tx, long ty) {
        Path file = dir.resolve(String.valueOf(ZOOM)).resolve(String.valueOf(tx)).resolve(ty + ".png");
        if (!Files.isRegularFile(file)) {
            return MISSING;
        }
        try {
            BufferedImage img = ImageIO.read(file.toFile());
            if (img == null || img.getWidth() != SIZE || img.getHeight() != SIZE) {
                return MISSING;
            }
            float[] h = new float[SIZE * SIZE];
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    int rgb = img.getRGB(x, y);
                    int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
                    h[y * SIZE + x] = (float) (r * 256 + g + b / 256.0 - 32768);
                }
            }
            return h;
        } catch (IOException e) {
            return MISSING;
        }
    }
}
