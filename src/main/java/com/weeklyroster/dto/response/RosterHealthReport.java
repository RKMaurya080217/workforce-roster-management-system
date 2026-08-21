package com.weeklyroster.dto.response;

import com.weeklyroster.entity.RosterStatus;
import java.time.LocalDate;
import java.util.List;

public record RosterHealthReport(
        Long cycleId,
        LocalDate startDate,
        LocalDate endDate,
        RosterStatus status,
        boolean readyToPublish,
        String summaryStatus,
        String coverageCheck,
        String restRulesCheck,
        String nightLimitCheck,
        String genderRulesCheck,
        String leaveRulesCheck,
        String overridesCheck,
        String duplicatesCheck,
        String weeklyOffCheck,
        String shiftContinuityCheck,
        int criticalConflictsCount,
        int highConflictsCount,
        int mediumConflictsCount,
        int lowConflictsCount,
        int infoCount,
        List<ConflictItem> conflicts
) {}
