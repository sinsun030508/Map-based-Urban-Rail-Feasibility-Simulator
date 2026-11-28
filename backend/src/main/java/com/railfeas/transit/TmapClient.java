package com.railfeas.transit;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** TMAP 대중교통 API 한 번. 상태·본문을 그대로 주고, 통신이 안 되면 예외다. */
public interface TmapClient {

    record Reply(int status, String body) {
    }

    Reply search(BigDecimal fromLat, BigDecimal fromLng, BigDecimal toLat, BigDecimal toLng, LocalDateTime at)
            throws IOException, InterruptedException;
}
