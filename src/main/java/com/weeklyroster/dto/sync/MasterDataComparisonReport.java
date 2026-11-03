package com.weeklyroster.dto.sync;

import java.util.List;

public record MasterDataComparisonReport(
        String syncStatus, // IN_SYNC, OUT_OF_SYNC
        int databaseCount,
        int csvCount,
        int matchingRecords,
        int mismatchedRecords,
        int missingInDatabase,
        int missingInCsv,
        String databaseHash,
        String csvHash,
        List<EmployeeSyncDiffItem> diffs
) {}
