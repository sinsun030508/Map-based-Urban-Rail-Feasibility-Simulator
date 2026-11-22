package com.railfeas.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * **정차역은 JDBC 배치로 넣는다**(11-22 쓰기 단계 측정). 최악 노선(역 7,475행)의 쓰기 트랜잭션이 12.9초였다 — 키가 `IDENTITY`(AUTO_INCREMENT)라 하이버네이트가 INSERT 배치를 꺼서
 * 행마다 DB 와 왕복한다(행당 약 1.3~1.7ms, 운영과 같은 compose 네트워크 안에서도 9.6초). 결과(11행)는 JPA 로 저장해 키를 얻고, 역은 `PreparedStatement` 배치로 한꺼번에 넣는다.
 * 여기서는 **무엇을 어떤 값으로 몇 번에 나눠 보내는가**를 고정한다 — 실제 저장 결과는 정답지 대조(33개 노선 응답 JSON)로 본다.
 */
class StationBatchWriterTest {

    private static ScenarioResult result(Long id, int stations) {
        ScenarioResult r = Mockito.mock(ScenarioResult.class);
        Mockito.when(r.getId()).thenReturn(id);
        List<Station> list = new ArrayList<>();
        for (int i = 0; i < stations; i++) {
            list.add(Station.builder().sequence(i).name("역" + i).latitude(BigDecimal.valueOf(37.5 + i * 0.001)).longitude(BigDecimal.valueOf(127.0 + i * 0.001))
                    .estimatedDailyUsers(100 + i).recommended(true).build());
        }
        Mockito.when(r.getStations()).thenReturn(list);
        return r;
    }

    private final Connection conn = Mockito.mock(Connection.class);
    private final PreparedStatement ps = Mockito.mock(PreparedStatement.class);

    private void stubPrepare() throws SQLException {
        Mockito.when(conn.prepareStatement(StationBatchWriter.SQL)).thenReturn(ps);
    }

    @Test
    @DisplayName("모든 결과의 모든 역을 올바른 result_id 와 값으로 넣는다")
    void insertsEveryStationWithItsResultId() throws SQLException {
        stubPrepare();

        StationBatchWriter.insert(conn, List.of(result(11L, 2), result(22L, 1)));

        InOrder order = Mockito.inOrder(ps);
        // 결과 11 의 첫 역
        order.verify(ps).setLong(1, 11L);
        order.verify(ps).setInt(2, 0);
        order.verify(ps).setString(3, "역0");
        order.verify(ps).setBigDecimal(4, BigDecimal.valueOf(37.5));
        order.verify(ps).setBigDecimal(5, BigDecimal.valueOf(127.0));
        order.verify(ps).setInt(6, 100);
        order.verify(ps).setBoolean(7, true);
        order.verify(ps).addBatch();
        Mockito.verify(ps, Mockito.times(3)).addBatch();
        Mockito.verify(ps).setLong(1, 22L);
        Mockito.verify(ps, Mockito.times(1)).executeBatch();       // 3행 — 한 번에
        Mockito.verify(ps).close();
    }

    @Test
    @DisplayName("1,000행 단위로 나눠 보낸다 — 2,500행이면 executeBatch 3번(1000·1000·500)")
    void splitsIntoChunks() throws SQLException {
        stubPrepare();

        StationBatchWriter.insert(conn, List.of(result(1L, 2_500)));

        assertThat(StationBatchWriter.BATCH).isEqualTo(1_000);
        Mockito.verify(ps, Mockito.times(2_500)).addBatch();
        Mockito.verify(ps, Mockito.times(3)).executeBatch();
    }

    @Test
    @DisplayName("이름·예상 이용객이 없으면 setNull 이다 — 0 이나 빈 문자열로 채우지 않는다")
    void nullsAreBoundAsNull() throws SQLException {
        stubPrepare();
        ScenarioResult r = Mockito.mock(ScenarioResult.class);
        Mockito.when(r.getId()).thenReturn(5L);
        Mockito.when(r.getStations()).thenReturn(List.of(Station.builder().sequence(0).latitude(BigDecimal.ONE).longitude(BigDecimal.TEN).recommended(true).build()));

        StationBatchWriter.insert(conn, List.of(r));

        Mockito.verify(ps).setNull(3, Types.VARCHAR);
        Mockito.verify(ps).setNull(6, Types.INTEGER);
        Mockito.verify(ps, Mockito.never()).setString(Mockito.eq(3), Mockito.anyString());
        Mockito.verify(ps, Mockito.never()).setInt(Mockito.eq(6), Mockito.anyInt());
    }

    @Test
    @DisplayName("저장할 역이 없으면 문장을 만들지도 않는다")
    void nothingToInsert() throws SQLException {
        StationBatchWriter.insert(conn, List.of(result(1L, 0)));
        StationBatchWriter.insert(conn, List.of());

        Mockito.verifyNoInteractions(conn);
    }

    @Test
    @DisplayName("아직 저장되지 않은 결과(id 없음)의 역은 거부한다 — 결과를 flush 하기 전에 부르면 고아 행이 생긴다")
    void rejectsUnsavedResult() {
        assertThatThrownBy(() -> StationBatchWriter.insert(conn, List.of(result(null, 3))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flush");
        Mockito.verifyNoInteractions(conn);
    }

    @Test
    @DisplayName("실패해도 문장을 닫는다")
    void closesStatementOnFailure() throws SQLException {
        stubPrepare();
        Mockito.when(ps.executeBatch()).thenThrow(new SQLException("1062"));

        assertThatThrownBy(() -> StationBatchWriter.insert(conn, List.of(result(1L, 2)))).isInstanceOf(SQLException.class);

        Mockito.verify(ps).close();
    }

    @Test
    @DisplayName("SQL 은 station 테이블의 컬럼 일곱 개를 이름으로 적는다 — 순서가 바뀌어도 값이 어긋나지 않게")
    void sqlNamesItsColumns() {
        assertThat(StationBatchWriter.SQL).isEqualTo("INSERT INTO station (result_id, sequence, name, latitude, longitude, estimated_daily_users, is_recommended) VALUES (?,?,?,?,?,?,?)");
    }
}
