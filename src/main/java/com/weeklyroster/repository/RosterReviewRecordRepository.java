package com.weeklyroster.repository;

import com.weeklyroster.entity.RosterReviewRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RosterReviewRecordRepository extends JpaRepository<RosterReviewRecord, Long> {
    Optional<RosterReviewRecord> findByEmployeeIdAndCycleId(Long employeeId, Long cycleId);
    List<RosterReviewRecord> findByCycleId(Long cycleId);
    long countByCycleId(Long cycleId);
    boolean existsByEmployeeIdAndCycleId(Long employeeId, Long cycleId);
}
