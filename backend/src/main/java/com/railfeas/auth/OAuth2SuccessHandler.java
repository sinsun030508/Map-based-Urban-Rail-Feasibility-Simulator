package com.railfeas.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.railfeas.common.ApiException;
import com.railfeas.user.UserRepository;

/** 소셜 로그인 성공 → JWT 발급 → 프론트엔드 주소로 토큰을 붙여 리다이렉트 */
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
        String token = users.findById(userId)
                .map(tokenProvider::createToken)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                        "소셜 로그인 사용자를 찾을 수 없습니다"));

        String target = redirectUri + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        getRedirectStrategy().sendRedirect(request, response, target);
    }
}
