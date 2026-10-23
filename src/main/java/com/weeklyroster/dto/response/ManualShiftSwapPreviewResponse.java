package com.weeklyroster.dto.response;

import java.time.LocalDate;
import java.util.List;

public record ManualShiftSwapPreviewResponse(
        Long employeeAId,
        String employeeAName,
        LocalDate dateA,
        String originalShiftA,
        String proposedShiftA,
        boolean originalOffA,
        boolean proposedOffA,
        Long employeeBId,
        String employeeBName,
        LocalDate dateB,
        String originalShiftB,
        String proposedShiftB,
        boolean originalOffB,
        boolean proposedOffB,
        int conflictCount,
        List<String> conflicts,
        boolean canApply
) {}
