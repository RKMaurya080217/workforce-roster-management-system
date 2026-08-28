package com.weeklyroster.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "roster_versions", indexes = {
    @Index(name = "idx_roster_version_cycle", columnList = "cycle_id"),
    @Index(name = "idx_roster_version_num", columnList = "cycle_id, version_number")
})
public class RosterVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cycle_id", nullable = false)
    private RosterCycle cycle;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "action_reason", length = 500)
    private String actionReason;

    @Column(name = "created_timestamp", nullable = false)
    private LocalDateTime createdTimestamp = LocalDateTime.now();

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "generation_mode", length = 30)
    private String generationMode;

    @Column(name = "status", length = 30)
    private String status;

    @Column(name = "affected_assignments_count")
    private Integer affectedAssignmentsCount = 0;

    @Lob
    @Column(name = "snapshot_data", columnDefinition = "LONGTEXT")
    private String snapshotData;

    @Column(name = "health_score")
    private Integer healthScore = 94;

    @Column(name = "impact_summary", length = 500)
    private String impactSummary;

    public RosterVersion() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public RosterCycle getCycle() { return cycle; }
    public void setCycle(RosterCycle cycle) { this.cycle = cycle; }
    public int getVersionNumber() { return versionNumber; }
    public void setVersionNumber(int versionNumber) { this.versionNumber = versionNumber; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getActionReason() { return actionReason; }
    public void setActionReason(String actionReason) { this.actionReason = actionReason; }
    public LocalDateTime getCreatedTimestamp() { return createdTimestamp; }
    public void setCreatedTimestamp(LocalDateTime createdTimestamp) { this.createdTimestamp = createdTimestamp; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getGenerationMode() { return generationMode; }
    public void setGenerationMode(String generationMode) { this.generationMode = generationMode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getAffectedAssignmentsCount() { return affectedAssignmentsCount; }
    public void setAffectedAssignmentsCount(Integer affectedAssignmentsCount) { this.affectedAssignmentsCount = affectedAssignmentsCount; }
    public String getSnapshotData() { return snapshotData; }
    public void setSnapshotData(String snapshotData) { this.snapshotData = snapshotData; }
    public Integer getHealthScore() { return healthScore; }
    public void setHealthScore(Integer healthScore) { this.healthScore = healthScore; }
    public String getImpactSummary() { return impactSummary; }
    public void setImpactSummary(String impactSummary) { this.impactSummary = impactSummary; }

}
