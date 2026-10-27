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
        long totalWeeklyOffs
) {}
