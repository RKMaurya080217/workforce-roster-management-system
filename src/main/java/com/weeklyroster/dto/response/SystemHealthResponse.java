package com.weeklyroster.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public record SystemHealthResponse(
        String overallStatus,
        LocalDateTime timestamp,
        String version,
        List<ComponentHealth> components
) {}