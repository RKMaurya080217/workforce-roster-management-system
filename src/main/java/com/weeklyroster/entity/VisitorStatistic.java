package com.weeklyroster.entity;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.time.LocalDateTime;

@Entity
@DiscriminatorValue("VISITOR_STATS")
public class VisitorStatistic extends MasterReferenceItem {

    @Column(name = "total_visits")
    private Long totalVisits = 0L;

    @Column(name = "last_visit_at")
    private LocalDateTime lastVisitAt;

    public VisitorStatistic() {}

    public VisitorStatistic(Long totalVisits, LocalDateTime lastVisitAt) {
        this.totalVisits = totalVisits != null ? totalVisits : 0L;
        this.lastVisitAt = lastVisitAt;
    }

    public Long getTotalVisits() {
        return totalVisits != null ? totalVisits : 0L;
    }

    public void setTotalVisits(Long totalVisits) {
        this.totalVisits = totalVisits != null ? totalVisits : 0L;
    }

    public LocalDateTime getLastVisitAt() {
        return lastVisitAt;
    }

    public void setLastVisitAt(LocalDateTime lastVisitAt) {
        this.lastVisitAt = lastVisitAt;
    }
}