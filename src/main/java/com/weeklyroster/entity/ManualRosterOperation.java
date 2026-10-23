package com.weeklyroster.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "manual_roster_operations")
public class ManualRosterOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cycle_id")
    private Long cycleId;

    @Column(name = "operation_type", length = 50, nullable = false)
    private String operationType;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "admin_username", length = 100)
    private String adminUsername;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "undone", nullable = false)
    private Boolean undone = Boolean.FALSE;

    @Column(name = "undone_at")
    private LocalDateTime undoneAt;

    @Lob
    @Column(name = "snapshot_json", columnDefinition = "TEXT")
    private String snapshotJson;

    public ManualRosterOperation() {}

    public ManualRosterOperation(Long cycleId, String operationType, String description,
                                 String adminUsername, String snapshotJson) {
        this.cycleId = cycleId;
        this.operationType = operationType;
        this.description = description;
        this.adminUsername = adminUsername;
        this.snapshotJson = snapshotJson;
        this.createdAt = LocalDateTime.now();
        this.undone = Boolean.FALSE;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCycleId() {
        return cycleId;
    }

    public void setCycleId(Long cycleId) {
        this.cycleId = cycleId;
    }

    public String getOperationType() {
        return operationType;
    }

    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Boolean getUndone() {
        return undone != null ? undone : Boolean.FALSE;
    }

    public boolean isUndone() {
        return Boolean.TRUE.equals(this.undone);
    }

    public void setUndone(Boolean undone) {
        this.undone = undone != null ? undone : Boolean.FALSE;
    }

    public LocalDateTime getUndoneAt() {
        return undoneAt;
    }

    public void setUndoneAt(LocalDateTime undoneAt) {
        this.undoneAt = undoneAt;
    }

    public String getSnapshotJson() {
        return snapshotJson;
    }

    public void setSnapshotJson(String snapshotJson) {
        this.snapshotJson = snapshotJson;
    }
}
