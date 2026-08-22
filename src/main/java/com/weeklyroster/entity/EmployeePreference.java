package com.weeklyroster.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "employee_preferences", indexes = {
    @Index(name = "idx_emp_pref_emp", columnList = "employee_id"),
    @Index(name = "idx_emp_pref_status", columnList = "status")
})
public class EmployeePreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "preferred_shift_types", length = 150)
    private String preferredShiftTypes;

    @Column(name = "preferred_off_days", length = 150)
    private String preferredOffDays;

    @Column(name = "preferred_working_days", length = 150)
    private String preferredWorkingDays;

    @Column(name = "avoid_shift_types", length = 150)
    private String avoidShiftTypes;

    @Column(name = "temporary_restrictions", length = 1000)
    private String temporaryRestrictions;

    @Column(length = 1000)
    private String remarks;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PreferenceStatus status = PreferenceStatus.PENDING;

    @Column(name = "admin_remarks", length = 1000)
    private String adminRemarks;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reviewed_by", length = 100)
    private String reviewedBy;

    public EmployeePreference() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Employee getEmployee() { return employee; }
    public void setEmployee(Employee employee) { this.employee = employee; }
    public String getPreferredShiftTypes() { return preferredShiftTypes; }
    public void setPreferredShiftTypes(String preferredShiftTypes) { this.preferredShiftTypes = preferredShiftTypes; }
    public String getPreferredOffDays() { return preferredOffDays; }
    public void setPreferredOffDays(String preferredOffDays) { this.preferredOffDays = preferredOffDays; }
    public String getPreferredWorkingDays() { return preferredWorkingDays; }
    public void setPreferredWorkingDays(String preferredWorkingDays) { this.preferredWorkingDays = preferredWorkingDays; }
    public String getAvoidShiftTypes() { return avoidShiftTypes; }
    public void setAvoidShiftTypes(String avoidShiftTypes) { this.avoidShiftTypes = avoidShiftTypes; }
    public String getTemporaryRestrictions() { return temporaryRestrictions; }
    public void setTemporaryRestrictions(String temporaryRestrictions) { this.temporaryRestrictions = temporaryRestrictions; }
    public String getRemarks() { return remarks; }
    public void setRemarks(String remarks) { this.remarks = remarks; }
    public PreferenceStatus getStatus() { return status; }
    public void setStatus(PreferenceStatus status) { this.status = status; }
    public String getAdminRemarks() { return adminRemarks; }
    public void setAdminRemarks(String adminRemarks) { this.adminRemarks = adminRemarks; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(LocalDate effectiveTo) { this.effectiveTo = effectiveTo; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
}
