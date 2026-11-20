package com.railfeas.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.railfeas.common.ApiException;
import com.railfeas.user.User;
import com.railfeas.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;

/**
 * 같은 이메일로 가입이 동시에 들어올 때(가입 버튼을 빠르게 두 번) — 둘 다 「없는 이메일」 을 확인하고 INSERT 해 둘째가 유니크 제약에 걸린다(11-21 코드 점검).
 * 그 예외를 잡지 않으면 처리기가 없어 /error 로 넘어가 403·500 처럼 보이고, 첫 요청은 성공했는데 사용자는 가입이 실패한 줄 안다
 */
class SignupRaceTest {

    @Test
    @DisplayName("확인은 통과했는데 저장에서 이메일 유니크 제약에 걸리면 409 「이미 가입된 이메일입니다」 다")
    void duplicateInsertBecomesConflict() {
        UserRepository users = mock(UserRepository.class);
        when(users.existsByEmail("a@b.test")).thenReturn(false);
        when(users.save(any(User.class))).thenThrow(new DataIntegrityViolationException("Duplicate entry 'a@b.test' for key 'email'"));
        AuthService auth = new AuthService(users, NoOpPasswordEncoder.getInstance(), null);

        ApiException e = catchThrowableOfType(
                () -> auth.signup(new AuthDto.SignupRequest("a@b.test", "password123", "닉네임")), ApiException.class);

        assertThat(e).as("처리기가 받는 ApiException 이어야 한다 — 다른 예외면 /error 로 간다").isNotNull();
        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getMessage()).isEqualTo("이미 가입된 이메일입니다");
    }
}
