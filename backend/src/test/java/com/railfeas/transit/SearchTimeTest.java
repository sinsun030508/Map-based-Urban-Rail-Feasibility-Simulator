package com.railfeas.transit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class SearchTimeTest {

    // 2026-11-27 은 금요일
    @Test
    void 평일_아침_여덟시_전이면_그날이다() {
        assertThat(SearchTime.next(LocalDateTime.of(2026, 11, 27, 7, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 27, 8, 0));
    }

    @Test
    void 여덟시가_되면_다음_평일이다_주말은_건너뛴다() {
        assertThat(SearchTime.next(LocalDateTime.of(2026, 11, 27, 8, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 30, 8, 0));
        assertThat(SearchTime.next(LocalDateTime.of(2026, 11, 28, 23, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 30, 8, 0));
    }
}
