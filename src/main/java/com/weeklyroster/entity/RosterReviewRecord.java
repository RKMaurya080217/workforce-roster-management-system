package com.weeklyroster.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@DiscriminatorValue("ROSTER_REVIEW")
public class RosterReviewRecord extends SystemAuditLogEntry {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cycle_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private RosterCycle cycle;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt = LocalDateTime.now();

    public RosterReviewRecord() {}

    public RosterReviewRecord(Employee employee, RosterCycle cycle) {
        this.employee = employee;
        this.cycle = cycle;
        this.reviewedAt = LocalDateTime.now();
    }

    public Employee getEmployee() { return employee; }
    public void setEmployee(Employee employee) { this.employee = employee; }
    public RosterCycle getCycle() { return cycle; }
    public void setCycle(RosterCycle cycle) { this.cycle = cycle; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
}
