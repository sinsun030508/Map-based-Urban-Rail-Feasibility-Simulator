package com.railfeas.reference;

import com.railfeas.common.ModeType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModeCapacityRepository extends JpaRepository<ModeCapacity, ModeType> {
}
