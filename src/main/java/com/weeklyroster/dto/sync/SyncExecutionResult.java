package com.weeklyroster.dto.sync;

import java.time.LocalDateTime;
import java.util.List;

public record SyncExecutionResult(
        boolean success,
        int recordsInserted,
        int recordsUpdated,
        int recordsPreserved,
        List<String> errors,
        LocalDateTime timestamp,
        String csvHash,
        String databaseHash
) {}
