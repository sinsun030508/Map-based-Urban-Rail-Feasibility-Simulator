package com.railfeas.calc;

import java.time.Duration;
import java.util.Optional;

/** 계산 결과를 문자열로 담아 두는 곳. 실패하면 예외를 던진다 — 받는 쪽({@link MountainPlans})이 계산으로 넘어간다 */
public interface PlanStore {

    Optional<String> get(String key);

    void put(String key, String value, Duration ttl);
}
