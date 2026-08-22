package com.weeklyroster.repository;

import com.weeklyroster.entity.LeaveRequest;
import com.weeklyroster.entity.LeaveStatus;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {
    @Query("SELECT l FROM LeaveRequest l JOIN FETCH l.employee WHERE l.employee.id = :employeeId ORDER BY l.requestedAt DESC")
    List<LeaveRequest> findByEmployeeIdOrderByRequestedAtDesc(@Param("employeeId") Long employeeId);

    List<LeaveRequest> findByEmployeeIdAndStatusOrderByIdDesc(Long employeeId, LeaveStatus status);

    @Query("SELECT l FROM LeaveRequest l JOIN FETCH l.employee WHERE l.status = :status ORDER BY l.requestedAt ASC")
    List<LeaveRequest> findByStatusOrderByRequestedAtAsc(@Param("status") LeaveStatus status);

    @Query("SELECT l FROM LeaveRequest l JOIN FETCH l.employee WHERE l.status IN :statuses ORDER BY l.requestedAt ASC")
    List<LeaveRequest> findByStatusInOrderByRequestedAtAsc(@Param("statuses") List<LeaveStatus> statuses);

    long countByStatus(LeaveStatus status);

    long countByStatusIn(List<LeaveStatus> statuses);

    boolean existsByEmployeeIdAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            Long employeeId, LeaveStatus status, LocalDate date, LocalDate sameDate);

    @Query("SELECT count(l) > 0 FROM LeaveRequest l WHERE l.employee.id = :employeeId AND l.status IN :statuses AND l.startDate <= :endDate AND l.endDate >= :startDate")
    boolean existsOverlappingLeave(@Param("employeeId") Long employeeId,
                                   @Param("statuses") List<LeaveStatus> statuses,
                                   @Param("startDate") LocalDate startDate,
                                   @Param("endDate") LocalDate endDate);

    @Query("SELECT count(l) > 0 FROM LeaveRequest l WHERE l.employee.id = :employeeId AND l.id != :excludeId AND l.status IN :statuses AND l.startDate <= :endDate AND l.endDate >= :startDate")
    boolean existsOverlappingLeaveExcludingId(@Param("employeeId") Long employeeId,
                                             @Param("statuses") List<LeaveStatus> statuses,
                                             @Param("startDate") LocalDate startDate,
                                             @Param("endDate") LocalDate endDate,
                                             @Param("excludeId") Long excludeId);

    @Query("SELECT l FROM LeaveRequest l JOIN FETCH l.employee WHERE l.status = :status AND l.startDate <= :endDate AND l.endDate >= :startDate")
    List<LeaveRequest> findApprovedLeavesInCycle(@Param("status") LeaveStatus status,
                                                 @Param("startDate") LocalDate startDate,
                                                 @Param("endDate") LocalDate endDate);

    @Query("SELECT l FROM LeaveRequest l JOIN FETCH l.employee WHERE l.startDate <= :endDate AND l.endDate >= :startDate")
    List<LeaveRequest> findByStartDateLessThanEqualAndEndDateGreaterThanEqual(@Param("endDate") LocalDate endDate,
                                                                             @Param("startDate") LocalDate startDate);

    default List<LeaveRequest> findPendingRequests() {
        return findByStatusOrderByRequestedAtAsc(LeaveStatus.PENDING);
    }

    default List<LeaveRequest> findOverlappingLeaves(Long employeeId, LocalDate startDate, LocalDate endDate, LeaveStatus status) {
        return findApprovedLeavesInCycle(status, startDate, endDate).stream()
                .filter(l -> l.getEmployee().getId().equals(employeeId))
                .toList();
    }
}
