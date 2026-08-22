package com.weeklyroster.dto.request;

import com.weeklyroster.entity.HandoverPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record CreateHandoverRequest(
        @NotNull(message = "Handover date is required") LocalDate handoverDate,
        @NotNull(message = "Shift ID is required") Long shiftId,
        Long toEmployeeId,
        @NotBlank(message = "Summary is required") String summary,
        String pendingTasks,
        String completedTasks,
        String importantNotes,
        HandoverPriority priority
) {}
