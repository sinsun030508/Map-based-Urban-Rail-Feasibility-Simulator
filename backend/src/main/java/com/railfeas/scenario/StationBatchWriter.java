package com.railfeas.scenario;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collection;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

/**
 * **정차역을 JDBC 배치로 넣는다**(11-22 쓰기 단계 측정).
 *
 * <p>최악 노선(점 50개 981km)의 계산 한 건이 19초였고 그중 **쓰기 트랜잭션이 12.9초**였다(역 7,475행) — 운영과 같은 compose 네트워크 안에서도 9.6초. 키가 `IDENTITY`(AUTO_INCREMENT)라
 * 하이버네이트가 INSERT 배치를 끄고 행마다 DB 와 왕복하기 때문이다(행당 약 1.3ms). 서울~부산(역 1,133행)도 2.6초 중 대부분이 저장이다. 그래서 **결과(11행)는 JPA 로 저장해 키를 얻고
 * 역은 `PreparedStatement` 배치로** 넣는다(`rewriteBatchedStatements=true` — 커넥터가 한 문장으로 합친다).
 *
 * <p><b>같은 연결·같은 트랜잭션이다</b>(`Session.doWork`) — 롤백도 함께 된다. 호출 전에 결과가 **flush 돼 id 가 있어야** 한다(없으면 거부 — 부모 없는 고아 행이 생긴다).
 * 역 컬렉션은 JPA 가 저장하지 않는다(`ScenarioResult.stations` 에 cascade 가 없다) — 삭제는 DB 의 `ON DELETE CASCADE` 가 한다.
 */
@Component
public class StationBatchWriter {

    static final int BATCH = 1_000;
    static final String SQL = "INSERT INTO station (result_id, sequence, name, latitude, longitude, estimated_daily_users, is_recommended) VALUES (?,?,?,?,?,?,?)";

    private final EntityManager entityManager;

    public StationBatchWriter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** 결과들의 역을 지금 트랜잭션의 연결로 넣는다. 결과는 이미 flush 돼 id 가 있어야 한다 */
    public void save(Collection<ScenarioResult> results) {
        entityManager.unwrap(Session.class).doWork(connection -> insert(connection, results));
    }

    static void insert(Connection connection, Collection<ScenarioResult> results) throws SQLException {
        int stations = 0;
        for (ScenarioResult result : results) {
            if (!result.getStations().isEmpty() && result.getId() == null) {
                throw new IllegalStateException("결과가 아직 저장되지 않았다 — 역을 넣기 전에 결과를 flush 해 id 를 얻어야 한다");
            }
            stations += result.getStations().size();
        }
        if (stations == 0) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(SQL)) {
            int pending = 0;
            for (ScenarioResult result : results) {
                for (Station s : result.getStations()) {
                    ps.setLong(1, result.getId());
                    ps.setInt(2, s.getSequence());
                    if (s.getName() == null) {
                        ps.setNull(3, Types.VARCHAR);
                    } else {
                        ps.setString(3, s.getName());
                    }
                    ps.setBigDecimal(4, s.getLatitude());
                    ps.setBigDecimal(5, s.getLongitude());
                    if (s.getEstimatedDailyUsers() == null) {
                        ps.setNull(6, Types.INTEGER);
                    } else {
                        ps.setInt(6, s.getEstimatedDailyUsers());
                    }
                    ps.setBoolean(7, s.getRecommended());
                    ps.addBatch();
                    if (++pending == BATCH) {
                        ps.executeBatch();
                        pending = 0;
                    }
                }
            }
            if (pending > 0) {
                ps.executeBatch();
            }
        }
    }
}
