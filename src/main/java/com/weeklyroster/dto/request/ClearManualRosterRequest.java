package com.weeklyroster.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;

public record ClearManualRosterRequest(
        Long cycleId,
        LocalDate startDate,
        @NotBlank String mode, // ENTIRE_WEEK, SELECTED_EMPLOYEES, SELECTED_DATES, SELECTED_CELLS
        List<Long> employeeIds,
        List<LocalDate> dates,
        List<CellCoordinate> cells,
        Boolean previewOnly
) {
    public record CellCoordinate(Long employeeId, LocalDate date) {}
}
