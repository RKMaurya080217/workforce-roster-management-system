package com.weeklyroster.service;

import com.weeklyroster.dto.request.CreateProfileChangeRequest;
import com.weeklyroster.dto.request.ProfileChangeDecisionRequest;
import com.weeklyroster.dto.response.ProfileChangeRequestResponse;
import com.weeklyroster.entity.ActivityCategory;
import com.weeklyroster.entity.ActivityStatus;
import com.weeklyroster.entity.AuditAction;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.NotificationType;
import com.weeklyroster.entity.ProfileChangeRequest;
import com.weeklyroster.entity.ProfileChangeStatus;
import com.weeklyroster.entity.Role;
import com.weeklyroster.entity.User;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.ProfileChangeRequestRepository;
import com.weeklyroster.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ProfileChangeRequestService {

    private static final Logger log = LoggerFactory.getLogger(ProfileChangeRequestService.class);

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");

    public static final Map<String, String> FIELD_CANONICAL_MAP = Map.ofEntries(
            Map.entry("FIRSTNAME", "firstName"),
            Map.entry("FIRST_NAME", "firstName"),
            Map.entry("LASTNAME", "lastName"),
            Map.entry("LAST_NAME", "lastName"),
            Map.entry("EMAIL", "email"),
            Map.entry("GENDER", "gender"),
            Map.entry("EMPLOYEECODE", "employeeCode"),
            Map.entry("EMPLOYEE_CODE", "employeeCode"),
            Map.entry("PHONE", "contactNumber"),
            Map.entry("PHONENUMBER", "contactNumber"),
            Map.entry("PHONE_NUMBER", "contactNumber"),
            Map.entry("CONTACT", "contactNumber"),
            Map.entry("CONTACTNUMBER", "contactNumber"),
            Map.entry("CONTACT_NUMBER", "contactNumber")
    );

    public static final Set<String> ALLOWED_FIELDS = Set.of(
            "firstName",
            "lastName",
            "email",
            "gender",
            "employeeCode",
            "contactNumber"
    );

    private final ProfileChangeRequestRepository profileChangeRequestRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final EmployeeActivityLogService activityLogService;
    private final DevCredentialMirrorService devCredentialMirrorService;

    public ProfileChangeRequestService(ProfileChangeRequestRepository profileChangeRequestRepository,
                                       EmployeeRepository employeeRepository,
                                       UserRepository userRepository,
                                       AuditService auditService,
                                       NotificationService notificationService,
                                       EmployeeActivityLogService activityLogService) {
        this(profileChangeRequestRepository, employeeRepository, userRepository, auditService, notificationService, activityLogService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProfileChangeRequestService(ProfileChangeRequestRepository profileChangeRequestRepository,
                                       EmployeeRepository employeeRepository,
                                       UserRepository userRepository,
                                       AuditService auditService,
                                       NotificationService notificationService,
                                       EmployeeActivityLogService activityLogService,
                                       @org.springframework.beans.factory.annotation.Autowired(required = false) DevCredentialMirrorService devCredentialMirrorService) {
        this.profileChangeRequestRepository = profileChangeRequestRepository;
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.activityLogService = activityLogService;
        this.devCredentialMirrorService = devCredentialMirrorService;
    }

    @Transactional
    public ProfileChangeRequestResponse submitRequest(CreateProfileChangeRequest request) {
        Employee employee = getCurrentEmployee();

        if (request.fieldName() == null || request.fieldName().isBlank()) {
            throw new BusinessException("Field name is required");
        }

        String canonicalField = normalizeFieldName(request.fieldName());
        if (!ALLOWED_FIELDS.contains(canonicalField)) {
            throw new BusinessException("Field '" + request.fieldName() + "' is not eligible for profile change request. Allowed fields: " + ALLOWED_FIELDS);
        }

        if (request.requestedValue() == null || request.requestedValue().trim().isEmpty()) {
            throw new BusinessException("Requested value cannot be blank for field: " + canonicalField);
        }

        String requestedValue = request.requestedValue().trim();
        validateRequestedValue(canonicalField, requestedValue);

        String currentValue = getCurrentFieldValue(employee, canonicalField);
        if (requestedValue.equalsIgnoreCase(currentValue != null ? currentValue.trim() : "")) {
            throw new BusinessException("Requested value is identical to current value: " + requestedValue);
        }

        // Prevent duplicate pending requests for the same employee + field
        if (profileChangeRequestRepository.existsByEmployeeIdAndFieldNameAndStatus(employee.getId(), canonicalField, ProfileChangeStatus.PENDING)) {
            throw new BusinessException("A pending change request already exists for field: " + canonicalField);
        }

        ProfileChangeRequest changeRequest = new ProfileChangeRequest();
        changeRequest.setEmployee(employee);
        changeRequest.setFieldName(canonicalField);
        changeRequest.setCurrentValue(currentValue);
        changeRequest.setRequestedValue(requestedValue);
        changeRequest.setStatus(ProfileChangeStatus.PENDING);
        changeRequest.setRequestedAt(LocalDateTime.now());

        ProfileChangeRequest saved = profileChangeRequestRepository.save(changeRequest);

        // Activity log
        String username = employee.getUser() != null ? employee.getUser().getUsername() : employee.getEmployeeCode().toLowerCase();
        if (activityLogService != null) {
            activityLogService.logActivity(
                    employee.getId(),
                    username,
                    ActivityCategory.PROFILE,
                    "PROFILE_CHANGE_REQUESTED",
                    ActivityStatus.SUCCESS,
                    "Submitted profile change request for " + canonicalField + " to '" + requestedValue + "'",
                    "WEB"
            );
        }

        // Notification for employee
        if (notificationService != null) {
            notificationService.createNotification(
                    username,
                    employee.getId(),
                    "Profile Change Request Submitted",
                    "Your profile change request for " + canonicalField + " has been submitted for admin review.",
                    NotificationType.PROFILE_CHANGE_REQUESTED,
                    "profile",
                    saved.getId()
            );
        }

        // Audit entry
        if (auditService != null) {
            auditService.log(
                    AuditAction.PROFILE_CHANGE_REQUESTED,
                    "PROFILE_CHANGE_REQUEST",
                    saved.getId(),
                    null,
                    employee.getId(),
                    employee.getFirstName() + " " + employee.getLastName(),
                    currentValue,
                    requestedValue,
                    "Employee requested profile change for " + canonicalField,
                    "WEB"
            );
        }

        return toResponse(saved);
    }

    @Transactional
    public ProfileChangeRequestResponse approve(Long id, ProfileChangeDecisionRequest decision) {
        ProfileChangeRequest request = profileChangeRequestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Profile change request not found with ID: " + id));

        if (request.getStatus() != ProfileChangeStatus.PENDING) {
            throw new BusinessException("Profile change request is not in PENDING state (current: " + request.getStatus() + ")");
        }

        Employee employee = request.getEmployee();
        if (employee == null) {
            throw new ResourceNotFoundException("Target employee not found for request: " + id);
        }

        String field = request.getFieldName();
        String requestedValue = request.getRequestedValue();

        // Apply employee update
        applyFieldUpdate(employee, field, requestedValue);
        employeeRepository.save(employee);

        if (devCredentialMirrorService != null) {
            devCredentialMirrorService.updateProfile(employee, null);
        }

        String remarks = decision != null ? decision.adminRemarks() : null;
        request.setStatus(ProfileChangeStatus.APPROVED);
        request.setReviewedAt(LocalDateTime.now());
        request.setAdminRemarks(remarks);

        ProfileChangeRequest saved = profileChangeRequestRepository.save(request);

        String username = employee.getUser() != null ? employee.getUser().getUsername() : employee.getEmployeeCode().toLowerCase();

        // Activity log
        if (activityLogService != null) {
            String desc = "Profile change request for " + field + " was approved by admin"
                    + (remarks != null && !remarks.isBlank() ? ". Remarks: " + remarks : "");
            activityLogService.logActivity(
                    employee.getId(),
                    username,
                    ActivityCategory.PROFILE,
                    "PROFILE_CHANGE_APPROVED",
                    ActivityStatus.SUCCESS,
                    desc,
                    "ADMIN"
            );
        }

        // Audit entry
        if (auditService != null) {
            auditService.log(
                    AuditAction.PROFILE_CHANGE_APPROVED,
                    "EMPLOYEE",
                    employee.getId(),
                    null,
                    employee.getId(),
                    employee.getFirstName() + " " + employee.getLastName(),
                    request.getCurrentValue(),
                    requestedValue,
                    remarks != null && !remarks.isBlank() ? remarks : "Profile change request approved",
                    "MANUAL"
            );
        }

        // Notification for employee
        if (notificationService != null) {
            String message = "Your profile change request for " + field + " has been approved."
                    + (remarks != null && !remarks.isBlank() ? " Remarks: " + remarks : "");
            notificationService.createNotification(
                    username,
                    employee.getId(),
                    "Profile Change Request Approved",
                    message,
                    NotificationType.PROFILE_CHANGE_DECISION,
                    "profile",
                    saved.getId()
            );
        }

        return toResponse(saved);
    }

    @Transactional
    public ProfileChangeRequestResponse reject(Long id, ProfileChangeDecisionRequest decision) {
        ProfileChangeRequest request = profileChangeRequestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Profile change request not found with ID: " + id));

        if (request.getStatus() != ProfileChangeStatus.PENDING) {
            throw new BusinessException("Profile change request is not in PENDING state (current: " + request.getStatus() + ")");
        }

        Employee employee = request.getEmployee();
        String remarks = decision != null ? decision.adminRemarks() : null;

        request.setStatus(ProfileChangeStatus.REJECTED);
        request.setReviewedAt(LocalDateTime.now());
        request.setAdminRemarks(remarks);

        ProfileChangeRequest saved = profileChangeRequestRepository.save(request);

        String username = employee != null
                ? (employee.getUser() != null ? employee.getUser().getUsername() : employee.getEmployeeCode().toLowerCase())
                : null;
        Long empId = employee != null ? employee.getId() : null;

        // Activity log
        if (activityLogService != null && empId != null) {
            String desc = "Profile change request for " + request.getFieldName() + " was rejected by admin"
                    + (remarks != null && !remarks.isBlank() ? ". Remarks: " + remarks : "");
            activityLogService.logActivity(
                    empId,
                    username,
                    ActivityCategory.PROFILE,
                    "PROFILE_CHANGE_REJECTED",
                    ActivityStatus.SUCCESS,
                    desc,
                    "ADMIN"
            );
        }

        // Audit entry
        if (auditService != null) {
            auditService.log(
                    AuditAction.PROFILE_CHANGE_REJECTED,
                    "PROFILE_CHANGE_REQUEST",
                    request.getId(),
                    null,
                    empId,
                    employee != null ? employee.getFirstName() + " " + employee.getLastName() : "Unknown",
                    request.getCurrentValue(),
                    request.getRequestedValue(),
                    remarks != null && !remarks.isBlank() ? remarks : "Profile change request rejected",
                    "MANUAL"
            );
        }

        // Notification for employee
        if (notificationService != null && username != null && empId != null) {
            String message = "Your profile change request for " + request.getFieldName() + " has been rejected."
                    + (remarks != null && !remarks.isBlank() ? " Remarks: " + remarks : "");
            notificationService.createNotification(
                    username,
                    empId,
                    "Profile Change Request Rejected",
                    message,
                    NotificationType.PROFILE_CHANGE_DECISION,
                    "profile",
                    saved.getId()
            );
        }

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<ProfileChangeRequestResponse> getMyRequests() {
        Employee employee = getCurrentEmployee();
        return profileChangeRequestRepository.findByEmployeeIdOrderByRequestedAtDesc(employee.getId())
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ProfileChangeRequestResponse> getPendingRequests() {
        return profileChangeRequestRepository.findByStatusOrderByRequestedAtAsc(ProfileChangeStatus.PENDING)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ProfileChangeRequestResponse getById(Long id) {
        ProfileChangeRequest request = profileChangeRequestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Profile change request not found with ID: " + id));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && !isAdmin(auth)) {
            Employee currentEmp = getCurrentEmployee();
            if (!request.getEmployee().getId().equals(currentEmp.getId())) {
                throw new AccessDeniedException("Access denied: You can only view your own change requests");
            }
        }

        return toResponse(request);
    }

    private String normalizeFieldName(String fieldName) {
        String upper = fieldName.trim().toUpperCase().replace("-", "_").replace(" ", "_");
        if (FIELD_CANONICAL_MAP.containsKey(upper)) {
            return FIELD_CANONICAL_MAP.get(upper);
        }
        for (String allowed : ALLOWED_FIELDS) {
            if (allowed.equalsIgnoreCase(fieldName.trim())) {
                return allowed;
            }
        }
        return fieldName.trim();
    }

    private void validateRequestedValue(String field, String value) {
        switch (field) {
            case "firstName" -> {
                if (value.length() > 80) {
                    throw new BusinessException("First name must not exceed 80 characters");
                }
            }
            case "lastName" -> {
                if (value.length() > 80) {
                    throw new BusinessException("Last name must not exceed 80 characters");
                }
            }
            case "email" -> {
                if (value.length() > 160) {
                    throw new BusinessException("Email must not exceed 160 characters");
                }
                if (!EMAIL_PATTERN.matcher(value).matches()) {
                    throw new BusinessException("Invalid email format: " + value);
                }
            }
            case "gender" -> {
                try {
                    Gender.valueOf(value.toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new BusinessException("Invalid gender: '" + value + "'. Must be MALE or FEMALE");
                }
            }
            case "employeeCode" -> {
                if (value.length() > 40) {
                    throw new BusinessException("Employee code must not exceed 40 characters");
                }
            }
            case "contactNumber" -> {
                if (value.length() > 30) {
                    throw new BusinessException("Contact number must not exceed 30 characters");
                }
            }
            default -> throw new BusinessException("Unsupported field: " + field);
        }
    }

    private String getCurrentFieldValue(Employee employee, String field) {
        return switch (field) {
            case "firstName" -> employee.getFirstName();
            case "lastName" -> employee.getLastName();
            case "email" -> employee.getEmail();
            case "gender" -> employee.getGender() != null ? employee.getGender().name() : null;
            case "employeeCode" -> employee.getEmployeeCode();
            case "contactNumber" -> employee.getContactNumber();
            default -> throw new BusinessException("Unsupported field: " + field);
        };
    }

    private void applyFieldUpdate(Employee employee, String field, String value) {
        switch (field) {
            case "firstName" -> employee.setFirstName(value);
            case "lastName" -> employee.setLastName(value);
            case "email" -> {
                if (!employee.getEmail().equalsIgnoreCase(value) && employeeRepository.existsByEmail(value)) {
                    throw new BusinessException("Email already in use by another employee: " + value);
                }
                employee.setEmail(value.toLowerCase());
            }
            case "gender" -> {
                try {
                    employee.setGender(Gender.valueOf(value.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    throw new BusinessException("Invalid gender: " + value);
                }
            }
            case "employeeCode" -> {
                if (!employee.getEmployeeCode().equalsIgnoreCase(value) && employeeRepository.existsByEmployeeCode(value)) {
                    throw new BusinessException("Employee code already in use by another employee: " + value);
                }
                employee.setEmployeeCode(value);
            }
            case "contactNumber" -> employee.setContactNumber(value);
            default -> throw new BusinessException("Unsupported field update: " + field);
        }
    }

    private User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            throw new AccessDeniedException("Authentication required");
        }
        return userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + auth.getName()));
    }

    private Employee getCurrentEmployee() {
        User user = getCurrentUser();
        return employeeRepository.findByUserUsername(user.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("Employee profile not found for user: " + user.getUsername()));
    }

    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(Role.ROLE_ADMIN.name()));
    }

    public ProfileChangeRequestResponse toResponse(ProfileChangeRequest r) {
        Employee e = r.getEmployee();
        return new ProfileChangeRequestResponse(
                r.getId(),
                e != null ? e.getId() : null,
                e != null ? e.getEmployeeCode() : null,
                e != null ? (e.getFirstName() + " " + e.getLastName()) : null,
                r.getFieldName(),
                r.getCurrentValue(),
                r.getRequestedValue(),
                r.getStatus(),
                r.getRequestedAt(),
                r.getReviewedAt(),
                r.getAdminRemarks()
        );
    }
}
