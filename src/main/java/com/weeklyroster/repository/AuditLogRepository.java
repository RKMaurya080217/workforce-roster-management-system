package com.weeklyroster.repository;

import com.weeklyroster.entity.AuditAction;
import com.weeklyroster.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findAllByOrderByTimestampDesc();

    List<AuditLog> findByCycleIdOrderByTimestampDesc(Long cycleId);

    List<AuditLog> findByEmployeeIdOrderByTimestampDesc(Long employeeId);

    List<AuditLog> findByActionOrderByTimestampDesc(AuditAction action);

    @Query("SELECT a FROM AuditLog a WHERE " +
           "(:cycleId IS NULL OR a.cycleId = :cycleId) AND " +
           "(:action IS NULL OR a.action = :action) AND " +
           "(:actor IS NULL OR LOWER(a.actor) LIKE LOWER(CONCAT('%', :actor, '%'))) AND " +
           "(:employeeId IS NULL OR a.employeeId = :employeeId) AND " +
           "(:fromTimestamp IS NULL OR a.timestamp >= :fromTimestamp) AND " +
           "(:toTimestamp IS NULL OR a.timestamp <= :toTimestamp) " +
           "ORDER BY a.timestamp DESC")
    List<AuditLog> searchAuditLogs(
            @Param("cycleId") Long cycleId,
            @Param("action") AuditAction action,
            @Param("actor") String actor,
            @Param("employeeId") Long employeeId,
            @Param("fromTimestamp") LocalDateTime fromTimestamp,
            @Param("toTimestamp") LocalDateTime toTimestamp
    );
}
