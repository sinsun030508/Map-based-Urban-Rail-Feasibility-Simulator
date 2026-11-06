package com.railfeas.auth;

import com.railfeas.common.ApiException;
import com.railfeas.user.UserRepository;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@io.swagger.v3.oas.annotations.tags.Tag(name = "인증")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserRepository users;
    private final ObjectProvider<ClientRegistrationRepository> clients;

    public AuthController(AuthService authService, UserRepository users,
                          ObjectProvider<ClientRegistrationRepository> clients) {
        this.authService = authService;
        this.users = users;
        this.clients = clients;
    }

    @PostMapping("/signup")
    public AuthDto.TokenResponse signup(@Valid @RequestBody AuthDto.SignupRequest request) {
        return authService.signup(request);
    }

    @PostMapping("/login")
    public AuthDto.TokenResponse login(@Valid @RequestBody AuthDto.LoginRequest request) {
        return authService.login(request);
    }

    /**
     * 켜져 있는 소셜 로그인(`google`·`kakao`). **화면은 이것으로 버튼을 고른다** — 키가 없으면
     * 소셜 로그인이 꺼져 있어(`SecurityConfig`) 버튼을 누르면 404 다. 그래서 빈 목록이면 버튼을 숨긴다
     */
    @GetMapping("/providers")
    public List<String> providers() {
        List<String> ids = new ArrayList<>();
        if (clients.getIfAvailable() instanceof Iterable<?> registrations) {
            for (Object r : registrations) {
                ids.add(((ClientRegistration) r).getRegistrationId());
            }
        }
        return ids;
    }

    /**
     * 토큰의 사람. 소셜 로그인은 `#token=` 만 들고 화면으로 돌아오므로 닉네임·권한을 여기서 받는다.
     * `/api/auth/**` 는 모두에게 열려 있어 토큰이 없으면 principal 이 null 이다 — 그때는 401
     */
    @GetMapping("/me")
    public AuthDto.MeResponse me(@AuthenticationPrincipal Long userId) {
        if (userId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다");
        }
        return users.findById(userId)
                .map(u -> new AuthDto.MeResponse(u.getId(), u.getNickname(), u.getRole().name()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다"));
    }
}
