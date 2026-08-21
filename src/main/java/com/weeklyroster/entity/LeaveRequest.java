package com.weeklyroster.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "leave_requests", indexes = {
        @Index(name = "idx_leave_employee_status", columnList = "employee_id, status"),
        @Index(name = "idx_leave_status", columnList = "status"),
        @Index(name = "idx_leave_dates", columnList = "startDate, endDate")
})
public class LeaveRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Employee employee;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LeaveStatus status;

    @Column(length = 500)
    private String adminRemarks;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime reviewedAt;

    private LocalDate originalStartDate;

    private LocalDate originalEndDate;

    private LocalDate pendingStartDate;

    private LocalDate pendingEndDate;

    @Column(length = 500)
    private String modificationReason;

    @Column(length = 500)
    private String cancellationReason;

    private LocalDateTime modifiedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Employee getEmployee() { return employee; }
    public void setEmployee(Employee employee) { this.employee = employee; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LeaveStatus getStatus() { return status; }
    public void setStatus(LeaveStatus status) { this.status = status; }
    public String getAdminRemarks() { return adminRemarks; }
    public void setAdminRemarks(String adminRemarks) { this.adminRemarks = adminRemarks; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public void setRequestedAt(LocalDateTime requestedAt) { this.requestedAt = requestedAt; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }

    public LocalDate getOriginalStartDate() { return originalStartDate; }
    public void setOriginalStartDate(LocalDate originalStartDate) { this.originalStartDate = originalStartDate; }
    public LocalDate getOriginalEndDate() { return originalEndDate; }
    public void setOriginalEndDate(LocalDate originalEndDate) { this.originalEndDate = originalEndDate; }
    public LocalDate getPendingStartDate() { return pendingStartDate; }
    public void setPendingStartDate(LocalDate pendingStartDate) { this.pendingStartDate = pendingStartDate; }
    public LocalDate getPendingEndDate() { return pendingEndDate; }
    public void setPendingEndDate(LocalDate pendingEndDate) { this.pendingEndDate = pendingEndDate; }
    public String getModificationReason() { return modificationReason; }
    public void setModificationReason(String modificationReason) { this.modificationReason = modificationReason; }
    public String getCancellationReason() { return cancellationReason; }
    public void setCancellationReason(String cancellationReason) { this.cancellationReason = cancellationReason; }
    public LocalDateTime getModifiedAt() { return modifiedAt; }
    public void setModifiedAt(LocalDateTime modifiedAt) { this.modifiedAt = modifiedAt; }
}
