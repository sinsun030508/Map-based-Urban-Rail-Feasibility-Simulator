package com.railfeas.reference;

import com.railfeas.common.ModeType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReferenceLineRepository extends JpaRepository<ReferenceLine, Long> {
    List<ReferenceLine> findByModeTypeAndOutlierFalse(ModeType modeType);
}
