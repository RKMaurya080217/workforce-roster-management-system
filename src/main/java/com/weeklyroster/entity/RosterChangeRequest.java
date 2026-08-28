package com.weeklyroster.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "roster_change_requests")
public class RosterChangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cycle_id")
    private RosterCycle cycle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false)
    private RosterAssignment assignment;

    @Column(name = "roster_date", nullable = false)
    private LocalDate rosterDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_shift_type", nullable = false)
    private ShiftType currentShiftType;

    @Column(name = "current_weekly_off", nullable = false)
    private boolean currentWeeklyOff;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_shift_type", nullable = false)
    private ShiftType requestedShiftType;

    @Column(name = "requested_weekly_off", nullable = false)
    private boolean requestedWeeklyOff;

    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RosterChangeStatus status = RosterChangeStatus.PENDING;

    @Column(name = "admin_remarks", length = 1000)
    private String adminRemarks;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decided_by")
    private String decidedBy;

    public RosterChangeRequest() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Employee getEmployee() { return employee; }
    public void setEmployee(Employee employee) { this.employee = employee; }

    public RosterCycle getCycle() { return cycle; }
    public void setCycle(RosterCycle cycle) { this.cycle = cycle; }

    public RosterAssignment getAssignment() { return assignment; }
    public void setAssignment(RosterAssignment assignment) { this.assignment = assignment; }

    public LocalDate getRosterDate() { return rosterDate; }
    public void setRosterDate(LocalDate rosterDate) { this.rosterDate = rosterDate; }

    public ShiftType getCurrentShiftType() { return currentShiftType; }
    public void setCurrentShiftType(ShiftType currentShiftType) { this.currentShiftType = currentShiftType; }

    public boolean isCurrentWeeklyOff() { return currentWeeklyOff; }
    public void setCurrentWeeklyOff(boolean currentWeeklyOff) { this.currentWeeklyOff = currentWeeklyOff; }

    public ShiftType getRequestedShiftType() { return requestedShiftType; }
    public void setRequestedShiftType(ShiftType requestedShiftType) { this.requestedShiftType = requestedShiftType; }

    public boolean isRequestedWeeklyOff() { return requestedWeeklyOff; }
    public void setRequestedWeeklyOff(boolean requestedWeeklyOff) { this.requestedWeeklyOff = requestedWeeklyOff; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public RosterChangeStatus getStatus() { return status; }
    public void setStatus(RosterChangeStatus status) { this.status = status; }

    public String getAdminRemarks() { return adminRemarks; }
    public void setAdminRemarks(String adminRemarks) { this.adminRemarks = adminRemarks; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }

    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }
}
