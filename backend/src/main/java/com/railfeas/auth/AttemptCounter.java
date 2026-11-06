package com.railfeas.auth;

import java.time.Duration;

/** 실패 횟수를 세는 곳. 실패하면 예외를 던진다 — 받는 쪽({@link LoginThrottle})이 막지 않고 넘어간다 */
public interface AttemptCounter {

    long current(String key);

    /** 처음 셀 때 {@code window} 뒤에 사라지도록 건다 */
    void increment(String key, Duration window);

    void clear(String key);
}
