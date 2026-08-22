package com.weeklyroster.dto.request;

import com.weeklyroster.entity.ProficiencyLevel;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record AssignEmployeeSkillRequest(
        @NotNull(message = "Employee ID is required") Long employeeId,
        @NotNull(message = "Skill ID is required") Long skillId,
        ProficiencyLevel proficiencyLevel,
        String certificationName,
        LocalDate certificationExpiryDate,
        Boolean certified
) {}
