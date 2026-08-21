package com.weeklyroster.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "employee_activity_logs", indexes = {
    @Index(name = "idx_emp_act_emp_id", columnList = "employee_id"),
    @Index(name = "idx_emp_act_username", columnList = "username"),
    @Index(name = "idx_emp_act_category", columnList = "category"),
    @Index(name = "idx_emp_act_created_at", columnList = "created_at")
})
public class EmployeeActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(nullable = false, length = 100)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ActivityCategory category;

    @Column(nullable = false, length = 80)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ActivityStatus status;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(length = 100)
    private String source = "WEB";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
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
