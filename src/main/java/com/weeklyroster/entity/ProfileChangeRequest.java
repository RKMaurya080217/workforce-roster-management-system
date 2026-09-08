package com.weeklyroster.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@DiscriminatorValue("PROFILE_CHANGE")
public class ProfileChangeRequest extends EmployeeWorkflowRequest {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @Column(name = "field_name", length = 60)
    private String fieldName;

    @Column(name = "current_value", length = 255)
    private String currentValue;

    @Column(name = "requested_value", length = 255)
    private String requestedValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "profile_status", length = 30)
    private ProfileChangeStatus status = ProfileChangeStatus.PENDING;

    @Column(name = "requested_at")
    private LocalDateTime requestedAt = LocalDateTime.now();

    @Column(name = "profile_reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "admin_remarks", length = 500)
    private String adminRemarks;

    public Employee getEmployee() { return employee; }
    public void setEmployee(Employee employee) { this.employee = employee; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }
    public String getCurrentValue() { return currentValue; }
    public void setCurrentValue(String currentValue) { this.currentValue = currentValue; }
    public String getRequestedValue() { return requestedValue; }
    public void setRequestedValue(String requestedValue) { this.requestedValue = requestedValue; }
    public ProfileChangeStatus getStatus() { return status; }
    public void setStatus(ProfileChangeStatus status) { this.status = status; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public void setRequestedAt(LocalDateTime requestedAt) { this.requestedAt = requestedAt; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getAdminRemarks() { return adminRemarks; }
    public void setAdminRemarks(String adminRemarks) { this.adminRemarks = adminRemarks; }
    public Long getVersion() { return super.getVersion(); }
    public void setVersion(Long version) { super.setVersion(version); }
}
