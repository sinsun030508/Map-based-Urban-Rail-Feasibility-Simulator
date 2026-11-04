package com.railfeas.common;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handle(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("message", e.getMessage()));
    }

    /** 본문이 깨졌거나 JSON 이 아닐 때. 잡지 않으면 /error 로 넘어가 403 처럼 보인다 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handle(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("message", "요청 본문을 읽을 수 없습니다 (UTF-8 JSON 인지 확인하세요)"));
    }

    /**
     * 화면에 그대로 뜨는 문구라 **필드 이름을 우리말로 바꿔** 내보낸다.
     * 그냥 두면 회원가입에서 "password: 크기가 8에서 64 사이여야 합니다" 처럼
     * 내부 DTO 필드명이 사용자에게 노출된다.
     */
    private static final Map<String, String> FIELD_NAMES = Map.of(
            "email", "이메일", "password", "비밀번호", "nickname", "닉네임",
            "title", "제목", "source", "근거", "value", "값", "points", "지점");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handle(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> message(f.getField(), f.getDefaultMessage()))
                .orElse("잘못된 요청입니다");
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    /** 지점 목록 안의 좌표 — `points[1].latitude` */
    private static final Pattern POINT_COORD = Pattern.compile("points\\[(\\d+)]\\.(latitude|longitude)");

    /**
     * 화면에 띄울 한 줄. **목록 안의 좌표는 몇 번째 지점인지로 말한다** — 필드 이름 표는 `points` 만
     * 알아서, 바다를 잘못 누르면 「points[1].latitude — 다음 값 이상이어야 합니다 33.0」 이 떴다.
     * 범위는 `ScenarioDto.PointRequest` 의 검증과 같다
     */
    static String message(String field, String defaultMessage) {
        Matcher m = POINT_COORD.matcher(field);
        if (m.matches()) {
            return (Integer.parseInt(m.group(1)) + 1) + "번째 지점이 한반도 밖입니다 (위도 33~39°, 경도 124~132°)";
        }
        return FIELD_NAMES.getOrDefault(field, field) + " — " + defaultMessage;
    }
}
