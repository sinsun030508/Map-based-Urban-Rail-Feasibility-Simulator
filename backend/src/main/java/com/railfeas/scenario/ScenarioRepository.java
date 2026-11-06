package com.railfeas.scenario;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
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
