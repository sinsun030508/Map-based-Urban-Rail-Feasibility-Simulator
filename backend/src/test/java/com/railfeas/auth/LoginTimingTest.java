package com.railfeas.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.railfeas.common.ApiException;
import com.railfeas.user.AuthProvider;
import com.railfeas.user.Role;
import com.railfeas.user.User;
import com.railfeas.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 로그인 응답 **시간**으로 계정이 있는지 알 수 없어야 한다(11-26 보안 점검). 문구는 같았지만 없는 계정은 비밀번호 해시(bcrypt)를 건너뛰어
 * 36ms, 있는 계정은 280ms 라 시간만 재면 가입 여부가 드러났다. 그래서 없는 계정·소셜 계정(비밀번호 없음)도 해시 비교를 **꼭 한 번** 한다.
 * 시간을 직접 재면 테스트가 흔들려 비교 횟수를 센다(실제 시간은 로컬 서버로 다시 쟀다 — 노트)
 */
class LoginTimingTest {

    /** 비교 횟수만 센다 — 값은 언제나 「틀림」 */
    private static class CountingEncoder implements PasswordEncoder {
        int matches;

        @Override
        public String encode(CharSequence raw) {
            return "hash:" + raw;
        }

        @Override
        public boolean matches(CharSequence raw, String encoded) {
            matches++;
            return false;
        }
    }

    private static User user(String password) {
        return User.builder().email("a@b.test").password(password).nickname("n").provider(AuthProvider.LOCAL).role(Role.USER).build();
    }

    @Test
    @DisplayName("없는 계정으로 로그인해도 해시 비교를 한 번 한다 — 있는 계정과 걸리는 시간이 같다")
    void unknownEmailStillPaysForOneHashComparison() {
        UserRepository users = mock(UserRepository.class);
        when(users.findByEmail("nobody@b.test")).thenReturn(Optional.empty());
        CountingEncoder encoder = new CountingEncoder();
        AuthService auth = new AuthService(users, encoder, null);

        assertThatThrownBy(() -> auth.login(new AuthDto.LoginRequest("nobody@b.test", "whatever1")))
                .isInstanceOf(ApiException.class).hasMessage("이메일 또는 비밀번호가 올바르지 않습니다");
        assertThat(encoder.matches).isEqualTo(1);
    }

    @Test
    @DisplayName("소셜 계정(비밀번호 없음)도 해시 비교를 한 번 한다 — 소셜 가입 여부도 시간으로 안 드러난다")
    void socialAccountStillPaysForOneHashComparison() {
        UserRepository users = mock(UserRepository.class);
        when(users.findByEmail("a@b.test")).thenReturn(Optional.of(user(null)));
        CountingEncoder encoder = new CountingEncoder();
        AuthService auth = new AuthService(users, encoder, null);

        assertThatThrownBy(() -> auth.login(new AuthDto.LoginRequest("a@b.test", "whatever1"))).isInstanceOf(ApiException.class);
        assertThat(encoder.matches).isEqualTo(1);
    }

    @Test
    @DisplayName("있는 계정의 틀린 비밀번호도 비교는 한 번 — 세 경우가 같은 일을 한다")
    void wrongPasswordComparesOnce() {
        UserRepository users = mock(UserRepository.class);
        when(users.findByEmail("a@b.test")).thenReturn(Optional.of(user("hash:right")));
        CountingEncoder encoder = new CountingEncoder();
        AuthService auth = new AuthService(users, encoder, null);

        assertThatThrownBy(() -> auth.login(new AuthDto.LoginRequest("a@b.test", "wrong"))).isInstanceOf(ApiException.class);
        assertThat(encoder.matches).isEqualTo(1);
    }
}
