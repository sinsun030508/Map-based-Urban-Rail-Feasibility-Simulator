package com.railfeas.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 인증이 없거나 토큰을 못 쓸 때 — <b>401</b>.
 *
 * <p>이게 없으면 Spring Security 가 기본값 {@code Http403ForbiddenEntryPoint} 를 써서
 * <b>인증 실패까지 403</b> 으로 내보낸다. 화면(`api/client.js`)은 <b>401 만 세션 만료로
 * 본다</b> — 403 까지 묶으면 관리자 권한 부족 같은 다른 오류가 전부 "로그인이 필요합니다"로
 * 보여 원인을 못 찾기 때문이다. 그 판단은 옳고, 401 을 주지 않던 쪽이 틀렸다.
 *
 * <p><b>안 고쳤을 때 실제로 이렇게 보였다.</b> 토큰이 만료된 채로 들어오면 머리글에는
 * 여전히 로그인된 것처럼 보이고, 시나리오 목록이 <b>"저장된 시나리오가 없습니다"</b> 로
 * 뜬다. 세션이 끊겼다는 말이 아니라 "당신은 저장한 것이 없다"는 거짓말이라, 저장해 둔
 * 노선이 있는 사람은 지워진 줄 안다.
 *
 * <p>본문은 이 프로젝트의 {@code {message}} 규격으로 낸다 ({@code ApiExceptionHandler} 와
 * 같은 모양). Spring 기본 응답은 {@code {timestamp,status,error,path}} 라 message 가 없고,
 * 그러면 화면이 "요청 실패 (403)" 같은 밋밋한 문구로 떨어진다.
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException e) throws IOException {
        AuthErrorWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                "로그인이 만료되었거나 로그인하지 않았습니다. 다시 로그인해 주세요");
    }
}
