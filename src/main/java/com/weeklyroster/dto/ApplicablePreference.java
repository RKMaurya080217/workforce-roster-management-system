package com.weeklyroster.dto;

import com.weeklyroster.entity.ShiftType;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public record ApplicablePreference(
        Long preferenceId,
        Long employeeId,
        Set<ShiftType> preferredShifts,
        List<ShiftType> preferredShiftOrder,
        Set<ShiftType> avoidShifts,
        Set<DayOfWeek> preferredOffDays,
        Set<DayOfWeek> preferredWorkingDays,
        String temporaryRestrictions,
        boolean isApproved
) {
    public ApplicablePreference(
            Long preferenceId,
            Long employeeId,
            Set<ShiftType> preferredShifts,
            Set<ShiftType> avoidShifts,
            Set<DayOfWeek> preferredOffDays,
            Set<DayOfWeek> preferredWorkingDays,
            String temporaryRestrictions,
            boolean isApproved) {
        this(
                preferenceId,
                employeeId,
                preferredShifts,
                preferredShifts != null ? new ArrayList<>(preferredShifts) : Collections.emptyList(),
                avoidShifts,
                preferredOffDays,
                preferredWorkingDays,
                temporaryRestrictions,
                isApproved
        );
    }

    public static ApplicablePreference none(Long employeeId) {
        return new ApplicablePreference(
                null,
                employeeId,
                Collections.emptySet(),
                Collections.emptyList(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                null,
                false
        );
    }

    public boolean hasPreferredShifts() {
        return preferredShifts != null && !preferredShifts.isEmpty();
    }

    public boolean hasAvoidShifts() {
        return avoidShifts != null && !avoidShifts.isEmpty();
    }

    public boolean hasPreferredOffDays() {
        return preferredOffDays != null && !preferredOffDays.isEmpty();
    }

    public boolean hasPreferredWorkingDays() {
        return preferredWorkingDays != null && !preferredWorkingDays.isEmpty();
    }

    public boolean isShiftPreferred(ShiftType shiftType) {
        return hasPreferredShifts() && preferredShifts.contains(shiftType);
    }

    public int getShiftPreferenceRank(ShiftType shiftType) {
        if (preferredShiftOrder == null) return -1;
        return preferredShiftOrder.indexOf(shiftType);
    }

    public boolean isShiftAvoided(ShiftType shiftType) {
        return avoidShifts != null && avoidShifts.contains(shiftType);
    }

    public boolean isDayPreferredOff(DayOfWeek dayOfWeek) {
        return preferredOffDays != null && preferredOffDays.contains(dayOfWeek);
    }

    public boolean isDayPreferredWorking(DayOfWeek dayOfWeek) {
        return preferredWorkingDays != null && preferredWorkingDays.contains(dayOfWeek);
    }
}
