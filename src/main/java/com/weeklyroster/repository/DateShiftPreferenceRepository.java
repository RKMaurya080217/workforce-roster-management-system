package com.weeklyroster.repository;

import com.weeklyroster.entity.DateShiftPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DateShiftPreferenceRepository extends JpaRepository<DateShiftPreference, Long> {

    Optional<DateShiftPreference> findByEmployeeIdAndPreferenceDate(Long employeeId, LocalDate preferenceDate);

    List<DateShiftPreference> findByPreferenceDateBetween(LocalDate startDate, LocalDate endDate);

    List<DateShiftPreference> findAllByOrderByPreferenceDateDesc();

    List<DateShiftPreference> findByEmployeeIdOrderByPreferenceDateDesc(Long employeeId);

    boolean existsByEmployeeIdAndPreferenceDate(Long employeeId, LocalDate preferenceDate);

    void deleteByEmployeeIdAndPreferenceDate(Long employeeId, LocalDate preferenceDate);

    void deleteByEmployeeId(Long employeeId);
}
