package com.weeklyroster.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "roster_cycles", uniqueConstraints = @UniqueConstraint(columnNames = {"start_date", "end_date"}))
public class RosterCycle {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private LocalDateTime generatedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GenerationMode generationMode = GenerationMode.MANUAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RosterStatus status = RosterStatus.GENERATED;

    @Column
    private LocalDateTime publishedAt;

    @Column(length = 100)
    private String publishedBy;

    @Column
    private LocalDateTime lockedAt;

    @Column(length = 100)
    private String lockedBy;

    @Column
    private LocalDateTime unlockedAt;

    @Column(length = 100)
    private String unlockedBy;

    @Column(length = 500)
    private String unlockReason;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }
    public GenerationMode getGenerationMode() { return generationMode; }
    public void setGenerationMode(GenerationMode generationMode) { this.generationMode = generationMode; }
    public RosterStatus getStatus() { return status; }
    public void setStatus(RosterStatus status) { this.status = status; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    public String getPublishedBy() { return publishedBy; }
    public void setPublishedBy(String publishedBy) { this.publishedBy = publishedBy; }
    public LocalDateTime getLockedAt() { return lockedAt; }
    public void setLockedAt(LocalDateTime lockedAt) { this.lockedAt = lockedAt; }
    public String getLockedBy() { return lockedBy; }
    public void setLockedBy(String lockedBy) { this.lockedBy = lockedBy; }
    public LocalDateTime getUnlockedAt() { return unlockedAt; }
    public void setUnlockedAt(LocalDateTime unlockedAt) { this.unlockedAt = unlockedAt; }
    public String getUnlockedBy() { return unlockedBy; }
    public void setUnlockedBy(String unlockedBy) { this.unlockedBy = unlockedBy; }
    public String getUnlockReason() { return unlockReason; }
    public void setUnlockReason(String unlockReason) { this.unlockReason = unlockReason; }
}
