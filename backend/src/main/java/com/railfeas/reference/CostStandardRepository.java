package com.railfeas.reference;

import com.railfeas.common.ModeType;
import com.railfeas.common.StructureType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CostStandardRepository extends JpaRepository<CostStandard, Long> {

    Optional<CostStandard> findByModeTypeAndStructureTypeAndActiveTrue(
            ModeType modeType, StructureType structureType);

    List<CostStandard> findByActiveTrue();

    /** 활성일 때만 내린다. 내린 행 수를 돌려준다 — 0 이면 다른 관리자가 먼저 고쳤다 */
    @Modifying
    @Query("update CostStandard s set s.active = false where s.id = :id and s.active = true")
    int deactivate(@Param("id") Long id);
}
