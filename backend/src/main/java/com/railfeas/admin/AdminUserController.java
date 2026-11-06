package com.railfeas.admin;

import com.railfeas.common.ApiException;
import com.railfeas.scenario.ScenarioRepository;
import com.railfeas.user.Role;
import com.railfeas.user.User;
import com.railfeas.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 관리·시나리오 생성 통계 (제안서 기능 8). `/api/admin/**` 은 ADMIN 만 들어온다(`SecurityConfig`).
 *
 * **자기 자신은 바꾸거나 지울 수 없다** — 그래서 관리자가 0명이 되는 일이 없다(행동하는 관리자가 늘 남는다).
 * **관리자 계정은 일반 회원으로 내린 뒤에만 지운다** — 지우면 되돌릴 수 없어 두 단계로 둔다. 시나리오는 외래키의
 * `ON DELETE CASCADE` 로 함께 지워진다. 권한은 요청마다 DB 에서 읽고 지운 계정은 익명이 되므로
 * (`JwtAuthenticationFilter`) 바꾸면 그 사람의 다음 요청부터 바로 적용된다.
 *
 * **통계는 지금 남아 있는 것만 센다** — 생성 기록을 따로 남기지 않아 지운 시나리오는 세지 못한다.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "관리자 — 회원·통계")
@RestController
@RequestMapping("/api/admin")
public class AdminUserController {

    static final int STATS_DAYS = 30;

    private final UserRepository users;
    private final ScenarioRepository scenarios;

    public AdminUserController(UserRepository users, ScenarioRepository scenarios) {
        this.users = users;
        this.scenarios = scenarios;
    }

    /** 비밀번호·소셜 고유 id 는 내보내지 않는다 */
    public record UserRow(Long id, String email, String nickname, com.railfeas.user.AuthProvider provider,
                          Role role, LocalDateTime createdAt, long scenarioCount) {
        static UserRow of(User u, long scenarioCount) {
            return new UserRow(u.getId(), u.getEmail(), u.getNickname(), u.getProvider(), u.getRole(),
                    u.getCreatedAt(), scenarioCount);
        }
    }

    public record RoleRequest(@NotNull Role role) {
    }

    public record DayCount(LocalDate date, long count) {
    }

    /**
     * noRecommendation — 계산했지만 내세울 안이 없는 시나리오(전부 B/C 0 등).
     * 지역별은 없다 — `scenario.region_class` 를 채우는 코드가 없어 전부 NULL 이다
     */
    public record Stats(long users, Map<String, Long> byProvider, long scenarios, long calculated,
                        Map<String, Long> byMode, long noRecommendation, List<DayCount> daily) {
    }

    @GetMapping("/users")
    @Transactional(readOnly = true)
    public List<UserRow> users() {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : scenarios.countByUser()) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return users.findAll().stream()
                .sorted(Comparator.comparing(User::getId))
                .map(u -> UserRow.of(u, counts.getOrDefault(u.getId(), 0L)))
                .toList();
    }

    @PutMapping("/users/{id}/role")
    @Transactional
    public UserRow changeRole(@AuthenticationPrincipal Long me, @PathVariable Long id,
                              @Valid @RequestBody RoleRequest request) {
        User target = find(id);
        if (id.equals(me)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "자기 권한은 바꿀 수 없습니다 — 다른 관리자에게 맡기세요");
        }
        target.changeRole(request.role());
        return UserRow.of(target, scenarios.countByUserId(id));
    }

    /** 204 — 화면의 `request` 는 204 가 아니면 본문을 JSON 으로 읽어, 빈 200 이면 성공이 오류로 뜬다 */
    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal Long me, @PathVariable Long id) {
        User target = find(id);
        if (id.equals(me)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "자기 계정은 지울 수 없습니다");
        }
        if (target.getRole() == Role.ADMIN) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "관리자는 일반 회원으로 내린 뒤에 지울 수 있습니다");
        }
        users.delete(target);
    }

    @GetMapping("/stats")
    @Transactional(readOnly = true)
    public Stats stats() {
        List<User> all = users.findAll();
        Map<String, Long> byProvider = new LinkedHashMap<>();
        for (User u : all) {
            byProvider.merge(u.getProvider().name(), 1L, Long::sum);
        }
        long calculated = scenarios.countCalculated();
        Map<String, Long> byMode = named(scenarios.countByRecommendedMode());
        long recommended = byMode.values().stream().mapToLong(Long::longValue).sum();
        LocalDate today = LocalDate.now();
        return new Stats(all.size(), byProvider, scenarios.count(), calculated, byMode,
                Math.max(0, calculated - recommended),
                daily(scenarios.createdSince(today.minusDays(STATS_DAYS - 1L).atStartOfDay()), today, STATS_DAYS));
    }

    /** 오늘까지 {@code days} 일의 생성 수. 빈 날은 0 — 안 채우면 막대가 날짜를 건너뛰어 활동이 이어진 것처럼 보인다 */
    static List<DayCount> daily(List<LocalDateTime> created, LocalDate today, int days) {
        Map<LocalDate, Long> counts = new HashMap<>();
        for (LocalDateTime t : created) {
            counts.merge(t.toLocalDate(), 1L, Long::sum);
        }
        List<DayCount> out = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            out.add(new DayCount(d, counts.getOrDefault(d, 0L)));
        }
        return out;
    }

    /** {값, 수} 행을 이름표로 */
    private static Map<String, Long> named(List<Object[]> rows) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object[] row : rows) {
            out.put(row[0].toString(), (Long) row[1]);
        }
        return out;
    }

    private User find(Long id) {
        return users.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다"));
    }
}
