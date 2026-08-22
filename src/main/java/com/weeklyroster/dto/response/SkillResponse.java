package com.weeklyroster.dto.response;

import java.time.LocalDateTime;

public record SkillResponse(
        Long id,
        String name,
        String category,
        String description,
        boolean active,
        LocalDateTime createdAt
) {}
