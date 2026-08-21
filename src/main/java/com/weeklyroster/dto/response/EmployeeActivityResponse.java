package com.weeklyroster.dto.response;

import com.weeklyroster.entity.ActivityCategory;
import com.weeklyroster.entity.ActivityStatus;
import java.time.LocalDateTime;

public record EmployeeActivityResponse(
    Long id,
    Long employeeId,
    String username,
    ActivityCategory category,
    String action,
    ActivityStatus status,
    String description,
    String source,
    LocalDateTime createdAt
) {}
