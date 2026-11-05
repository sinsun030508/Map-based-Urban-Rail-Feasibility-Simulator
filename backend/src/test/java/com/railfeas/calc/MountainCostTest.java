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
        return new MountainDetour.Mountain(index, tunnelKm, detoured, addedKm, detoured ? null : "이유", List.of());
    }

    @Test
    @DisplayName("터널비는 지하 km단가와의 차이다 — 지하 행은 빠진다")
    void 터널비는_지하와의_단가차() {
        var plan = new MountainDetour.Plan(List.of(), List.of(mountain(1, 2.0, false, 0)));
        var deltas = MountainCost.deltas(plan, List.of(
                std(ModeType.HEAVY_METRO, StructureType.UNDERGROUND, 1_000),
                std(ModeType.HEAVY_METRO, StructureType.ELEVATED, 400)), speed(ModeType.HEAVY_METRO, 30));

        assertThat(deltas).hasSize(1);
        var d = deltas.get(0);
        assertThat(d.structure()).isEqualTo(StructureType.ELEVATED);
        assertThat(d.tunnelExtraCost()).isEqualTo(1_200L);
        // 돌아갈 수 있는 구간이 없다 — 돌아가는 안이 없다는 뜻이지 0원이 아니다
        assertThat(d.detourExtraCost()).isNull();
        assertThat(d.detourExtraMin()).isNull();
    }

    @Test
    @DisplayName("돌아가는 안은 늘어난 길이 + 못 돌아간 구간의 터널이다")
    void 돌아가는_안은_남은_터널을_함께_센다() {
        var plan = new MountainDetour.Plan(List.of(), List.of(mountain(1, 2.0, true, 3.0), mountain(2, 1.0, false, 0)));
        var d = MountainCost.deltas(plan, List.of(
                std(ModeType.HEAVY_METRO, StructureType.UNDERGROUND, 1_000),
                std(ModeType.HEAVY_METRO, StructureType.ELEVATED, 400)), speed(ModeType.HEAVY_METRO, 30)).get(0);

        assertThat(d.tunnelExtraCost()).isEqualTo(1_800L);          // 3km × 600
        assertThat(d.detourExtraCost()).isEqualTo(1_800L);          // 3km × 400 + 1km × 600
        assertThat(d.detourExtraMin()).isEqualTo(6.0);               // 3km ÷ 30km/h
    }

    @Test
    @DisplayName("트램은 지하 단가가 없어 터널비를 모른다 — 우회비는 있다")
    void 트램은_터널비를_모른다() {
        var all = new MountainDetour.Plan(List.of(), List.of(mountain(1, 2.0, true, 3.0)));
        var d = MountainCost.deltas(all, List.of(std(ModeType.TRAM, StructureType.AT_GRADE, 273)),
                speed(ModeType.TRAM, 22.1)).get(0);
        assertThat(d.tunnelExtraCost()).isNull();
        assertThat(d.detourExtraCost()).isEqualTo(819L);

        // 못 돌아간 구간이 남으면 그 터널값도 모른다 — 일부만 더해 싸 보이게 하지 않는다
        var some = new MountainDetour.Plan(List.of(), List.of(mountain(1, 2.0, true, 3.0), mountain(2, 1.0, false, 0)));
        assertThat(MountainCost.deltas(some, List.of(std(ModeType.TRAM, StructureType.AT_GRADE, 273)),
                speed(ModeType.TRAM, 22.1)).get(0).detourExtraCost()).isNull();
    }

    @Test
    @DisplayName("BRT 는 도로 기준이라 빠진다")
    void BRT_는_빠진다() {
        var plan = new MountainDetour.Plan(List.of(), List.of(mountain(1, 2.0, true, 3.0)));
        assertThat(MountainCost.deltas(plan, List.of(
                std(ModeType.BRT_HIGH, StructureType.AT_GRADE, 24),
                std(ModeType.BRT_LOW, StructureType.AT_GRADE, 4)), Map.of())).isEmpty();
    }
}
