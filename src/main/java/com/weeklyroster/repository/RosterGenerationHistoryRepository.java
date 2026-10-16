package com.weeklyroster.repository;

import com.weeklyroster.entity.RosterGenerationHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@Repository
public interface RosterGenerationHistoryRepository extends JpaRepository<RosterGenerationHistory, Long> {

    List<RosterGenerationHistory> findByStartDateAndEndDateOrderByCreatedAtDesc(LocalDate startDate, LocalDate endDate);

    @Query("SELECT r.fingerprint FROM RosterGenerationHistory r WHERE r.startDate = :startDate AND r.endDate = :endDate")
    Set<String> findFingerprintsByDateRange(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    long countByStartDateAndEndDate(LocalDate startDate, LocalDate endDate);
}
