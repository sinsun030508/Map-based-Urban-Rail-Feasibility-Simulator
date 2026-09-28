package com.railfeas.common;

import org.springframework.http.HttpStatus;

/** 컨트롤러까지 올라가면 상태코드와 메시지로 그대로 응답된다. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
