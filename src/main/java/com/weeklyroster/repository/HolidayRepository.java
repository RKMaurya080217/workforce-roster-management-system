package com.weeklyroster.repository;

import com.weeklyroster.entity.Holiday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface HolidayRepository extends JpaRepository<Holiday, Long> {
    List<Holiday> findByHolidayDate(LocalDate holidayDate);
    List<Holiday> findByHolidayDateAndActiveTrue(LocalDate holidayDate);
    List<Holiday> findByHolidayDateOrderByCreatedAtDesc(LocalDate holidayDate);
    List<Holiday> findByHolidayDateAndActiveTrueOrderByCreatedAtDesc(LocalDate holidayDate);
    boolean existsByHolidayDate(LocalDate holidayDate);
    boolean existsByHolidayDateAndIdNot(LocalDate holidayDate, Long id);
    List<Holiday> findByActiveTrueOrderByHolidayDateAsc();
    List<Holiday> findAllByOrderByHolidayDateAsc();
    List<Holiday> findAllByOrderByHolidayDateDesc();
    List<Holiday> findByHolidayDateBetweenOrderByHolidayDateAsc(LocalDate startDate, LocalDate endDate);
    List<Holiday> findByHolidayDateGreaterThanEqualAndActiveTrueOrderByHolidayDateAsc(LocalDate fromDate);
}
