package com.weeklyroster.dto.sync;

import java.util.List;

public record SyncPreviewResponse(
        boolean canApply,
        int totalCsvRecords,
        int addedCount,
        int updatedCount,
        int unchangedCount,
        int missingFromCsvCount,
        int conflictCount,
        List<EmployeeSyncDiffItem> diffs,
        List<String> errors
) {}
