package com.weeklyroster.repository;

import com.weeklyroster.entity.RosterChangeRequest;
import com.weeklyroster.entity.RosterChangeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RosterChangeRequestRepository extends JpaRepository<RosterChangeRequest, Long> {
    List<RosterChangeRequest> findByEmployeeIdOrderByCreatedAtDesc(Long employeeId);
    List<RosterChangeRequest> findByCycleIdOrderByCreatedAtDesc(Long cycleId);
    List<RosterChangeRequest> findByStatusOrderByCreatedAtDesc(RosterChangeStatus status);
    List<RosterChangeRequest> findByCycleIdAndStatusOrderByCreatedAtDesc(Long cycleId, RosterChangeStatus status);
    Optional<RosterChangeRequest> findByAssignmentIdAndStatus(Long assignmentId, RosterChangeStatus status);
    long countByStatus(RosterChangeStatus status);
    long countByCycleIdAndStatus(Long cycleId, RosterChangeStatus status);
}
