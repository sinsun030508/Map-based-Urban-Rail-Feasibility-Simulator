package com.railfeas.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railfeas.common.ApiException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * **계산의 동시 실행 개수를 제한한다**(11-22 최악 입력 시험). 상한 근처 노선(서울 지그재그 981km)은 한 건에 19초이고 CPU 를 다 쓴다. 로그인한 사용자 한 명이 30개를 동시에 보내자
 * 10개가 500(DB 연결 풀 10개를 쓰기 단계가 점유 → `Connection is not available, request timed out after 30000ms`), 개별 계산 97초, **다른 사용자의 목록 조회가 최대 51초** 막혔다.
 * 사용자당·전체 개수를 두고 **넘으면 기다리게 하지 않고 바로 429** 다 — 줄을 세우면 스레드·연결을 잡아 문제가 그대로 옮겨 간다.
 */
class CalculationGateTest {

    private static long USER = 1L, OTHER = 2L;

    private static HttpStatus statusOf(Throwable e) {
        return ((ApiException) e).getStatus();
    }

    @Test
    @DisplayName("사용자당 개수까지는 들어가고 그 위는 429 — 허가를 돌려주면 다시 들어간다")
    void perUserLimitThenRelease() {
        CalculationGate gate = new CalculationGate(10, 2);

        CalculationGate.Permit a = gate.enter(USER);
        CalculationGate.Permit b = gate.enter(USER);
        assertThatThrownBy(() -> gate.enter(USER)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS))
                .hasMessageContaining("이미");

        a.close();
        CalculationGate.Permit c = gate.enter(USER);     // 하나 반납하면 다시 들어간다
        b.close();
        c.close();
    }

    @Test
    @DisplayName("한 사용자가 한도를 다 써도 다른 사용자는 들어간다 — 사용자별로 센다")
    void perUserCountsAreIndependent() {
        CalculationGate gate = new CalculationGate(10, 1);

        CalculationGate.Permit mine = gate.enter(USER);
        assertThatThrownBy(() -> gate.enter(USER)).isInstanceOf(ApiException.class);
        CalculationGate.Permit theirs = gate.enter(OTHER);       // 막히지 않는다
        mine.close();
        theirs.close();
    }

    @Test
    @DisplayName("전체 개수를 넘으면 사용자가 달라도 429 — 서버를 지킨다. 반납하면 다시 들어간다")
    void globalLimit() {
        CalculationGate gate = new CalculationGate(2, 5);

        CalculationGate.Permit a = gate.enter(1L);
        CalculationGate.Permit b = gate.enter(2L);
        assertThatThrownBy(() -> gate.enter(3L)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS))
                .hasMessageContaining("몰려");

        a.close();
        gate.enter(3L).close();
        b.close();
    }

    @Test
    @DisplayName("전체에서 막혀도 그 사용자의 사용자별 개수는 올라가 있지 않다 — 거절이 개수를 새게 하지 않는다")
    void rejectionDoesNotLeakUserCount() {
        CalculationGate gate = new CalculationGate(1, 2);

        CalculationGate.Permit holder = gate.enter(1L);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> gate.enter(2L)).isInstanceOf(ApiException.class);   // 전체 한도로 5번 거절
        }
        holder.close();
        // 새는 게 있으면 사용자 2 의 개수가 5 로 남아 영영 못 들어간다
        gate.enter(2L).close();
        gate.enter(2L).close();
    }

    @Test
    @DisplayName("허가를 두 번 닫아도 한 번만 돌려준다 — try-with-resources 와 직접 close 가 겹쳐도 안 샌다")
    void closeIsIdempotent() {
        CalculationGate gate = new CalculationGate(1, 1);

        CalculationGate.Permit p = gate.enter(USER);
        p.close();
        p.close();
        CalculationGate.Permit again = gate.enter(USER);
        // 두 번 반납됐다면 전체 한도(1)가 2 로 불어나 두 번째가 들어간다
        assertThatThrownBy(() -> gate.enter(OTHER)).isInstanceOf(ApiException.class);
        again.close();
    }

    @Test
    @DisplayName("많이 겹쳐도 한도를 넘어 들어가지 않는다 — 동시에 던진 40개 중 한도 개수만 들어간다")
    void neverAdmitsMoreThanTheLimitUnderRace() throws Exception {
        CalculationGate gate = new CalculationGate(4, 4);
        List<CalculationGate.Permit> admitted = java.util.Collections.synchronizedList(new ArrayList<>());
        var start = new java.util.concurrent.CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            long user = i;      // 사용자마다 다르게 — 전체 한도만 본다
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    admitted.add(gate.enter(user));
                } catch (ApiException | InterruptedException ignored) {
                    // 거절
                }
            });
            threads.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }

        assertThat(admitted).hasSize(4);
        admitted.forEach(CalculationGate.Permit::close);
    }

    @Test
    @DisplayName("한도를 0 으로 두면 CPU 코어의 절반(최소 2)을 쓴다 — 설정이 없을 때의 기본")
    void autoLimitFromCpuCount() {
        int expected = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        CalculationGate gate = new CalculationGate(0, 100);

        List<CalculationGate.Permit> held = new ArrayList<>();
        for (long i = 0; i < expected; i++) {
            held.add(gate.enter(i));
        }
        assertThatThrownBy(() -> gate.enter(999L)).isInstanceOf(ApiException.class);
        held.forEach(CalculationGate.Permit::close);
    }
}
