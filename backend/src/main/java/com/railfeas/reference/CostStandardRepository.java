package com.railfeas.reference;

import com.railfeas.common.ModeType;
import com.railfeas.common.StructureType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CostStandardRepository extends JpaRepository<CostStandard, Long> {

    Optional<CostStandard> findByModeTypeAndStructureTypeAndActiveTrue(
            ModeType modeType, StructureType structureType);

    List<CostStandard> findByActiveTrue();
}
