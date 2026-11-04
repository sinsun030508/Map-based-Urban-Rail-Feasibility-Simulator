package com.railfeas.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.railfeas.user.UserRepository;

/**
 * 소셜 로그인 성공 → JWT 발급 → 프론트엔드 주소로 리다이렉트.
 *
 * **토큰은 `#` 뒤에 싣는다.** `?token=` 이면 방문 기록·북마크·다음 페이지의 Referer·서버 로그에 남는다.
 * `#` 뒤(fragment)는 브라우저가 서버로 보내지 않는다. 화면은 받자마자 주소에서 지운다(`oauthCallback.js`).
 * 실패도 같은 주소로 `#error=` 를 붙여 보낸다 — 필터 안에서 예외를 던지면 화면이 아니라 500 이 된다
 */
@Component
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtTokenProvider tokenProvider;
    private final UserRepository users;
    private final String redirectUri;

    public OAuth2SuccessHandler(JwtTokenProvider tokenProvider, UserRepository users,
                                @Value("${app.oauth2.redirect-uri}") String redirectUri) {
        this.tokenProvider = tokenProvider;
        this.users = users;
        this.redirectUri = redirectUri;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User principal = (OAuth2User) authentication.getPrincipal();
        Long userId = ((Number) principal.getAttribute("userId")).longValue();
        String target = users.findById(userId)
                .map(u -> redirectUri + "#token=" + encode(tokenProvider.createToken(u)))
                .orElse(redirectUri + "#error=" + encode("소셜 로그인 사용자를 찾을 수 없습니다"));
        getRedirectStrategy().sendRedirect(request, response, target);
    }

    static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
