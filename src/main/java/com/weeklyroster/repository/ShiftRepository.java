package com.weeklyroster.repository;

import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShiftRepository extends JpaRepository<Shift, Long> {
    Optional<Shift> findByShiftType(ShiftType shiftType);
    List<Shift> findByActiveTrueOrderByIdAsc();
}
