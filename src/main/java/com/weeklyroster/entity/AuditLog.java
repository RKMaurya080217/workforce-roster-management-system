package com.weeklyroster.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@DiscriminatorValue("AUDIT")
public class AuditLog extends SystemAuditLogEntry {

    @Column(name = "audit_timestamp")
    private LocalDateTime timestamp = LocalDateTime.now();

    @Column(length = 100)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "audit_action", length = 50)
    private AuditAction action;

    @Column(name = "entity_type", length = 50)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "cycle_id")
    private Long cycleId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "employee_name", length = 150)
    private String employeeName;

    @Column(name = "old_value", length = 1000)
    private String oldValue;

    @Column(name = "new_value", length = 1000)
    private String newValue;

    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "source", length = 30)
    private String source = "MANUAL";

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public AuditAction getAction() { return action; }
    public void setAction(AuditAction action) { this.action = action; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public Long getEntityId() { return entityId; }
    public void setEntityId(Long entityId) { this.entityId = entityId; }
    public Long getCycleId() { return cycleId; }
    public void setCycleId(Long cycleId) { this.cycleId = cycleId; }
    public Long getEmployeeId() { return employeeId; }
    public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }
    public String getEmployeeName() { return employeeName; }
    public void setEmployeeName(String employeeName) { this.employeeName = employeeName; }
    public String getOldValue() { return oldValue; }
    public void setOldValue(String oldValue) { this.oldValue = oldValue; }
    public String getNewValue() { return newValue; }
    public void setNewValue(String newValue) { this.newValue = newValue; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
