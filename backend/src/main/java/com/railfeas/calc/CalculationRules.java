package com.railfeas.calc;

import java.time.LocalDateTime;

/**
 * B/C 를 내는 **계산 규칙**이 마지막으로 바뀐 시각.
 *
 * 저장된 결과가 낡았는지는 기준값(DB 의 원단위·수단 표)이 바뀐 시각과 견줘 알린다
 * (`/api/reference/updated-at`). 그런데 **규칙은 DB 에 없어** 코드를 바꿔도 그 시각이
 * 움직이지 않는다 — 편익의 전환 전 수단에 기존 KTX 를 넣은 뒤에도, 전에 저장한
 * 서울~부산이 아무 표시 없이 B/C 3.86 을 보여 줬다. 근거가 바뀐 숫자를 그대로 인용하게
 * 되는 것이 이 프로젝트가 가장 피하려는 일이다.
 *
 * <b>편익·비용·추천을 바꾸는 코드를 고치면 이 시각을 올린다.</b> <b>고친 지금 시각</b>을 적는다 —
 * 미래로 적으면 그때까지 새로 계산한 결과에도 알림이 뜬다(테스트가 막는다). 서버 시각(KST)이다 —
 * `scenario.calculated_at` 과 같은 기준이다. 화면 문구만 바꾼 것은 올리지 않는다.
 */
public final class CalculationRules {

    /**
     * 역이 드문 기존 수단(KTX)은 통행이 노선 전체를 덮을 때만 전환 전 수단으로 쓰기로 한 시각.
     * 그 전 — 10-23 00:10 1인당 절감을 평균 통행거리(13.6km)까지만, 10-22 23:50 도시철도
     * 탑승 시간을 반올림하지 않기로, 23:39 기존 수단을 전환 전 수단에 넣고 B/C 0 은 추천하지
     * 않기로 했다
     */
    public static final LocalDateTime CHANGED_AT = LocalDateTime.of(2026, 10, 23, 0, 19);

    private CalculationRules() {
    }
}
