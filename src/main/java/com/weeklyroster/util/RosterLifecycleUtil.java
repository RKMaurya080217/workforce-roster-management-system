package com.weeklyroster.util;

import com.weeklyroster.entity.GenerationMode;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.RosterStatus;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

public final class RosterLifecycleUtil {

    public static final String CLASSIFICATION_CURRENT = "CURRENT";
    public static final String CLASSIFICATION_UPCOMING = "UPCOMING";
    public static final String CLASSIFICATION_PAST = "PAST";
    public static final String CLASSIFICATION_FUTURE = "FUTURE";

    public static final String SOURCE_AUTOMATIC = "AUTOMATIC";
    public static final String SOURCE_MANUAL_ADMIN = "MANUAL_ADMIN";

    private RosterLifecycleUtil() {}

    /**
     * Determines cycle classification relative to baseDate (or Asia/Kolkata today).
     */
    public static String classifyCycle(LocalDate startDate, LocalDate endDate, LocalDate baseDate) {
        if (startDate == null || endDate == null) {
            return CLASSIFICATION_FUTURE;
        }
        if (baseDate == null) {
            baseDate = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        }

        LocalDate currentMonday = baseDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate currentSunday = currentMonday.plusDays(6);
        LocalDate upcomingMonday = currentMonday.plusDays(7);
        LocalDate upcomingSunday = upcomingMonday.plusDays(6);

        if (startDate.equals(currentMonday) && endDate.equals(currentSunday)) {
            return CLASSIFICATION_CURRENT;
        } else if (startDate.equals(upcomingMonday) && endDate.equals(upcomingSunday)) {
            return CLASSIFICATION_UPCOMING;
        } else if (endDate.isBefore(currentMonday)) {
            return CLASSIFICATION_PAST;
        } else {
            return CLASSIFICATION_FUTURE;
        }
    }

    public static String classifyCycle(LocalDate startDate, LocalDate endDate) {
        return classifyCycle(startDate, endDate, null);
    }

    /**
     * Maps GenerationMode to authoritative source string.
     */
    public static String resolveSource(GenerationMode mode) {
        if (mode == GenerationMode.AUTOMATIC) {
            return SOURCE_AUTOMATIC;
        }
        return SOURCE_MANUAL_ADMIN;
    }

    /**
     * Evaluates dynamic status for display while respecting persistent lifecycle status.
     * E.g. a PUBLISHED roster becomes ACTIVE when current date is inside its start/end range,
     * and COMPLETED when current date has passed its end date.
     */
    public static RosterStatus resolveEffectiveStatus(RosterCycle cycle, LocalDate baseDate) {
        if (cycle == null || cycle.getStatus() == null) {
            return RosterStatus.GENERATED;
        }
        if (baseDate == null) {
            baseDate = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        }

        RosterStatus persistent = cycle.getStatus();
        if (persistent == RosterStatus.PUBLISHED) {
            if (!baseDate.isBefore(cycle.getStartDate()) && !baseDate.isAfter(cycle.getEndDate())) {
                return RosterStatus.ACTIVE;
            } else if (baseDate.isAfter(cycle.getEndDate())) {
                return RosterStatus.COMPLETED;
            }
        }
        return persistent;
    }

    /**
     * Deletability check: Only DRAFT or un-published GENERATED cycles are deletable.
     * PUBLISHED, ACTIVE, LOCKED, and COMPLETED cycles are protected from deletion.
     */
    public static boolean isDeletable(RosterCycle cycle) {
        if (cycle == null || cycle.getStatus() == null) return true;
        RosterStatus status = cycle.getStatus();
        return status == RosterStatus.DRAFT || status == RosterStatus.GENERATED;
    }
}
