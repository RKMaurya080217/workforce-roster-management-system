package com.weeklyroster.dto.sync;

import java.util.List;

public record EmployeeSyncDiffItem(
        String employeeCode,
        String action, // ADD, UPDATE, UNCHANGED, PRESERVE_DATABASE_RECORD, CONFLICT
        List<SyncFieldDiff> changes,
        String errorMessage
) {
    public static EmployeeSyncDiffItem ofAdd(String code, List<SyncFieldDiff> changes) {
        return new EmployeeSyncDiffItem(code, "ADD", changes, null);
    }

    public static EmployeeSyncDiffItem ofUpdate(String code, List<SyncFieldDiff> changes) {
        return new EmployeeSyncDiffItem(code, "UPDATE", changes, null);
    }

    public static EmployeeSyncDiffItem ofUnchanged(String code) {
        return new EmployeeSyncDiffItem(code, "UNCHANGED", List.of(), null);
    }

    public static EmployeeSyncDiffItem ofPreserve(String code) {
        return new EmployeeSyncDiffItem(code, "PRESERVE_DATABASE_RECORD", List.of(), null);
    }

    public static EmployeeSyncDiffItem ofConflict(String code, String errorMessage) {
        return new EmployeeSyncDiffItem(code, "CONFLICT", List.of(), errorMessage);
    }
}
