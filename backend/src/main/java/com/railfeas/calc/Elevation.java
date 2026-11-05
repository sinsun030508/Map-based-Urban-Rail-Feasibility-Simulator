package com.railfeas.calc;

import java.util.OptionalDouble;

/** 땅 높이(m). 모르면 비어 있다 — 0 으로 채우면 「평지」 로 읽힌다 */
@FunctionalInterface
public interface Elevation {

    OptionalDouble at(double lat, double lng);
}
