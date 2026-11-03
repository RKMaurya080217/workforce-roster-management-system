package com.weeklyroster.dto.sync;

public record SyncFieldDiff(
        String fieldName,
        String oldValue,
        String newValue
) {}
