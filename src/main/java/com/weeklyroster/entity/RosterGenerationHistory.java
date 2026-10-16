package com.weeklyroster.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "roster_generation_history", indexes = {
        @Index(name = "idx_rgh_dates", columnList = "start_date, end_date"),
        @Index(name = "idx_rgh_fingerprint", columnList = "fingerprint")
})
public class RosterGenerationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "fingerprint", nullable = false, length = 128)
    private String fingerprint;

    @Column(name = "generation_attempt", nullable = false)
    private Integer generationAttempt = 1;

    @Column(name = "generation_mode", length = 30)
    private String generationMode = "MANUAL";

    @Column(name = "quality_score")
    private Integer qualityScore = 0;

    @Column(name = "preference_score")
    private Integer preferenceScore = 0;

    @Column(name = "continuity_score")
    private Integer continuityScore = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public RosterGenerationHistory() {}

    public RosterGenerationHistory(LocalDate startDate, LocalDate endDate, String fingerprint,
                                   Integer generationAttempt, String generationMode,
                                   Integer qualityScore, Integer preferenceScore, Integer continuityScore) {
        this.startDate = startDate;
        this.endDate = endDate;
        this.fingerprint = fingerprint;
        this.generationAttempt = generationAttempt != null ? generationAttempt : 1;
        this.generationMode = generationMode != null ? generationMode : "MANUAL";
        this.qualityScore = qualityScore != null ? qualityScore : 0;
        this.preferenceScore = preferenceScore != null ? preferenceScore : 0;
        this.continuityScore = continuityScore != null ? continuityScore : 0;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

    public Integer getGenerationAttempt() { return generationAttempt; }
    public void setGenerationAttempt(Integer generationAttempt) { this.generationAttempt = generationAttempt; }

    public String getGenerationMode() { return generationMode; }
    public void setGenerationMode(String generationMode) { this.generationMode = generationMode; }

    public Integer getQualityScore() { return qualityScore; }
    public void setQualityScore(Integer qualityScore) { this.qualityScore = qualityScore; }

    public Integer getPreferenceScore() { return preferenceScore; }
    public void setPreferenceScore(Integer preferenceScore) { this.preferenceScore = preferenceScore; }

    public Integer getContinuityScore() { return continuityScore; }
    public void setContinuityScore(Integer continuityScore) { this.continuityScore = continuityScore; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
