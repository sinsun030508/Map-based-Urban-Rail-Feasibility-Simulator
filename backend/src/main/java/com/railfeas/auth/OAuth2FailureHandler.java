package com.railfeas.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * 소셜 로그인 실패(사용자가 동의를 거절했거나 공급자가 오류를 냈을 때) → 화면의 같은 주소로 `#error=`.
 * 걸지 않으면 Spring 기본값이 백엔드의 `/login?error` 로 보내 **화면이 아닌 곳**에 떨어진다
 */
@Component
public class OAuth2FailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private final String redirectUri;

    public OAuth2FailureHandler(@Value("${app.oauth2.redirect-uri}") String redirectUri) {
        this.redirectUri = redirectUri;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        getRedirectStrategy().sendRedirect(request, response,
                redirectUri + "#error=" + OAuth2SuccessHandler.encode("소셜 로그인에 실패했습니다 — 다시 시도해 주세요"));
    }
}
