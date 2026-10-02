package com.railfeas.reference;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BenefitParameterRepository extends JpaRepository<BenefitParameter, Long> {
    Optional<BenefitParameter> findByParamName(String paramName);

    @Query("select max(p.updatedAt) from BenefitParameter p")
    LocalDateTime lastUpdatedAt();
}
