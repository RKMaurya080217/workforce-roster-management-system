package com.weeklyroster.dto.request;

import com.weeklyroster.entity.ProficiencyLevel;
import java.time.LocalDate;

public record UpdateEmployeeSkillRequest(
        ProficiencyLevel proficiencyLevel,
        String certificationName,
        LocalDate certificationExpiryDate,
        Boolean certified,
        Boolean active
) {}
