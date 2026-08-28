package com.weeklyroster.dto.response;

import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.ShiftType;
import java.time.LocalDate;
import java.util.List;

public record RosterChangeImpactResponse(
        Long assignmentId,
        Long cycleId,
        Long employeeId,
        String employeeCode,
        String employeeName,
        Gender employeeGender,
        LocalDate date,
        String dayOfWeek,
        ShiftType currentShiftType,
        ShiftType proposedShiftType,
        boolean currentWeeklyOff,
        boolean proposedWeeklyOff,
        Double currentHealthScore,
        Double projectedHealthScore,
        String impactStatus,          // "SAFE", "WARNING", "BLOCKED"
        String impactBadgeLabel,       // "🟢 SAFE TO APPLY", "🟠 WARNING — OPERATIONAL IMPACT", "🔴 BLOCKED — HARD CONSTRAINT VIOLATION"
        boolean canApply,
        boolean requiresAdminConfirmation,
        String coverageImpact,         // "Safe", "Warning", "Blocked"
        String coverageMessage,
        String restImpact,             // "Safe", "Blocked"
        String restMessage,
        String preferenceImpact,       // "Improved", "Neutral", "Avoided", "Conflict"
        String preferenceMessage,
        String continuityImpact,       // "Improved", "Neutral", "Degraded"
        String continuityMessage,
        String workloadImpact,         // "Neutral", "Increased", "Decreased"
        String workloadMessage,
        String nightImpact,            // "Safe", "Blocked", "Unchanged"
        String nightMessage,
        String genderImpact,           // "Safe", "Blocked"
        String genderMessage,
        String teamImpactMessage,
        List<String> blockers,
        List<String> warnings,
        List<String> positivePoints
) {}
