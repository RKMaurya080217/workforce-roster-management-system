package com.weeklyroster.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;

@Entity
@Table(name = "profile_change_requests", indexes = {
        @Index(name = "idx_pcr_employee_status", columnList = "employee_id, status"),
        @Index(name = "idx_pcr_status", columnList = "status")
})
public class ProfileChangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(nullable = false, length = 60)
    private String fieldName;

    @Column(length = 255)
    private String currentValue;

    @Column(nullable = false, length = 255)
    private String requestedValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProfileChangeStatus status = ProfileChangeStatus.PENDING;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime reviewedAt;

    @Column(length = 500)
    private String adminRemarks;

    @Version
    private Long version;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Employee getEmployee() {
        return employee;
    }

    public void setEmployee(Employee employee) {
        this.employee = employee;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getCurrentValue() {
        return currentValue;
    }

    public void setCurrentValue(String currentValue) {
        this.currentValue = currentValue;
    }

    public String getRequestedValue() {
        return requestedValue;
    }

    public void setRequestedValue(String requestedValue) {
        this.requestedValue = requestedValue;
    }

    public ProfileChangeStatus getStatus() {
        return status;
    }

    public void setStatus(ProfileChangeStatus status) {
        this.status = status;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(LocalDateTime requestedAt) {
        this.requestedAt = requestedAt;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getAdminRemarks() {
        return adminRemarks;
    }

    public void setAdminRemarks(String adminRemarks) {
        this.adminRemarks = adminRemarks;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
