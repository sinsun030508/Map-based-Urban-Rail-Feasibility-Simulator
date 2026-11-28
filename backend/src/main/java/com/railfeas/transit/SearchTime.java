package com.railfeas.transit;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 운행 여부를 따지는 시각 — **지금 뒤의 첫 평일(월~금) 08:00**(설계 판단). 밤에 조회하면 막차·심야버스가 섞이고,
 * 이 프로젝트는 평일 첨두를 본다. 공휴일은 가리지 않는다.
 */
final class SearchTime {

    private static final LocalTime MORNING = LocalTime.of(8, 0);

    private SearchTime() {
    }

    static LocalDateTime next(LocalDateTime now) {
        LocalDateTime at = now.toLocalDate().atTime(MORNING);
        while (!at.isAfter(now) || at.getDayOfWeek() == DayOfWeek.SATURDAY || at.getDayOfWeek() == DayOfWeek.SUNDAY) {
            at = at.plusDays(1);
        }
        return at;
    }
}
