package com.weeklyroster.dto.request;

import jakarta.validation.constraints.NotBlank;

public record SkillRequest(
        @NotBlank(message = "Skill name is required") String name,
        String category,
        String description,
        Boolean active
) {}
