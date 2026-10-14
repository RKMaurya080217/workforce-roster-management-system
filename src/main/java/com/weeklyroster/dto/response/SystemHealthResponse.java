package com.weeklyroster.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public record SystemHealthResponse(
        String overallStatus,
        LocalDateTime timestamp,
        String version,
        String activeProfile,
        String databaseType,
        String emailProvider,
        String serverTimezone,
        List<ComponentHealth> components
) {
    public SystemHealthResponse(String overallStatus, LocalDateTime timestamp, String version, List<ComponentHealth> components) {
        this(overallStatus, timestamp, version, "local", "SQLite", "Brevo", "Asia/Kolkata", components);
    }
}