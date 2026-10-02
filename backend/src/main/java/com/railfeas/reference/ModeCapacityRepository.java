package com.railfeas.reference;

import com.railfeas.common.ModeType;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ModeCapacityRepository extends JpaRepository<ModeCapacity, ModeType> {

    @Query("select max(m.updatedAt) from ModeCapacity m")
    LocalDateTime lastUpdatedAt();
}
