package com.weeklyroster.dto.response;

public record UndoOperationResponse(
        Long operationId,
        String operationType,
        String description,
        int restoredAssignmentsCount,
        ManualRosterMatrixResponse matrix
) {}
