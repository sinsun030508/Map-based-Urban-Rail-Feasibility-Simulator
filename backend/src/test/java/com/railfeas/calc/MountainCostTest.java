package com.railfeas.calc;

import static org.assertj.core.api.Assertions.assertThat;

import com.railfeas.common.ModeType;
import com.railfeas.common.StructureType;
import com.railfeas.reference.CostStandard;
import com.railfeas.reference.ModeCapacity;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 산 구간의 비용 차이 — 앱의 건설비는 (수단×구조)마다 「고정비 + km단가×연장 + 역단가×역수」 다.
 * 고가 선의 T km 를 땅속으로 바꾸면 그 몫만큼 km단가 차이가 붙는다.
 */
class MountainCostTest {

    private static <T> T newInstance(Class<T> type, Object... nameValues) {
        try {
            Constructor<T> c = type.getDeclaredConstructor();
            c.setAccessible(true);
            T instance = c.newInstance();
            for (int i = 0; i < nameValues.length; i += 2) {
                Field f = type.getDeclaredField((String) nameValues[i]);
                f.setAccessible(true);
                f.set(instance, nameValues[i + 1]);
            }
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CostStandard std(ModeType mode, StructureType structure, long perKm) {
        return newInstance(CostStandard.class, "modeType", mode, "structureType", structure,
                "fixedCost", 1_000L, "costPerKm", perKm, "costPerStation", 100L, "active", true);
    }

    private static Map<ModeType, ModeCapacity> speed(ModeType mode, double kmh) {
        return Map.of(mode, newInstance(ModeCapacity.class, "modeType", mode, "speedKmh", BigDecimal.valueOf(kmh)));
    }

    private static MountainDetour.Mountain mountain(int index, double tunnelKm, boolean detoured, double addedKm) {
        return new MountainDetour.Mountain(index, tunnelKm, detoured, false, addedKm, detoured ? null : "이유", List.of());
    }

    private static final org.assertj.core.data.Offset<Double> NEAR = org.assertj.core.data.Offset.offset(0.01);

    @Test
    @DisplayName("km당 터널 추가분은 KDI 산악 NATM 터널 − 그 행의 기준 구조(2019 → 2025) — 터널이 더 싸면 0, 지하 행은 0")
    void 추가분은_KDI_표준공사비다() {
        double f = MountainCost.DEFLATOR_2019;
        assertThat(MountainCost.extraPerKm(std(ModeType.HEAVY_METRO, StructureType.ELEVATED, 550))).isCloseTo((319 - 317) * f, NEAR);
        assertThat(MountainCost.extraPerKm(std(ModeType.LIGHT_RAIL, StructureType.ELEVATED, 348))).isCloseTo((265.6 - 190.8) * f, NEAR);
        assertThat(MountainCost.extraPerKm(std(ModeType.DOUBLE_ELEC, StructureType.ELEVATED, 423))).isZero();   // 터널 192 < 교량 317
        assertThat(MountainCost.extraPerKm(std(ModeType.EXPRESS_RAIL, StructureType.ELEVATED, 335))).isZero();
        assertThat(MountainCost.extraPerKm(std(ModeType.TRAM, StructureType.AT_GRADE, 273))).isCloseTo((265.6 - 13.4) * f, NEAR);
        assertThat(MountainCost.extraPerKm(std(ModeType.HEAVY_METRO, StructureType.UNDERGROUND, 875))).isZero();
        // BRT 는 도로 2차로 터널(14,584천원/m) − 그 행 km단가
        assertThat(MountainCost.extraPerKm(std(ModeType.BRT_HIGH, StructureType.AT_GRADE, 24))).isCloseTo(145.84 * f - 24, NEAR);
        assertThat(MountainCost.extraPerKm(std(ModeType.BRT_LOW, StructureType.AT_GRADE, 4))).isCloseTo(145.84 * f - 4, NEAR);
    }

    @Test
    @DisplayName("물가 배수는 S4 시드의 2019년 GDP 디플레이터 배수와 같다")
    void 물가_배수는_S4_와_같다() throws Exception {
        String s4 = java.nio.file.Files.readString(java.nio.file.Path.of("../db/seed/S4__price_index.sql"));
        var m = java.util.regex.Pattern.compile("\\(2019,\\s*[0-9.]+,\\s*([0-9.]+)").matcher(s4);
        assertThat(m.find()).isTrue();
        assertThat(MountainCost.DEFLATOR_2019).isEqualTo(Double.parseDouble(m.group(1)));
    }

    @Test
    @DisplayName("뚫는 안은 터널 km × km당 추가분, 돌아가는 안은 늘어난 길이 + 못 돌아간 구간의 터널이다")
    void 두_안() {
        var plan = new MountainDetour.Plan(List.of(), List.of(mountain(1, 2.0, true, 3.0), mountain(2, 1.0, false, 0)));
        var d = MountainCost.deltas(plan, List.of(std(ModeType.LIGHT_RAIL, StructureType.ELEVATED, 400)),
                speed(ModeType.LIGHT_RAIL, 30), null).get(0);
        double extra = (265.6 - 190.8) * MountainCost.DEFLATOR_2019;

        assertThat(d.tunnelExtraCost()).isEqualTo(Math.round(3.0 * extra));
        assertThat(d.detourExtraCost()).isEqualTo(Math.round(3.0 * 400) + Math.round(1.0 * extra));
        assertThat(d.detourExtraMin()).isEqualTo(6.0);
        // 지하 행은 빠진다
        assertThat(MountainCost.deltas(plan, List.of(std(ModeType.HEAVY_METRO, StructureType.UNDERGROUND, 875)),
                Map.of(), null)).isEmpty();
    }

    @Test
    @DisplayName("트램도 이제 안다 — 경량전철 NATM − 노면전차(같은 KDI 표)")
    void 트램() {
        var plan = new MountainDetour.Plan(List.of(), List.of(mountain(1, 2.0, false, 0)));
        var d = MountainCost.deltas(plan, List.of(std(ModeType.TRAM, StructureType.AT_GRADE, 273)),
                speed(ModeType.TRAM, 22.1), null).get(0);
        assertThat(d.tunnelExtraCost()).isEqualTo(Math.round(2.0 * (265.6 - 13.4) * MountainCost.DEFLATOR_2019));
    }

    @Test
    @DisplayName("BRT 는 도로 기울기로 잰 터널 km 로 센다 — 모르면 모름, 돌아가는 안은 없다")
    void BRT_는_도로_터널로() {
        var plan = new MountainDetour.Plan(List.of(), List.of(mountain(1, 2.0, true, 3.0)));
        var brt = List.of(std(ModeType.BRT_HIGH, StructureType.AT_GRADE, 24));
        var d = MountainCost.deltas(plan, brt, Map.of(), 1.5).get(0);
        assertThat(d.tunnelExtraCost()).isEqualTo(Math.round(1.5 * (145.84 * MountainCost.DEFLATOR_2019 - 24)));
        assertThat(d.detourExtraCost()).isNull();
        assertThat(MountainCost.deltas(plan, brt, Map.of(), null).get(0).tunnelExtraCost()).isNull();
    }

    @Test
    @DisplayName("손익분기는 km당 추가분 ÷ 그 행 km단가의 중앙값이다 — BRT·지하는 빠진다")
    void 손익분기() {
        double f = MountainCost.DEFLATOR_2019;
        double heavy = (319 - 317) * f / 550, light = (265.6 - 190.8) * f / 400, tram = (265.6 - 13.4) * f / 273;
        // 정렬: 복선전철 0, 지하철, 경전철, 트램 → 가운데 둘의 평균
        assertThat(MountainCost.breakEven(List.of(
                std(ModeType.HEAVY_METRO, StructureType.UNDERGROUND, 875),
                std(ModeType.HEAVY_METRO, StructureType.ELEVATED, 550),
                std(ModeType.LIGHT_RAIL, StructureType.ELEVATED, 400),
                std(ModeType.DOUBLE_ELEC, StructureType.ELEVATED, 400),
                std(ModeType.TRAM, StructureType.AT_GRADE, 273),
                std(ModeType.BRT_HIGH, StructureType.AT_GRADE, 24))))
                .isCloseTo((heavy + light) / 2, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(tram).isGreaterThan(light);
    }

    @Test
    @DisplayName("견줄 행이 없으면(BRT·지하뿐) 손익분기를 모른다")
    void 손익분기를_모른다() {
        assertThat(MountainCost.breakEven(List.of(std(ModeType.BRT_HIGH, StructureType.AT_GRADE, 24),
                std(ModeType.HEAVY_METRO, StructureType.UNDERGROUND, 875)))).isNull();
    }
}
