package com.weeklyroster.dto.response;

import com.weeklyroster.entity.ProficiencyLevel;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record EmployeeSkillResponse(
        Long id,
        Long employeeId,
        String employeeCode,
        String employeeName,
        Long skillId,
        String skillName,
        String skillCategory,
        ProficiencyLevel proficiencyLevel,
        String certificationName,
        LocalDate certificationExpiryDate,
        boolean certified,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
