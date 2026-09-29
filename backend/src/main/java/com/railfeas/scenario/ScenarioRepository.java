package com.railfeas.scenario;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScenarioRepository extends JpaRepository<Scenario, Long> {

    List<Scenario> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * points 만 즉시 로딩한다. results 까지 함께 넣으면 Hibernate 가
     * MultipleBagFetchException 을 던진다 (List 두 개를 한 번에 조인할 수 없다).
     * results 는 트랜잭션 안에서 지연 로딩된다.
     */
    @EntityGraph(attributePaths = "points")
    Optional<Scenario> findWithDetailsById(Long id);
}
