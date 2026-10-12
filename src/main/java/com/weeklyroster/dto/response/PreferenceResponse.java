package com.weeklyroster.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.weeklyroster.entity.PreferenceStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public record PreferenceResponse(
        Long id,
        Long employeeId,
        String employeeCode,
        String employeeName,
        String preferredShiftTypes,
        String preferredOffDays,
        String preferredWorkingDays,
        String avoidShiftTypes,
        String temporaryRestrictions,
        String remarks,
        PreferenceStatus status,
        String adminRemarks,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        LocalDateTime createdAt,
        LocalDateTime reviewedAt,
        String reviewedBy,
        List<String> preferredShiftList,
        List<ShiftPriorityItem> preferredShiftPriorities
) {
    public PreferenceResponse(
            Long id,
            Long employeeId,
            String employeeCode,
            String employeeName,
            String preferredShiftTypes,
            String preferredOffDays,
            String preferredWorkingDays,
            String avoidShiftTypes,
            String temporaryRestrictions,
            String remarks,
            PreferenceStatus status,
            String adminRemarks,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            LocalDateTime createdAt,
            LocalDateTime reviewedAt,
            String reviewedBy) {
        this(
                id, employeeId, employeeCode, employeeName,
                preferredShiftTypes, preferredOffDays, preferredWorkingDays, avoidShiftTypes,
                temporaryRestrictions, remarks, status, adminRemarks,
                effectiveFrom, effectiveTo, createdAt, reviewedAt, reviewedBy,
                parseShiftList(preferredShiftTypes),
                parseShiftPriorities(preferredShiftTypes)
        );
    }

    private static List<String> parseShiftList(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();
        return Arrays.stream(text.split("[,;\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static List<ShiftPriorityItem> parseShiftPriorities(String text) {
        List<String> list = parseShiftList(text);
        List<ShiftPriorityItem> priorities = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            priorities.add(new ShiftPriorityItem(i + 1, list.get(i)));
        }
        return Collections.unmodifiableList(priorities);
    }

    @JsonProperty("preferredShifts")
    public String preferredShifts() {
        return preferredShiftTypes;
    }

    @JsonProperty("avoidShifts")
    public String avoidShifts() {
        return avoidShiftTypes;
    }

    @JsonProperty("temporaryConstraints")
    public String temporaryConstraints() {
        return temporaryRestrictions;
    }

    public record ShiftPriorityItem(int priority, String shift) {}
}
