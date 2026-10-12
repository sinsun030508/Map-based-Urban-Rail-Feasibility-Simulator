package com.railfeas.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

/**
 * 인증 실패와 권한 부족을 **다른 상태 코드로** 내보내는지 본다.
 *
 * <p>왜 중요한가. 화면(`api/client.js`)은 <b>401 만 세션 만료로 본다</b> — 403 까지 묶으면
 * 관리자 권한 부족 같은 다른 오류가 전부 "로그인이 필요합니다"로 보여 원인을 못 찾기
 * 때문이고, 그 판단은 옳다. 그런데 Spring Security 는 `authenticationEntryPoint` 를
 * 안 걸어 두면 기본값 {@code Http403ForbiddenEntryPoint} 를 써서 <b>인증 실패까지 403</b>
 * 으로 내보낸다.
 *
 * <p><b>그래서 실제로 이렇게 보였다.</b> 토큰이 만료된 사용자가 들어오면 머리글에는 여전히
 * 로그인된 것처럼 보이고, 시나리오 목록은 <b>"저장된 시나리오가 없습니다"</b> 라고 적힌다.
 * 세션이 끊겼다는 말이 아니라 <b>"당신은 저장한 것이 없다"</b> 는 거짓말이라, 저장해 둔
 * 노선이 있는 사람은 지워진 줄 안다. 그럴듯한데 틀린 답이 가장 나쁘다.
 */
class AuthErrorTest {

    private final JwtAuthenticationEntryPoint entryPoint = new JwtAuthenticationEntryPoint();
    private final JwtAccessDeniedHandler deniedHandler = new JwtAccessDeniedHandler();

    @Test
    @DisplayName("인증이 없으면 401 이다 — 화면이 세션 만료로 알아볼 수 있어야 한다")
    void unauthenticatedIs401() throws Exception {
        var res = new MockHttpServletResponse();
        entryPoint.commence(new MockHttpServletRequest(), res,
                new InsufficientAuthenticationException("토큰 없음"));

        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    @DisplayName("권한이 모자라면 403 이다 — 관리자 전용에 일반 사용자가 올 때")
    void forbiddenStaysForbidden() throws Exception {
        var res = new MockHttpServletResponse();
        deniedHandler.handle(new MockHttpServletRequest(), res,
                new AccessDeniedException("권한 없음"));

        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    @DisplayName("둘 다 {message} 로 내보낸다 — 화면이 서버 문구를 그대로 띄운다")
    void bothUseTheProjectErrorShape() throws Exception {
        // 기본값은 Spring 의 {timestamp,status,error,path} 라 message 가 없고,
        // 그러면 화면이 "요청 실패 (403)" 같은 밋밋한 문구로 떨어진다
        var a = new MockHttpServletResponse();
        entryPoint.commence(new MockHttpServletRequest(), a,
                new InsufficientAuthenticationException("x"));
        assertThat(a.getContentAsString()).contains("\"message\"");
        assertThat(a.getContentType()).contains("application/json");

        var b = new MockHttpServletResponse();
        deniedHandler.handle(new MockHttpServletRequest(), b, new AccessDeniedException("x"));
        assertThat(b.getContentAsString()).contains("\"message\"");
        assertThat(b.getContentType()).contains("application/json");
    }

    @Test
    @DisplayName("한글 문구가 깨지지 않는다")
    void koreanSurvives() throws Exception {
        var res = new MockHttpServletResponse();
        res.setCharacterEncoding("UTF-8");
        entryPoint.commence(new MockHttpServletRequest(), res,
                new InsufficientAuthenticationException("x"));

        // 깨지면 화면에 ???? 가 뜬다 — 이 프로젝트가 initdb 에서 이미 겪은 일이다
        assertThat(res.getContentAsString()).contains("로그인");
        assertThat(res.getContentType().toLowerCase()).contains("utf-8");
    }

    @Test
    @DisplayName("두 문구가 서로 다르다 — 같으면 가려낼 수 없다")
    void messagesAreDistinct() throws Exception {
        var a = new MockHttpServletResponse();
        entryPoint.commence(new MockHttpServletRequest(), a,
                new InsufficientAuthenticationException("x"));
        var b = new MockHttpServletResponse();
        deniedHandler.handle(new MockHttpServletRequest(), b, new AccessDeniedException("x"));

        assertThat(a.getContentAsString()).isNotEqualTo(b.getContentAsString());
    }
}
