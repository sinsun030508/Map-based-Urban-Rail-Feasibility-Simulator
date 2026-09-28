package com.railfeas.reference;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BenefitParameterRepository extends JpaRepository<BenefitParameter, Long> {
    Optional<BenefitParameter> findByParamName(String paramName);
}
