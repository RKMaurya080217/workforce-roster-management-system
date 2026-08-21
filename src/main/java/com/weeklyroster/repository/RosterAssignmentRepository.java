package com.weeklyroster.repository;

import com.weeklyroster.entity.RosterAssignment;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.ShiftType;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RosterAssignmentRepository extends JpaRepository<RosterAssignment, Long> {

    @Query("SELECT a FROM RosterAssignment a JOIN FETCH a.employee JOIN FETCH a.shift WHERE a.cycle = :cycle ORDER BY a.rosterDate ASC, a.employee.id ASC")
    List<RosterAssignment> findByCycleOrderByRosterDateAscEmployeeIdAsc(@Param("cycle") RosterCycle cycle);

    @Query("SELECT a FROM RosterAssignment a JOIN FETCH a.employee JOIN FETCH a.shift WHERE a.rosterDate = :rosterDate")
    List<RosterAssignment> findByRosterDate(@Param("rosterDate") LocalDate rosterDate);

    @Query("SELECT a FROM RosterAssignment a JOIN FETCH a.employee JOIN FETCH a.shift WHERE a.employee.id = :employeeId ORDER BY a.rosterDate ASC")
    List<RosterAssignment> findByEmployeeIdOrderByRosterDateAsc(@Param("employeeId") Long employeeId);

    @Query("SELECT a FROM RosterAssignment a JOIN FETCH a.shift WHERE a.employee.id = :employeeId ORDER BY a.rosterDate DESC")
    List<RosterAssignment> findTop30ByEmployeeIdOrderByRosterDateDesc(@Param("employeeId") Long employeeId);

    @Query("SELECT a FROM RosterAssignment a JOIN FETCH a.shift WHERE a.employee.id = :employeeId AND a.rosterDate = :rosterDate")
    List<RosterAssignment> findByEmployeeIdAndRosterDate(@Param("employeeId") Long employeeId, @Param("rosterDate") LocalDate rosterDate);

    List<RosterAssignment> findByEmployeeIdAndRosterDateBetween(Long employeeId, LocalDate startDate, LocalDate endDate);

    long countByRosterDateAndShiftShiftTypeAndWeeklyOffFalseAndOnLeaveFalse(LocalDate rosterDate, ShiftType shiftType);
    long countByRosterDateAndWeeklyOffTrue(LocalDate rosterDate);
    long countByRosterDateAndOnLeaveTrue(LocalDate rosterDate);

    @Query("SELECT count(a) FROM RosterAssignment a WHERE a.employee.id = :employeeId AND a.shift.shiftType = :shiftType")
    long countShiftForEmployee(@Param("employeeId") Long employeeId, @Param("shiftType") ShiftType shiftType);

    @Query("SELECT a FROM RosterAssignment a JOIN FETCH a.shift WHERE a.employee.id = :employeeId AND a.rosterDate < :rosterDate AND a.weeklyOff = false AND a.onLeave = false ORDER BY a.rosterDate DESC")
    List<RosterAssignment> findWorkedAssignmentsBefore(@Param("employeeId") Long employeeId, @Param("rosterDate") LocalDate rosterDate);

    @Query("SELECT DISTINCT a FROM RosterAssignment a JOIN FETCH a.employee JOIN FETCH a.shift WHERE a.rosterDate BETWEEN :startDate AND :endDate ORDER BY a.rosterDate ASC, a.employee.id ASC")
    List<RosterAssignment> findByRosterDateBetweenWithDetails(@Param("startDate") LocalDate startDate,
                                                             @Param("endDate") LocalDate endDate);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM RosterAssignment a WHERE a.cycle = :cycle")
    void deleteByCycle(@Param("cycle") RosterCycle cycle);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM RosterAssignment a WHERE a.rosterDate BETWEEN :startDate AND :endDate")
    void deleteByRosterDateBetween(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM roster_assignments WHERE cycle_id = :cycleId", nativeQuery = true)
    void deleteByCycleIdNative(@Param("cycleId") Long cycleId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM roster_assignments WHERE roster_date BETWEEN :startDate AND :endDate", nativeQuery = true)
    void deleteByDateRangeNative(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
}
