package com.weeklyroster.repository;

import com.weeklyroster.entity.RosterCycle;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RosterCycleRepository extends JpaRepository<RosterCycle, Long> {
    Optional<RosterCycle> findByStartDateAndEndDate(LocalDate startDate, LocalDate endDate);
    List<RosterCycle> findAllByOrderByStartDateDesc();

    @Query("SELECT c FROM RosterCycle c WHERE c.startDate <= :endDate AND c.endDate >= :startDate")
    List<RosterCycle> findOverlappingCycles(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM roster_cycles WHERE id = :cycleId", nativeQuery = true)
    void deleteCycleByIdNative(@Param("cycleId") Long cycleId);
}
