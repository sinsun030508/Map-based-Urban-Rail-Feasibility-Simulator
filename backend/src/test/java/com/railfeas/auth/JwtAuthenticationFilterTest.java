package com.railfeas.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.railfeas.user.AuthProvider;
import com.railfeas.user.Role;
import com.railfeas.user.User;
import com.railfeas.user.UserRepository;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * **권한은 토큰이 아니라 DB 에서 읽는다.**
 *
 * <p>전에는 로그인할 때 토큰에 적은 `role` 을 그대로 믿었다. 그래서 관리자를 일반 사용자로
 * 내려도 **이미 받은 토큰이 만료될 때까지(720분) 관리자 API 가 열려 있었다** — 서버 권한
 * 점검(11-04)에서 ADMIN 으로 받은 토큰을 USER 로 내린 뒤 다시 써 보니 `/api/admin/modes` 가
 * 200 이었다. 계정을 지워도 그 토큰은 로그인된 것으로 통했다.
 */
class JwtAuthenticationFilterTest {

    private static final long ID = 7L;

    private final JwtTokenProvider tokens =
            new JwtTokenProvider(new JwtProperties("test-secret-that-is-at-least-32-bytes!!", 60));
    private final UserRepository users = Mockito.mock(UserRepository.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokens, users);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static User user(Role role) {
        User u = User.builder().email("a@example.test").nickname("n")
                .provider(AuthProvider.LOCAL).role(role).build();
        try {
            Field id = User.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(u, ID);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return u;
    }

    private Authentication run(String token) throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    @DisplayName("관리자에서 내려간 사람의 옛 토큰은 일반 사용자 권한만 갖는다")
    void demotedTokenLosesAdmin() throws Exception {
        String adminToken = tokens.createToken(user(Role.ADMIN));
        Mockito.when(users.findById(ID)).thenReturn(Optional.of(user(Role.USER)));

        Authentication auth = run(adminToken);

        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("지워진 계정의 토큰은 인증하지 않는다")
    void deletedAccountIsAnonymous() throws Exception {
        String token = tokens.createToken(user(Role.USER));
        Mockito.when(users.findById(ID)).thenReturn(Optional.empty());

        assertThat(run(token)).isNull();
    }

    @Test
    @DisplayName("DB 도 관리자면 관리자다 — 사용자 번호는 토큰에서 온다")
    void adminStaysAdmin() throws Exception {
        String token = tokens.createToken(user(Role.ADMIN));
        Mockito.when(users.findById(ID)).thenReturn(Optional.of(user(Role.ADMIN)));

        Authentication auth = run(token);

        assertThat(auth.getPrincipal()).isEqualTo(ID);
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }
}
