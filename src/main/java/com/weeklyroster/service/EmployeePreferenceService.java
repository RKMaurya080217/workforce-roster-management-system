package com.weeklyroster.service;

import com.weeklyroster.dto.request.PreferenceDecisionRequest;
import com.weeklyroster.dto.request.PreferenceSubmitRequest;
import com.weeklyroster.dto.response.PreferenceResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.EmployeePreferenceRepository;
import com.weeklyroster.repository.EmployeeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class EmployeePreferenceService {

    private final EmployeePreferenceRepository preferenceRepository;
    private final EmployeeRepository employeeRepository;
    private final NotificationService notificationService;
    private final EmployeeActivityLogService activityLogService;
    private final AuditService auditService;

    public EmployeePreferenceService(EmployeePreferenceRepository preferenceRepository,
                                   EmployeeRepository employeeRepository,
                                   NotificationService notificationService,
                                   EmployeeActivityLogService activityLogService,
                                   AuditService auditService) {
        this.preferenceRepository = preferenceRepository;
        this.employeeRepository = employeeRepository;
        this.notificationService = notificationService;
        this.activityLogService = activityLogService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<PreferenceResponse> getMyPreferences(Long employeeId) {
        return preferenceRepository.findByEmployeeIdOrderByCreatedAtDesc(employeeId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<PreferenceResponse> getMyActiveApprovedPreference(Long employeeId) {
        return preferenceRepository.findTopByEmployeeIdAndStatusOrderByCreatedAtDesc(employeeId, PreferenceStatus.APPROVED)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public List<PreferenceResponse> getPendingPreferences() {
        return preferenceRepository.findByStatusOrderByCreatedAtDesc(PreferenceStatus.PENDING).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PreferenceResponse> getAllPreferences() {
        return preferenceRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    private static final java.util.Set<String> VALID_SHIFTS = java.util.Set.of("MORNING", "GENERAL", "EVENING", "NIGHT", "OFF");
    private static final java.util.List<String> ORDERED_DAYS = java.util.List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
    private static final java.util.Set<String> VALID_DAYS = new java.util.HashSet<>(ORDERED_DAYS);

    private String normalizeAndValidateShifts(String input, String fieldName) {
        if (input == null || input.isBlank()) return null;
        String[] parts = input.split("[,;\\s]+");
        java.util.Set<String> clean = new java.util.LinkedHashSet<>();
        for (String p : parts) {
            String trimmed = p.trim().toUpperCase();
            if (!trimmed.isEmpty()) {
                if (trimmed.equals("AND") || trimmed.equals("&")) continue;
                if (!VALID_SHIFTS.contains(trimmed)) {
                    // Check if 3-letter prefix match (e.g. MORN -> MORNING, GEN -> GENERAL, EVE -> EVENING)
                    String matched = null;
                    for (String s : VALID_SHIFTS) {
                        if (s.startsWith(trimmed) || (trimmed.length() >= 3 && trimmed.startsWith(s.substring(0, 3)))) {
                            matched = s;
                            break;
                        }
                    }
                    if (matched != null) {
                        clean.add(matched);
                    } else {
                        throw new BusinessException("Invalid shift type '" + p + "' in " + fieldName + ". Supported shifts: MORNING, GENERAL, EVENING, NIGHT.");
                    }
                } else {
                    clean.add(trimmed);
                }
            }
        }
        return clean.isEmpty() ? null : String.join(", ", clean);
    }

    private String normalizeAndValidateDays(String input, String fieldName) {
        if (input == null || input.isBlank()) return null;
        String cleanInput = input.toUpperCase().replaceAll("\\s*-\\s*", " TO ");
        String[] tokens = cleanInput.split("[,;\\s]+");
        java.util.Set<String> resultDays = new java.util.LinkedHashSet<>();

        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i].trim();
            if (token.isEmpty()) continue;

            if (token.equals("TO") || token.equals("THRU") || token.equals("THROUGH") || token.equals("AND") || token.equals("&")) {
                if (i > 0 && i + 1 < tokens.length) {
                    String prev = tokens[i - 1].trim();
                    String next = tokens[i + 1].trim();
                    int startIdx = findDayIndex(prev);
                    int endIdx = findDayIndex(next);
                    if (startIdx != -1 && endIdx != -1) {
                        if (startIdx <= endIdx) {
                            for (int d = startIdx; d <= endIdx; d++) {
                                resultDays.add(ORDERED_DAYS.get(d));
                            }
                        } else {
                            for (int d = startIdx; d < ORDERED_DAYS.size(); d++) {
                                resultDays.add(ORDERED_DAYS.get(d));
                            }
                            for (int d = 0; d <= endIdx; d++) {
                                resultDays.add(ORDERED_DAYS.get(d));
                            }
                        }
                    }
                }
                continue;
            }

            if (!VALID_DAYS.contains(token)) {
                int idx = findDayIndex(token);
                if (idx != -1) {
                    resultDays.add(ORDERED_DAYS.get(idx));
                } else {
                    throw new BusinessException("Invalid day '" + token + "' in " + fieldName + ". Supported days: MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY.");
                }
            } else {
                resultDays.add(token);
            }
        }

        return resultDays.isEmpty() ? null : String.join(", ", resultDays);
    }

    private int findDayIndex(String token) {
        if (token == null || token.isBlank()) return -1;
        String upper = token.trim().toUpperCase();
        for (int i = 0; i < ORDERED_DAYS.size(); i++) {
            String d = ORDERED_DAYS.get(i);
            if (d.equals(upper) || d.startsWith(upper) || (upper.length() >= 3 && upper.startsWith(d.substring(0, 3)))) {
                return i;
            }
        }
        return -1;
    }

    private java.util.Set<String> parseSet(String input) {
        if (input == null || input.isBlank()) return java.util.Set.of();
        String[] parts = input.split("[,;\\s]+");
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String p : parts) {
            String t = p.trim().toUpperCase();
            if (!t.isEmpty()) {
                set.add(t);
            }
        }
        return set;
    }

    private void validateConflicts(String preferredShifts, String avoidShifts, String preferredOffDays, String preferredWorkDays) {
        java.util.Set<String> prefS = parseSet(preferredShifts);
        java.util.Set<String> avoidS = parseSet(avoidShifts);
        java.util.Set<String> shiftConflict = new java.util.HashSet<>(prefS);
        shiftConflict.retainAll(avoidS);
        if (!shiftConflict.isEmpty()) {
            throw new BusinessException("Shift conflict: " + String.join(", ", shiftConflict) + " cannot be both preferred and avoided.");
        }

        java.util.Set<String> offD = parseSet(preferredOffDays);
        java.util.Set<String> workD = parseSet(preferredWorkDays);
        java.util.Set<String> dayConflict = new java.util.HashSet<>(offD);
        dayConflict.retainAll(workD);
        if (!dayConflict.isEmpty()) {
            throw new BusinessException("Day conflict: " + String.join(", ", dayConflict) + " cannot be selected as both a preferred OFF day and preferred working day.");
        }
    }

    public PreferenceResponse submitPreference(Long employeeId, PreferenceSubmitRequest req, String username) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeId));

        String prefShifts = normalizeAndValidateShifts(req.preferredShiftTypes(), "Preferred Shift Types");
        String avoidShifts = normalizeAndValidateShifts(req.avoidShiftTypes(), "Avoid Shift Types");
        String prefOff = normalizeAndValidateDays(req.preferredOffDays(), "Preferred OFF Days");
        String prefWork = normalizeAndValidateDays(req.preferredWorkingDays(), "Preferred Working Days");

        validateConflicts(prefShifts, avoidShifts, prefOff, prefWork);

        EmployeePreference pref = new EmployeePreference();
        pref.setEmployee(employee);
        pref.setPreferredShiftTypes(prefShifts);
        pref.setPreferredOffDays(prefOff);
        pref.setPreferredWorkingDays(prefWork);
        pref.setAvoidShiftTypes(avoidShifts);
        pref.setTemporaryRestrictions(req.temporaryRestrictions() != null ? req.temporaryRestrictions().trim() : null);
        pref.setRemarks(req.remarks() != null ? req.remarks().trim() : null);
        pref.setEffectiveFrom(req.effectiveFrom());
        pref.setEffectiveTo(req.effectiveTo());
        pref.setStatus(PreferenceStatus.PENDING);
        pref.setCreatedAt(LocalDateTime.now());

        EmployeePreference saved = preferenceRepository.save(pref);

        activityLogService.logActivity(employee.getId(), username, ActivityCategory.PREFERENCE,
                "PREFERENCE_SUBMITTED", ActivityStatus.PENDING,
                "Submitted shift preference request #" + saved.getId());

        auditService.log(AuditAction.PREFERENCE_SUBMITTED, "EMPLOYEE_PREFERENCE", saved.getId(), null,
                employee.getId(), employee.getFirstName() + " " + employee.getLastName(),
                null, "PENDING", "Submitted shift preferences", "MANUAL");

        notificationService.createNotification("admin", null,
                "New Shift Preference Request",
                employee.getFirstName() + " " + employee.getLastName() + " (" + employee.getEmployeeCode() + ") submitted shift preferences.",
                NotificationType.PREFERENCE_SUBMITTED, "preferences", saved.getId());

        return toResponse(saved);
    }

    public PreferenceResponse updatePreference(Long employeeId, Long preferenceId, PreferenceSubmitRequest req, String username) {
        EmployeePreference pref = preferenceRepository.findById(preferenceId)
                .orElseThrow(() -> new ResourceNotFoundException("Preference not found with id: " + preferenceId));

        if (!pref.getEmployee().getId().equals(employeeId)) {
            throw new BusinessException("Access denied: You can only update your own preferences.");
        }

        String prefShifts = normalizeAndValidateShifts(req.preferredShiftTypes(), "Preferred Shift Types");
        String avoidShifts = normalizeAndValidateShifts(req.avoidShiftTypes(), "Avoid Shift Types");
        String prefOff = normalizeAndValidateDays(req.preferredOffDays(), "Preferred OFF Days");
        String prefWork = normalizeAndValidateDays(req.preferredWorkingDays(), "Preferred Working Days");

        validateConflicts(prefShifts, avoidShifts, prefOff, prefWork);

        pref.setPreferredShiftTypes(prefShifts);
        pref.setPreferredOffDays(prefOff);
        pref.setPreferredWorkingDays(prefWork);
        pref.setAvoidShiftTypes(avoidShifts);
        pref.setTemporaryRestrictions(req.temporaryRestrictions() != null ? req.temporaryRestrictions().trim() : null);
        pref.setRemarks(req.remarks() != null ? req.remarks().trim() : null);
        pref.setEffectiveFrom(req.effectiveFrom());
        pref.setEffectiveTo(req.effectiveTo());
        pref.setStatus(PreferenceStatus.PENDING);

        EmployeePreference saved = preferenceRepository.save(pref);

        activityLogService.logActivity(employeeId, username, ActivityCategory.PREFERENCE,
                "PREFERENCE_UPDATED", ActivityStatus.PENDING,
                "Updated shift preference request #" + saved.getId());

        auditService.log(AuditAction.PREFERENCE_SUBMITTED, "EMPLOYEE_PREFERENCE", saved.getId(), null,
                employeeId, pref.getEmployee().getFirstName() + " " + pref.getEmployee().getLastName(),
                null, "UPDATED", "Updated shift preferences", "MANUAL");

        return toResponse(saved);
    }

    public void deletePreference(Long employeeId, Long preferenceId, String username) {
        EmployeePreference pref = preferenceRepository.findById(preferenceId)
                .orElseThrow(() -> new ResourceNotFoundException("Preference not found with id: " + preferenceId));

        if (!pref.getEmployee().getId().equals(employeeId)) {
            throw new BusinessException("Access denied: You can only clear/reset your own preferences.");
        }

        preferenceRepository.delete(pref);

        activityLogService.logActivity(employeeId, username, ActivityCategory.PREFERENCE,
                "PREFERENCE_CLEARED", ActivityStatus.SUCCESS,
                "Cleared shift preference #" + preferenceId);

        auditService.log(AuditAction.PREFERENCE_SUBMITTED, "EMPLOYEE_PREFERENCE", preferenceId, null,
                employeeId, pref.getEmployee().getFirstName() + " " + pref.getEmployee().getLastName(),
                "ACTIVE", "CLEARED", "Cleared shift preferences", "MANUAL");
    }

    public PreferenceResponse decidePreference(Long preferenceId, PreferenceDecisionRequest req, String adminUsername) {
        EmployeePreference pref = preferenceRepository.findById(preferenceId)
                .orElseThrow(() -> new ResourceNotFoundException("Preference not found with id: " + preferenceId));

        if (pref.getStatus() != PreferenceStatus.PENDING && req.status() == pref.getStatus()) {
            throw new BusinessException("Preference request is already in status: " + pref.getStatus());
        }

        PreferenceStatus oldStatus = pref.getStatus();
        pref.setStatus(req.status());
        pref.setAdminRemarks(req.adminRemarks() != null ? req.adminRemarks().trim() : null);
        pref.setReviewedAt(LocalDateTime.now());
        pref.setReviewedBy(adminUsername);

        EmployeePreference saved = preferenceRepository.save(pref);
        Employee employee = saved.getEmployee();

        AuditAction auditAction = req.status() == PreferenceStatus.APPROVED ? AuditAction.PREFERENCE_APPROVED : AuditAction.PREFERENCE_REJECTED;
        auditService.log(auditAction, "EMPLOYEE_PREFERENCE", saved.getId(), null,
                employee.getId(), employee.getFirstName() + " " + employee.getLastName(),
                oldStatus.name(), req.status().name(),
                "Admin " + req.status().name().toLowerCase() + " preference #" + saved.getId() + (req.adminRemarks() != null ? " - " + req.adminRemarks() : ""),
                "MANUAL");

        activityLogService.logActivity(employee.getId(), employee.getEmployeeCode().toLowerCase(), ActivityCategory.PREFERENCE,
                "PREFERENCE_" + req.status().name(), ActivityStatus.SUCCESS,
                "Shift preference request #" + saved.getId() + " was " + req.status().name().toLowerCase() + " by Admin");

        String notifMsg = req.status() == PreferenceStatus.APPROVED
                ? "Your shift preference request has been APPROVED by the administrator."
                : "Your shift preference request was REJECTED." + (req.adminRemarks() != null ? " Reason: " + req.adminRemarks() : "");

        notificationService.createNotification(employee.getEmployeeCode().toLowerCase(), employee.getId(),
                "Shift Preference " + req.status().name(),
                notifMsg, NotificationType.PREFERENCE_DECISION, "preferences", saved.getId());

        return toResponse(saved);
    }

    private PreferenceResponse toResponse(EmployeePreference p) {
        Employee e = p.getEmployee();
        return new PreferenceResponse(
                p.getId(),
                e != null ? e.getId() : null,
                e != null ? e.getEmployeeCode() : null,
                e != null ? (e.getFirstName() + " " + (e.getLastName() != null ? e.getLastName() : "")).trim() : null,
                p.getPreferredShiftTypes(),
                p.getPreferredOffDays(),
                p.getPreferredWorkingDays(),
                p.getAvoidShiftTypes(),
                p.getTemporaryRestrictions(),
                p.getRemarks(),
                p.getStatus(),
                p.getAdminRemarks(),
                p.getEffectiveFrom(),
                p.getEffectiveTo(),
                p.getCreatedAt(),
                p.getReviewedAt(),
                p.getReviewedBy()
        );
    }
}
