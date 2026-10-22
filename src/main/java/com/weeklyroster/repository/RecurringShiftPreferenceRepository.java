package com.weeklyroster.repository;

import com.weeklyroster.entity.RecurringShiftPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.DayOfWeek;
import java.util.List;

public interface RecurringShiftPreferenceRepository extends JpaRepository<RecurringShiftPreference, Long> {

    @Query("SELECT r FROM RecurringShiftPreference r JOIN FETCH r.employee WHERE r.employee.id = :employeeId ORDER BY r.dayOfWeek ASC, r.startDate ASC")
    List<RecurringShiftPreference> findByEmployeeId(@Param("employeeId") Long employeeId);

    @Query("SELECT r FROM RecurringShiftPreference r JOIN FETCH r.employee WHERE r.employee.id = :employeeId AND r.active = true ORDER BY r.dayOfWeek ASC")
    List<RecurringShiftPreference> findByEmployeeIdAndActiveTrue(@Param("employeeId") Long employeeId);

    @Query("SELECT r FROM RecurringShiftPreference r JOIN FETCH r.employee WHERE r.active = true")
    List<RecurringShiftPreference> findByActiveTrue();

    @Query("SELECT r FROM RecurringShiftPreference r WHERE r.employee.id = :employeeId AND r.dayOfWeek = :dayOfWeek")
    List<RecurringShiftPreference> findByEmployeeIdAndDayOfWeek(@Param("employeeId") Long employeeId, @Param("dayOfWeek") DayOfWeek dayOfWeek);
}
