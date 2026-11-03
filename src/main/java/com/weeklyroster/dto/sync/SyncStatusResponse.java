package com.weeklyroster.dto.sync;

import java.time.LocalDateTime;

public record SyncStatusResponse(
        String syncStatus, // IN_SYNC, OUT_OF_SYNC, UNKNOWN
        LocalDateTime lastSyncTimestamp,
        int databaseRecordCount,
        int csvRecordCount,
        String databaseHash,
        String csvHash,
        String canonicalFilePath
) {}
