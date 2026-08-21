package com.weeklyroster.repository;

import com.weeklyroster.entity.RosterOverride;
import com.weeklyroster.entity.RosterCycle;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RosterOverrideRepository extends JpaRepository<RosterOverride, Long> {
    java.util.List<RosterOverride> findByAssignmentIdOrderByCreatedAtDesc(Long assignmentId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM RosterOverride o WHERE o.assignment.id IN (SELECT a.id FROM RosterAssignment a WHERE a.cycle = :cycle)")
    void deleteByAssignmentCycle(@Param("cycle") RosterCycle cycle);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM RosterOverride o WHERE o.assignment.id IN (SELECT a.id FROM RosterAssignment a WHERE a.rosterDate BETWEEN :startDate AND :endDate)")
    void deleteByAssignmentRosterDateBetween(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE ro FROM roster_overrides ro JOIN roster_assignments ra ON ro.assignment_id = ra.id WHERE ra.cycle_id = :cycleId", nativeQuery = true)
    void deleteByCycleIdNative(@Param("cycleId") Long cycleId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE ro FROM roster_overrides ro JOIN roster_assignments ra ON ro.assignment_id = ra.id WHERE ra.roster_date BETWEEN :startDate AND :endDate", nativeQuery = true)
    void deleteByDateRangeNative(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
}
