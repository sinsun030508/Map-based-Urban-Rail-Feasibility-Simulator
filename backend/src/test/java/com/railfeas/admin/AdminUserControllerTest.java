package com.railfeas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railfeas.common.ApiException;
import com.railfeas.common.ModeType;
import com.railfeas.scenario.ScenarioRepository;
import com.railfeas.user.AuthProvider;
import com.railfeas.user.Role;
import com.railfeas.user.User;
import com.railfeas.user.UserRepository;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 회원 관리 — 되돌릴 수 없는 일이 있어 **막는 규칙**이 핵심이다. 자기 자신은 바꾸거나 지울 수 없으므로
 * 관리자가 0명이 되는 일이 없다(행동하는 관리자가 늘 남는다). 관리자 계정은 일반 회원으로 내린 뒤에만 지운다.
 */
class AdminUserControllerTest {

    private static final long ME = 1L;

    private final UserRepository users = Mockito.mock(UserRepository.class);
    private final ScenarioRepository scenarios = Mockito.mock(ScenarioRepository.class);
    private final AdminUserController controller = new AdminUserController(users, scenarios);

    private static User user(long id, Role role, AuthProvider provider) {
        User u = User.builder().email("u" + id + "@example.test").password("해시").nickname("회원" + id)
                .provider(provider).role(role).build();
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    private User stored(long id, Role role) {
        User u = user(id, role, AuthProvider.LOCAL);
        Mockito.when(users.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    private static HttpStatus statusOf(Runnable call) {
        try {
            call.run();
        } catch (ApiException e) {
            return e.getStatus();
        }
        throw new AssertionError("막혀야 한다");
    }

    @Test
    @DisplayName("목록은 시나리오 수를 붙인다 — 없는 회원은 0")
    void 목록() {
        Mockito.when(users.findAll()).thenReturn(List.of(user(2, Role.USER, AuthProvider.KAKAO), user(3, Role.ADMIN, AuthProvider.LOCAL)));
        Mockito.when(scenarios.countByUser()).thenReturn(List.<Object[]>of(new Object[]{2L, 5L}));

        var rows = controller.users();

        assertThat(rows).extracting(AdminUserController.UserRow::id).containsExactly(2L, 3L);
        assertThat(rows.get(0).scenarioCount()).isEqualTo(5);
        assertThat(rows.get(0).provider()).isEqualTo(AuthProvider.KAKAO);
        assertThat(rows.get(1).scenarioCount()).isZero();
    }

    @Test
    @DisplayName("응답에는 비밀번호 칸이 없다")
    void 비밀번호를_내보내지_않는다() {
        assertThat(Arrays.stream(AdminUserController.UserRow.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain("password", "providerUid");
    }

    @Test
    @DisplayName("권한을 바꾼다")
    void 권한_변경() {
        User target = stored(2, Role.USER);
        Mockito.when(scenarios.countByUserId(2L)).thenReturn(4L);

        var row = controller.changeRole(ME, 2L, new AdminUserController.RoleRequest(Role.ADMIN));

        assertThat(target.getRole()).isEqualTo(Role.ADMIN);
        assertThat(row.role()).isEqualTo(Role.ADMIN);
        // 응답 그대로 표의 행을 바꾸므로 시나리오 수도 참이어야 한다
        assertThat(row.scenarioCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("자기 권한은 못 바꾼다 — 관리자가 0명이 되는 길을 막는다")
    void 자기_권한은_못_바꾼다() {
        User me = stored(ME, Role.ADMIN);

        assertThat(statusOf(() -> controller.changeRole(ME, ME, new AdminUserController.RoleRequest(Role.USER))))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(me.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("회원을 지운다")
    void 삭제() {
        User target = stored(2, Role.USER);

        controller.delete(ME, 2L);

        Mockito.verify(users).delete(target);
    }

    @Test
    @DisplayName("자기 자신은 못 지운다")
    void 자기_삭제는_막는다() {
        stored(ME, Role.ADMIN);

        // 「일반 회원으로 내린 뒤」 안내가 나오면 안 된다 — 자기 권한은 못 바꾸니 따를 수 없는 말이다
        assertThatThrownBy(() -> controller.delete(ME, ME))
                .isInstanceOf(ApiException.class).hasMessageContaining("자기 계정");
        Mockito.verify(users, Mockito.never()).delete(Mockito.any());
    }

    @Test
    @DisplayName("관리자는 일반 회원으로 내린 뒤에만 지운다 — 되돌릴 수 없어 두 단계로 둔다")
    void 관리자는_바로_못_지운다() {
        stored(2, Role.ADMIN);

        assertThat(statusOf(() -> controller.delete(ME, 2L))).isEqualTo(HttpStatus.BAD_REQUEST);
        Mockito.verify(users, Mockito.never()).delete(Mockito.any());
    }

    @Test
    @DisplayName("없는 회원은 404")
    void 없는_회원() {
        Mockito.when(users.findById(9L)).thenReturn(Optional.empty());

        assertThat(statusOf(() -> controller.delete(ME, 9L))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> controller.changeRole(ME, 9L, new AdminUserController.RoleRequest(Role.USER)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("일별 생성 수는 빈 날을 0 으로 채우고 오래된 날부터 늘어놓는다 — 기간 밖은 뺀다")
    void 일별_집계() {
        LocalDate today = LocalDate.of(2026, 11, 6);
        var days = AdminUserController.daily(List.of(
                LocalDateTime.of(2026, 11, 6, 0, 0), LocalDateTime.of(2026, 11, 6, 23, 59),
                LocalDateTime.of(2026, 11, 4, 12, 0),
                LocalDateTime.of(2026, 11, 3, 23, 59)), today, 3);

        assertThat(days).extracting(AdminUserController.DayCount::date)
                .containsExactly(LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 5), today);
        assertThat(days).extracting(AdminUserController.DayCount::count).containsExactly(1L, 0L, 2L);
    }

    @Test
    @DisplayName("통계 — 가입 수단·추천 수단별로 세고, 계산만 하고 추천이 없는 시나리오도 드러난다")
    void 통계() {
        Mockito.when(users.findAll()).thenReturn(List.of(user(1, Role.ADMIN, AuthProvider.LOCAL),
                user(2, Role.USER, AuthProvider.KAKAO), user(3, Role.USER, AuthProvider.KAKAO)));
        Mockito.when(scenarios.count()).thenReturn(7L);
        Mockito.when(scenarios.countCalculated()).thenReturn(5L);
        Mockito.when(scenarios.countByRecommendedMode()).thenReturn(List.<Object[]>of(new Object[]{ModeType.TRAM, 3L}));
        Mockito.when(scenarios.createdSince(Mockito.any())).thenReturn(List.of());

        var s = controller.stats();

        assertThat(s.users()).isEqualTo(3);
        assertThat(s.byProvider()).containsEntry("KAKAO", 2L).containsEntry("LOCAL", 1L);
        assertThat(s.scenarios()).isEqualTo(7);
        assertThat(s.calculated()).isEqualTo(5);
        assertThat(s.byMode()).containsEntry("TRAM", 3L);
        // 계산했지만 추천이 없는 시나리오(전부 B/C 0 등) — 수단별 합과 계산 수의 차이가 조용히 사라지지 않게
        assertThat(s.noRecommendation()).isEqualTo(2);
        assertThat(s.daily()).hasSize(AdminUserController.STATS_DAYS);
    }
}
