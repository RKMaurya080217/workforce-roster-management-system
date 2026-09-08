package com.weeklyroster.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@DiscriminatorValue("EMPLOYEE_ACTIVITY")
public class EmployeeActivityLog extends SystemAuditLogEntry {

    @Column(name = "activity_employee_id")
    private Long employeeId;

    @Column(length = 100)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_category", length = 50)
    private ActivityCategory category;

    @Column(name = "activity_action", length = 80)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_status", length = 30)
    private ActivityStatus status;

    @Column(name = "activity_description", length = 500)
    private String description;

    @Column(name = "activity_source", length = 100)
    private String source = "WEB";

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getEmployeeId() { return employeeId; }
    public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public ActivityCategory getCategory() { return category; }
    public void setCategory(ActivityCategory category) { this.category = category; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public ActivityStatus getStatus() { return status; }
    public void setStatus(ActivityStatus status) { this.status = status; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
