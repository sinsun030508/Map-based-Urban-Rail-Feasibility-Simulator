package com.railfeas.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 로그인은 됐는데 권한이 모자랄 때 — <b>403</b> (관리자 전용에 일반 사용자가 온 경우).
 *
 * <p>401 과 <b>반드시 달라야 한다.</b> 둘을 같은 코드로 내보내면 화면이
 * "다시 로그인하면 되는 일"과 "로그인해도 안 되는 일"을 가려낼 수 없다.
 * 로그아웃시켜 봐야 소용없는 사용자를 로그인 화면으로 쫓아내게 된다.
 */
@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException e) throws IOException {
        AuthErrorWriter.write(response, HttpServletResponse.SC_FORBIDDEN,
                "이 작업을 할 권한이 없습니다");
    }
}
