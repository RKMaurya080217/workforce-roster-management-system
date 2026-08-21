package com.weeklyroster.service;

import com.weeklyroster.dto.response.AuditLogResponse;
import com.weeklyroster.entity.AuditAction;
import com.weeklyroster.entity.AuditLog;
import com.weeklyroster.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog log(AuditAction action,
                        String entityType,
                        Long entityId,
                        Long cycleId,
                        Long employeeId,
                        String employeeName,
                        String oldValue,
                        String newValue,
                        String reason,
                        String source) {
        String actor = "SYSTEM";
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            actor = auth.getName();
        }

        AuditLog auditLog = new AuditLog();
        auditLog.setTimestamp(LocalDateTime.now());
        auditLog.setActor(actor);
        auditLog.setAction(action);
        auditLog.setEntityType(entityType != null ? entityType : "UNKNOWN");
        auditLog.setEntityId(entityId);
        auditLog.setCycleId(cycleId);
        auditLog.setEmployeeId(employeeId);
        auditLog.setEmployeeName(employeeName);
        auditLog.setOldValue(oldValue);
        auditLog.setNewValue(newValue);
        auditLog.setReason(reason);
        auditLog.setSource(source != null ? source : (actor.equalsIgnoreCase("SYSTEM") ? "SYSTEM" : "MANUAL"));

        try {
            AuditLog saved = auditLogRepository.save(auditLog);
            log.info("AUDIT LOG [{}] Actor: {} Entity: {}:{} Reason: {}", action, actor, entityType, entityId, reason);
            return saved;
        } catch (Exception e) {
            log.error("Failed to persist audit log for action: {}", action, e);
            return auditLog;
        }
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> searchAuditLogs(Long cycleId,
                                                  AuditAction action,
                                                  String actor,
                                                  Long employeeId,
                                                  LocalDateTime fromTimestamp,
                                                  LocalDateTime toTimestamp) {
        return auditLogRepository.searchAuditLogs(cycleId, action, actor, employeeId, fromTimestamp, toTimestamp)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> getAllLogs() {
        return auditLogRepository.findAllByOrderByTimestampDesc().stream().map(this::toResponse).toList();
    }

    public AuditLogResponse toResponse(AuditLog log) {
        return new AuditLogResponse(
                log.getId(),
                log.getTimestamp(),
                log.getActor(),
                log.getAction(),
                log.getEntityType(),
                log.getEntityId(),
                log.getCycleId(),
                log.getEmployeeId(),
                log.getEmployeeName(),
                log.getOldValue(),
                log.getNewValue(),
                log.getReason(),
                log.getSource()
        );
    }
}
