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

    public PreferenceResponse submitPreference(Long employeeId, PreferenceSubmitRequest req, String username) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeId));

        EmployeePreference pref = new EmployeePreference();
        pref.setEmployee(employee);
        pref.setPreferredShiftTypes(req.preferredShiftTypes() != null ? req.preferredShiftTypes().trim() : null);
        pref.setPreferredOffDays(req.preferredOffDays() != null ? req.preferredOffDays().trim() : null);
        pref.setPreferredWorkingDays(req.preferredWorkingDays() != null ? req.preferredWorkingDays().trim() : null);
        pref.setAvoidShiftTypes(req.avoidShiftTypes() != null ? req.avoidShiftTypes().trim() : null);
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
