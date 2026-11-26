package com.railfeas.auth;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.railfeas.common.ApiException;
import com.railfeas.user.AuthProvider;
import com.railfeas.user.Role;
import com.railfeas.user.User;
import com.railfeas.user.UserRepository;

@Service
public class AuthService {

    private static final String DUPLICATE_EMAIL = "이미 가입된 이메일입니다";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    /**
     * 없는 계정·소셜 계정(비밀번호 없음)과 비교할 가짜 해시. 그냥 401 을 내면 해시 비교(bcrypt 약 0.25초)를 건너뛰어 응답이 빨라
     * **시간만 재도 가입 여부가 드러났다**(11-26 보안 점검 — 없는 계정 36ms, 있는 계정 280ms). 같은 비용으로 한 번 비교하고 버린다
     */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.dummyHash = passwordEncoder.encode("railfeas-login-timing-dummy");
    }

    @Transactional
    public AuthDto.TokenResponse signup(AuthDto.SignupRequest request) {
        if (users.existsByEmail(request.email())) {
            throw new ApiException(HttpStatus.CONFLICT, DUPLICATE_EMAIL);
        }
        User user;
        try {
            user = users.save(User.builder()
                    .email(request.email())
                    .password(passwordEncoder.encode(request.password()))
                    .nickname(request.nickname())
                    .provider(AuthProvider.LOCAL)
                    .role(Role.USER)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // 위 확인과 저장 사이에 같은 이메일이 먼저 들어왔다(가입 버튼을 빠르게 두 번) — 이메일 유니크 제약이 막는다.
            // 잡지 않으면 처리기가 없어 /error 로 넘어가 403·500 으로 보인다(11-21 코드 점검). id 가 IDENTITY 라 save 가 바로 INSERT 해 여기서 잡힌다
            throw new ApiException(HttpStatus.CONFLICT, DUPLICATE_EMAIL);
        }
        return toToken(user);
    }

    @Transactional(readOnly = true)
    public AuthDto.TokenResponse login(AuthDto.LoginRequest request) {
        User user = users.findByEmail(request.email()).orElse(null);
        // 소셜 가입 계정은 password 가 null 이라 비밀번호 로그인을 막는다. 없는 계정·소셜 계정도 가짜 해시와 한 번 비교한다 — 시간을 같게
        String stored = user == null ? null : user.getPassword();
        boolean matches = passwordEncoder.matches(request.password(), stored == null ? dummyHash : stored);
        if (stored == null || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다");
        }
        return toToken(user);
    }

    private AuthDto.TokenResponse toToken(User user) {
        return new AuthDto.TokenResponse(tokenProvider.createToken(user),
                user.getId(), user.getNickname(), user.getRole().name());
    }
}
