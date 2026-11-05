package com.railfeas.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * 소셜 로그인 실패(사용자가 동의를 거절했거나 공급자가 오류를 냈을 때) → 화면의 같은 주소로 `#error=`.
 * 걸지 않으면 Spring 기본값이 백엔드의 `/login?error` 로 보내 **화면이 아닌 곳**에 떨어진다
 */
@Component
public class OAuth2FailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2FailureHandler.class);

    private final String redirectUri;

    public OAuth2FailureHandler(@Value("${app.oauth2.redirect-uri}") String redirectUri) {
        this.redirectUri = redirectUri;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        // **로그에 남긴다.** 전에는 실패가 아무 기록 없이 화면으로만 가서, 카카오 로그인이 왜 실패했는지
        // (토큰 교환·동의·시크릿) 로그로 알 수 없었다(11-05). 공급자의 오류 코드와 설명만 — 토큰은 없다
        if (exception instanceof OAuth2AuthenticationException oe) {
            log.warn("소셜 로그인 실패 — {}: {}", oe.getError().getErrorCode(), oe.getError().getDescription());
        } else {
            log.warn("소셜 로그인 실패 — {}", exception.getMessage());
        }
        // 우리가 낸 이유(이메일 겹침)만 그대로 전한다 — 공급자·라이브러리의 내부 문구는 사용자에게 뜻이 없다
        String message = exception instanceof OAuth2AuthenticationException e
                && OAuth2UserService.EMAIL_IN_USE.equals(e.getError().getErrorCode())
                ? e.getError().getDescription()
                : "소셜 로그인에 실패했습니다 — 다시 시도해 주세요";
        getRedirectStrategy().sendRedirect(request, response,
                redirectUri + "#error=" + OAuth2SuccessHandler.encode(message));
    }
}
