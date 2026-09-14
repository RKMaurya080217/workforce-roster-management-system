package com.weeklyroster.entity;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.time.LocalDateTime;

@Entity
@DiscriminatorValue("ACTIVE_VISITOR")
public class ActiveVisitor extends MasterReferenceItem {

    @Column(name = "visitor_id", length = 64)
    private String visitorId;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    public ActiveVisitor() {}

    public ActiveVisitor(String visitorId, LocalDateTime lastSeenAt) {
        this.visitorId = visitorId;
        this.lastSeenAt = lastSeenAt;
    }

    public String getVisitorId() {
        return visitorId;
    }

    public void setVisitorId(String visitorId) {
        this.visitorId = visitorId;
    }

    public LocalDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(LocalDateTime lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }
}