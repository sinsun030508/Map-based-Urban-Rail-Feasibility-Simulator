package com.railfeas.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 보안 필터 단계의 오류 본문을 이 프로젝트의 {@code {message}} 규격으로 쓴다.
 *
 * <p>여기는 {@code @RestControllerAdvice}({@code ApiExceptionHandler}) 가 닿지 않는다 —
 * 컨트롤러에 들어가기 전에 끊기기 때문이다. 그래서 같은 모양을 손으로 맞춘다.
 *
 * <p><b>문자셋을 명시한다.</b> 안 하면 한글 문구가 깨져 화면에 {@code ????} 가 뜬다.
 * 이 프로젝트가 initdb(latin1)에서 이미 겪은 일이라 되풀이하지 않는다.
 */
final class AuthErrorWriter {

    private AuthErrorWriter() {
    }

    static void write(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 문구에 따옴표를 쓰지 않으므로 그대로 넣어도 JSON 이 깨지지 않는다.
        // 바깥에서 받은 값을 넣게 되면 그때는 이스케이프할 것.
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
