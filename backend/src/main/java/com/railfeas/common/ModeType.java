package com.railfeas.common;

/**
 * 수단 유형.
 *
 * <p><b>{@code EXPRESS_RAIL} 은 자료의 분류가 아니라 사용자에게 주는 선택지다.</b>
 * 원본 자료의 표기는 급행이든 아니든 모두 {@code 복선전철} 이라 {@code type_mapping} 은
 * 전부 {@code DOUBLE_ELEC} 로 보낸다 — 그래서 {@code reference_line.mode_type} ENUM
 * (V1 스키마)에는 {@code EXPRESS_RAIL} 이 없다. 거기 넣으면 비용 회귀의 수단 더미가
 * 갈라져, "비용은 가를 근거가 없다"(잔차 t=+0.12, 검출 한계 1.50배)는 판단과 어긋난다.
 *
 * <p>복선전철을 둘로 가른 이유는 역간격 분포가 쌍봉이어서다 — 급행 6.92km / 일반 2.62km
 * 인데 중앙값 4.30km 는 두 집단 사이 빈 틈이었다. 자세한 것은
 * {@code db/seed/S1__mode_capacity.sql} 과 {@code etl/mode_speed_check.py}.
 */
public enum ModeType {
    HEAVY_METRO, LIGHT_RAIL, TRAM, BRT_HIGH, BRT_LOW,
    DOUBLE_ELEC, EXPRESS_RAIL, SINGLE_ELEC, UPGRADE
}
