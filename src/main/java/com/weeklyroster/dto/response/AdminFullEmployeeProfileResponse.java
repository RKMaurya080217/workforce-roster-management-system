package com.weeklyroster.dto.response;

import com.weeklyroster.entity.Gender;
import java.time.LocalDate;
import java.util.List;

public record AdminFullEmployeeProfileResponse(
        Long id,
        String employeeCode,
        String firstName,
        String lastName,
        String email,
        String contactNumber,
        Gender gender,
        String username,
        String role,
        LocalDate joiningDate,
        LocalDate resignationDate,
        boolean active,
        String shiftPreference,
        List<RecurringShiftPreferenceResponse> recurringPreferences,
        List<DateShiftPreferenceResponse> dateSpecificPreferences,
        List<LeaveResponse> recentLeaves,
        long totalAssignments,
        long totalNightAssignments,
        long totalWeeklyOffs,
        String department,
        boolean developmentTeam,
        Integer maxNightsPerWeek,
        Long pairedEmployeeId
) {
    public AdminFullEmployeeProfileResponse(
            Long id,
            String employeeCode,
            String firstName,
            String lastName,
            String email,
            String contactNumber,
            Gender gender,
            String username,
            String role,
            LocalDate joiningDate,
            LocalDate resignationDate,
            boolean active,
            String shiftPreference,
            List<RecurringShiftPreferenceResponse> recurringPreferences,
            List<DateShiftPreferenceResponse> dateSpecificPreferences,
            List<LeaveResponse> recentLeaves,
            long totalAssignments,
            long totalNightAssignments,
            long totalWeeklyOffs
    ) {
        this(id, employeeCode, firstName, lastName, email, contactNumber, gender, username, role,
                joiningDate, resignationDate, active, shiftPreference, recurringPreferences,
                dateSpecificPreferences, recentLeaves, totalAssignments, totalNightAssignments,
                totalWeeklyOffs, "OPERATIONS", false, null, null);
    }
}
