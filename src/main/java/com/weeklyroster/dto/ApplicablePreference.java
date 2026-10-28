package com.weeklyroster.dto;

import com.weeklyroster.entity.ShiftType;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
        boolean isApproved,
        Map<LocalDate, ShiftType> dateSpecificShifts,
        Map<DayOfWeek, ShiftType> recurringWeeklyShifts,
        Map<DayOfWeek, Set<ShiftType>> recurringAvoidShifts
) {
    public ApplicablePreference(
            Long preferenceId,
            Long employeeId,
            Set<ShiftType> preferredShifts,
            List<ShiftType> preferredShiftOrder,
            Set<ShiftType> avoidShifts,
            Set<DayOfWeek> preferredOffDays,
            Set<DayOfWeek> preferredWorkingDays,
            String temporaryRestrictions,
            boolean isApproved,
            Map<LocalDate, ShiftType> dateSpecificShifts,
            Map<DayOfWeek, ShiftType> recurringWeeklyShifts) {
        this(
                preferenceId,
                employeeId,
                preferredShifts,
                preferredShiftOrder,
                avoidShifts,
                preferredOffDays,
                preferredWorkingDays,
                temporaryRestrictions,
                isApproved,
                dateSpecificShifts != null ? dateSpecificShifts : Collections.emptyMap(),
                recurringWeeklyShifts != null ? recurringWeeklyShifts : Collections.emptyMap(),
                Collections.emptyMap()
        );
    }

    public ApplicablePreference(
            Long preferenceId,
            Long employeeId,
            Set<ShiftType> preferredShifts,
            List<ShiftType> preferredShiftOrder,
            Set<ShiftType> avoidShifts,
            Set<DayOfWeek> preferredOffDays,
            Set<DayOfWeek> preferredWorkingDays,
            String temporaryRestrictions,
            boolean isApproved,
            Map<LocalDate, ShiftType> dateSpecificShifts) {
        this(
                preferenceId,
                employeeId,
                preferredShifts,
                preferredShiftOrder,
                avoidShifts,
                preferredOffDays,
                preferredWorkingDays,
                temporaryRestrictions,
                isApproved,
                dateSpecificShifts != null ? dateSpecificShifts : Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap()
        );
    }

    public ApplicablePreference(
            Long preferenceId,
            Long employeeId,
            Set<ShiftType> preferredShifts,
            List<ShiftType> preferredShiftOrder,
            Set<ShiftType> avoidShifts,
            Set<DayOfWeek> preferredOffDays,
            Set<DayOfWeek> preferredWorkingDays,
            String temporaryRestrictions,
            boolean isApproved) {
        this(
                preferenceId,
                employeeId,
                preferredShifts,
                preferredShiftOrder,
                avoidShifts,
                preferredOffDays,
                preferredWorkingDays,
                temporaryRestrictions,
                isApproved,
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap()
        );
    }

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
                isApproved,
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap()
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
                false,
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap()
        );
    }

    public static ApplicablePreference withDateShifts(Long employeeId, Map<LocalDate, ShiftType> dateShifts) {
        boolean hasDate = dateShifts != null && !dateShifts.isEmpty();
        return new ApplicablePreference(
                null,
                employeeId,
                Collections.emptySet(),
                Collections.emptyList(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                null,
                hasDate,
                dateShifts != null ? dateShifts : Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap()
        );
    }

    public static ApplicablePreference withDateAndRecurringShifts(
            Long employeeId,
            Map<LocalDate, ShiftType> dateShifts,
            Map<DayOfWeek, ShiftType> recurringShifts) {
        return withDateAndRecurringShifts(employeeId, dateShifts, recurringShifts, Collections.emptyMap());
    }

    public static ApplicablePreference withDateAndRecurringShifts(
            Long employeeId,
            Map<LocalDate, ShiftType> dateShifts,
            Map<DayOfWeek, ShiftType> recurringShifts,
            Map<DayOfWeek, Set<ShiftType>> recurringAvoidShifts) {
        boolean hasAny = (dateShifts != null && !dateShifts.isEmpty())
                || (recurringShifts != null && !recurringShifts.isEmpty())
                || (recurringAvoidShifts != null && !recurringAvoidShifts.isEmpty());
        return new ApplicablePreference(
                null,
                employeeId,
                Collections.emptySet(),
                Collections.emptyList(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                null,
                hasAny,
                dateShifts != null ? dateShifts : Collections.emptyMap(),
                recurringShifts != null ? recurringShifts : Collections.emptyMap(),
                recurringAvoidShifts != null ? recurringAvoidShifts : Collections.emptyMap()
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

    public boolean hasRecurringAvoidShift(DayOfWeek dayOfWeek) {
        return recurringAvoidShifts != null && dayOfWeek != null && recurringAvoidShifts.containsKey(dayOfWeek)
                && !recurringAvoidShifts.get(dayOfWeek).isEmpty();
    }

    public Set<ShiftType> getRecurringAvoidShifts(DayOfWeek dayOfWeek) {
        if (recurringAvoidShifts == null || dayOfWeek == null) return Collections.emptySet();
        return recurringAvoidShifts.getOrDefault(dayOfWeek, Collections.emptySet());
    }

    public boolean isShiftAvoidedOnDate(ShiftType shiftType, LocalDate date) {
        if (shiftType == null) return false;
        // BATCH 124 Authoritative Precedence:
        // Date-specific preference takes precedence over recurring negative preferences
        if (date != null && hasDateSpecificShift(date)) {
            return getDateSpecificShift(date) != shiftType && isShiftAvoided(shiftType);
        }
        if (isShiftAvoided(shiftType)) return true;
        if (date != null && hasRecurringAvoidShift(date.getDayOfWeek())) {
            return getRecurringAvoidShifts(date.getDayOfWeek()).contains(shiftType);
        }
        return false;
    }

    public boolean isDayPreferredOff(DayOfWeek dayOfWeek) {
        return preferredOffDays != null && preferredOffDays.contains(dayOfWeek);
    }

    public boolean isDayPreferredWorking(DayOfWeek dayOfWeek) {
        return preferredWorkingDays != null && preferredWorkingDays.contains(dayOfWeek);
    }

    public boolean hasDateSpecificShift(LocalDate date) {
        return dateSpecificShifts != null && date != null && dateSpecificShifts.containsKey(date);
    }

    public ShiftType getDateSpecificShift(LocalDate date) {
        if (dateSpecificShifts == null || date == null) return null;
        return dateSpecificShifts.get(date);
    }

    public boolean hasRecurringShift(DayOfWeek dayOfWeek) {
        return recurringWeeklyShifts != null && dayOfWeek != null && recurringWeeklyShifts.containsKey(dayOfWeek);
    }

    public ShiftType getRecurringShift(DayOfWeek dayOfWeek) {
        if (recurringWeeklyShifts == null || dayOfWeek == null) return null;
        return recurringWeeklyShifts.get(dayOfWeek);
    }

    /**
     * BATCH 113 & BATCH 124: Authoritative Target Shift for Date.
     * Priority: Date-Specific takes precedence over Recurring Weekly.
     */
    public ShiftType getTargetShiftForDate(LocalDate date) {
        if (date == null) return null;
        if (hasDateSpecificShift(date)) {
            return getDateSpecificShift(date);
        }
        if (hasRecurringShift(date.getDayOfWeek())) {
            return getRecurringShift(date.getDayOfWeek());
        }
        return null;
    }

    public boolean hasTargetShiftForDate(LocalDate date) {
        return getTargetShiftForDate(date) != null;
    }

    public boolean isTargetShift(ShiftType shiftType, LocalDate date) {
        ShiftType target = getTargetShiftForDate(date);
        return target != null && target == shiftType;
    }

    public boolean isShiftTargetOrNeutral(ShiftType shiftType, LocalDate date) {
        if (isShiftAvoidedOnDate(shiftType, date)) return false;
        ShiftType target = getTargetShiftForDate(date);
        return target == null || target == shiftType;
    }
}
