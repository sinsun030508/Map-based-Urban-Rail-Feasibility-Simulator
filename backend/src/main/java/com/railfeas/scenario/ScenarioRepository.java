package com.railfeas.scenario;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScenarioRepository extends JpaRepository<Scenario, Long> {

    List<Scenario> findByUserIdOrderByCreatedAtDesc(Long userId);

    @EntityGraph(attributePaths = {"points", "results"})
    Optional<Scenario> findWithDetailsById(Long id);
}
