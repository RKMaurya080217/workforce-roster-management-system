package com.weeklyroster.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@DiscriminatorValue("ROSTER_VERSION")
public class RosterVersion extends SystemAuditLogEntry {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cycle_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private RosterCycle cycle;

    @Column(name = "version_number")
    private Integer versionNumber = 1;

    @Column(name = "version_action", length = 50)
    private String action;

    @Column(name = "action_reason", length = 500)
    private String actionReason;

    @Column(name = "created_timestamp")
    private LocalDateTime createdTimestamp = LocalDateTime.now();

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "version_mode", length = 30)
    private String generationMode;

    @Column(name = "version_status", length = 30)
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

    public RosterCycle getCycle() { return cycle; }
    public void setCycle(RosterCycle cycle) { this.cycle = cycle; }
    public Integer getVersionNumber() { return versionNumber; }
    public void setVersionNumber(Integer versionNumber) { this.versionNumber = versionNumber; }
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
