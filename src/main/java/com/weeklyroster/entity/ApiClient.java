package com.weeklyroster.entity;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.time.LocalDateTime;

@Entity
@DiscriminatorValue("API_CLIENT")
public class ApiClient extends MasterReferenceItem {

    @Column(name = "client_name", length = 100)
    private String clientName;

    @Column(name = "api_key_hash", length = 128)
    private String apiKeyHash;

    @Column(name = "client_scopes", length = 255)
    private String scopes;

    @Column(name = "rate_limit_per_minute")
    private Integer rateLimitPerMinute = 60;

    @Column(name = "client_active")
    private Boolean active = true;

    @Column(name = "client_created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    public ApiClient() {}

    public ApiClient(String clientName, String apiKeyHash, String scopes, Integer rateLimitPerMinute) {
        this.clientName = clientName;
        this.apiKeyHash = apiKeyHash;
        this.scopes = scopes;
        this.rateLimitPerMinute = rateLimitPerMinute != null ? rateLimitPerMinute : 60;
        this.active = true;
        this.createdAt = LocalDateTime.now();
    }

    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }

    public String getApiKeyHash() { return apiKeyHash; }
    public void setApiKeyHash(String apiKeyHash) { this.apiKeyHash = apiKeyHash; }

    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }

    public Integer getRateLimitPerMinute() { return rateLimitPerMinute; }
    public void setRateLimitPerMinute(Integer rateLimitPerMinute) { this.rateLimitPerMinute = rateLimitPerMinute; }

    public Boolean getActive() { return active; }
    public boolean isActive() { return active != null && active; }
    public void setActive(Boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}
