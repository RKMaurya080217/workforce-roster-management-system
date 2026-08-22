package com.weeklyroster.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "shift_handovers", indexes = {
    @Index(name = "idx_handover_date", columnList = "handover_date"),
    @Index(name = "idx_handover_from_emp", columnList = "from_employee_id"),
    @Index(name = "idx_handover_to_emp", columnList = "to_employee_id"),
    @Index(name = "idx_handover_status", columnList = "status")
})
public class ShiftHandover {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "handover_date", nullable = false)
    private LocalDate handoverDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id", nullable = false)
    private Shift shift;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_employee_id", nullable = false)
    private Employee fromEmployee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_employee_id")
    private Employee toEmployee;

    @Column(nullable = false, length = 300)
    private String summary;

    @Column(name = "pending_tasks", length = 2000)
    private String pendingTasks;

    @Column(name = "completed_tasks", length = 2000)
    private String completedTasks;

    @Column(name = "important_notes", length = 2000)
    private String importantNotes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private HandoverPriority priority = HandoverPriority.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private HandoverStatus status = HandoverStatus.OPEN;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public ShiftHandover() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getHandoverDate() { return handoverDate; }
    public void setHandoverDate(LocalDate handoverDate) { this.handoverDate = handoverDate; }
    public Shift getShift() { return shift; }
    public void setShift(Shift shift) { this.shift = shift; }
    public Employee getFromEmployee() { return fromEmployee; }
    public void setFromEmployee(Employee fromEmployee) { this.fromEmployee = fromEmployee; }
    public Employee getToEmployee() { return toEmployee; }
    public void setToEmployee(Employee toEmployee) { this.toEmployee = toEmployee; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getPendingTasks() { return pendingTasks; }
    public void setPendingTasks(String pendingTasks) { this.pendingTasks = pendingTasks; }
    public String getCompletedTasks() { return completedTasks; }
    public void setCompletedTasks(String completedTasks) { this.completedTasks = completedTasks; }
    public String getImportantNotes() { return importantNotes; }
    public void setImportantNotes(String importantNotes) { this.importantNotes = importantNotes; }
    public HandoverPriority getPriority() { return priority; }
    public void setPriority(HandoverPriority priority) { this.priority = priority; }
    public HandoverStatus getStatus() { return status; }
    public void setStatus(HandoverStatus status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
