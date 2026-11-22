package com.railfeas.scenario;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScenarioRepository extends JpaRepository<Scenario, Long> {

    List<Scenario> findByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserId(Long userId);

    /**
     * points 만 즉시 로딩한다. results 까지 함께 넣으면 Hibernate 가
     * MultipleBagFetchException 을 던진다 (List 두 개를 한 번에 조인할 수 없다).
     * results 는 트랜잭션 안에서 지연 로딩된다.
     */
    @EntityGraph(attributePaths = "points")
    Optional<Scenario> findWithDetailsById(Long id);

    /**
     * **내 시나리오** 행을 `SELECT … FOR UPDATE` 로 잠가 읽는다 — 같은 시나리오를 바꾸는 쓰기(계산·수정·삭제)를 줄 세운다(11-22 동시 시험).
     * 안 잠그면 같은 시나리오를 동시에 계산하면 MySQL 교착(Deadlock found…)으로 8개 중 7개가, 동시에 수정하면 `OptimisticLockException`(이미 지워진 점을 또 지움)으로 4개 중 3개가 500 이었다.
     * 트랜잭션의 **첫 문장**이어야 한다 — 잠근 뒤에 읽어야 앞 요청이 커밋한 결과를 본다(REPEATABLE READ 스냅샷은 첫 일반 읽기에서 잡힌다).
     * 소유자 조건까지 걸어 **남의 시나리오는 잠그지도 않고 비어 돌아온다**(호출한 쪽이 404).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Scenario s where s.id = :id and s.user.id = :userId")
    Optional<Scenario> lockOwned(@Param("id") Long id, @Param("userId") Long userId);

    /** {회원 id, 시나리오 수} */
    @Query("select s.user.id, count(s) from Scenario s group by s.user.id")
    List<Object[]> countByUser();

    /** 결과가 있는 시나리오 수 — 추천 수단은 비어 있을 수 있어(전부 B/C 0) 결과로 센다 */
    @Query("select count(distinct r.scenario.id) from ScenarioResult r")
    long countCalculated();

    /** {추천 수단, 수} */
    @Query("select s.recommendedMode, count(s) from Scenario s where s.recommendedMode is not null "
            + "group by s.recommendedMode order by count(s) desc")
    List<Object[]> countByRecommendedMode();

    @Query("select s.createdAt from Scenario s where s.createdAt >= :from")
    List<LocalDateTime> createdSince(@Param("from") LocalDateTime from);
}
