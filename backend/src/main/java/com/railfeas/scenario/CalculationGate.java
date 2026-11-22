package com.railfeas.scenario;

import com.railfeas.common.ApiException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * **계산의 동시 실행 개수를 제한한다**(11-22 최악 입력 시험, 한도는 판단값).
 *
 * <p>상한 근처 노선(서울 지그재그 981km, 점 50개)은 한 건에 19초이고 CPU 를 다 쓴다. 로그인한 사용자 한 명이 이런 요청 30개를 동시에 보내자 10개가 500
 * (쓰기 단계가 DB 연결 풀 10개를 점유 → `Connection is not available, request timed out after 30000ms`), 개별 계산이 97초로 늘고,
 * **다른 사용자의 목록 조회가 최대 51초** 막혔다(기준선 47ms). 가입은 누구나 하므로 막을 곳은 계산이다.
 *
 * <p>**사용자당**({@code max-per-user}, 기본 2 — 더블클릭·두 탭은 그대로 된다)과 **전체**({@code max-concurrent}, 0 이면 CPU 코어의 절반, 최소 2) 둘을 센다.
 * 한 사용자가 전체를 독점하지 못하고, 사용자가 많아도 서버가 지켜진다. **넘으면 기다리게 하지 않고 바로 429** 다 — 줄을 세우면 스레드를 잡고 있다가 같은 문제가
 * 시간만 늦춰 나타난다. 허가는 {@link Permit#close()} 로 돌려주며 **두 번 닫아도 한 번만** 돌려준다(예외가 나도 `try-with-resources` 로 반드시 반납해야 한다 — 새면
 * 서버가 점점 계산을 거부한다).
 *
 * <p>한 서버 안의 개수만 센다(Redis 로 나누지 않는다) — 서버가 여럿이 되면 서버마다 한도가 따로다.
 */
@Component
public class CalculationGate {

    private final Semaphore global;
    private final int perUser;
    private final ConcurrentHashMap<Long, Integer> inFlight = new ConcurrentHashMap<>();

    public CalculationGate(@Value("${railfeas.calc.max-concurrent:0}") int maxConcurrent,
                           @Value("${railfeas.calc.max-per-user:2}") int maxPerUser) {
        int total = maxConcurrent > 0 ? maxConcurrent : Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        this.global = new Semaphore(total);
        this.perUser = Math.max(1, maxPerUser);
    }

    /** 계산에 들어간다. 한도를 넘으면 기다리지 않고 429 를 던진다. 돌려받은 허가는 반드시 닫는다 */
    public Permit enter(Long userId) {
        // 사용자별 개수를 원자적으로 올린다 — 한도면 올리지 않고 거절
        boolean[] ok = {false};
        inFlight.compute(userId, (id, n) -> {
            int now = n == null ? 0 : n;
            if (now < perUser) {
                ok[0] = true;
                return now + 1;
            }
            return now;
        });
        if (!ok[0]) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "이미 계산이 진행 중입니다 — 끝난 뒤에 다시 눌러 주세요");
        }
        if (!global.tryAcquire()) {
            release(userId);        // 거절이 개수를 새게 하지 않는다
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "지금 계산이 몰려 있습니다 — 잠시 뒤에 다시 눌러 주세요");
        }
        return new Permit(userId);
    }

    private void release(Long userId) {
        inFlight.computeIfPresent(userId, (id, n) -> n <= 1 ? null : n - 1);       // 0 이 되면 항목을 지워 맵이 자라지 않게 한다
    }

    /** 계산 한 건의 허가. 닫으면 돌려준다 — 두 번 닫아도 한 번만 */
    public final class Permit implements AutoCloseable {
        private final Long userId;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(Long userId) {
            this.userId = userId;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                global.release();
                release(userId);
            }
        }
    }
}
