package com.railfeas.transit;

import java.time.LocalDate;

/** 하루 호출 상한. 셀 수 없으면 예외를 던진다 — 받는 쪽({@link TransitService})은 그때 **부르지 않는다**. */
public interface DailyQuota {

    /** 그날 한 번을 센다. 상한 안이면 true */
    boolean tryAcquire(LocalDate day, int limit);
}
