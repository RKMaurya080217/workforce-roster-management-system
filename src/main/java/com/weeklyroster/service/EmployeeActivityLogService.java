package com.weeklyroster.service;

import com.weeklyroster.dto.response.EmployeeActivityPageResponse;
import com.weeklyroster.dto.response.EmployeeActivityResponse;
import com.weeklyroster.entity.ActivityCategory;
import com.weeklyroster.entity.ActivityStatus;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.EmployeeActivityLog;
import com.weeklyroster.repository.EmployeeActivityLogRepository;
import com.weeklyroster.repository.EmployeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class EmployeeActivityLogService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeActivityLogService.class);

    private final EmployeeActivityLogRepository activityLogRepository;
    private final EmployeeRepository employeeRepository;

    public EmployeeActivityLogService(EmployeeActivityLogRepository activityLogRepository,
                                      EmployeeRepository employeeRepository) {
        this.activityLogRepository = activityLogRepository;
        this.employeeRepository = employeeRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EmployeeActivityLog logActivity(Long employeeId,
                                           String username,
                                           ActivityCategory category,
                                           String action,
                                           ActivityStatus status,
                                           String description) {
        return logActivity(employeeId, username, category, action, status, description, "MANUAL");
    }

    /**
     * Records a new activity log entry. Runs in a separate transaction to ensure activity is
     * recorded even if the calling transaction fails or rolls back (e.g. failed login).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EmployeeActivityLog logActivity(Long employeeId,
                                           String username,
                                           ActivityCategory category,
                                           String action,
                                           ActivityStatus status,
                                           String description,
                                           String source) {
        try {
            if (username == null && employeeId != null) {
                Employee emp = employeeRepository.findById(employeeId).orElse(null);
                if (emp != null && emp.getUser() != null) {
                    username = emp.getUser().getUsername();
                } else if (emp != null) {
                    username = emp.getEmployeeCode().toLowerCase();
                }
            }

            if (employeeId == null && username != null) {
                Employee emp = employeeRepository.findByUserUsername(username).orElse(null);
                if (emp != null) {
                    employeeId = emp.getId();
                }
            }

            EmployeeActivityLog entry = new EmployeeActivityLog();
            entry.setEmployeeId(employeeId);
            entry.setUsername(username != null ? username : "anonymous");
            entry.setCategory(category != null ? category : ActivityCategory.ACCOUNT);
            entry.setAction(action != null ? action.toUpperCase() : "ACTIVITY");
            entry.setStatus(status != null ? status : ActivityStatus.SUCCESS);
            entry.setDescription(sanitizeDescription(description));
            entry.setSource(source != null ? source : "WEB");
            entry.setCreatedAt(LocalDateTime.now());

            return activityLogRepository.save(entry);
        } catch (Exception e) {
            log.error("Failed to persist employee activity log: {}", e.getMessage());
            return null;
        }
    }

    public EmployeeActivityLog logUserActivity(String username,
                                               ActivityCategory category,
                                               String action,
                                               ActivityStatus status,
                                               String description) {
        return logActivity(null, username, category, action, status, description, "WEB");
    }

    public EmployeeActivityLog logEmployeeActivity(Employee employee,
                                                   ActivityCategory category,
                                                   String action,
                                                   ActivityStatus status,
                                                   String description) {
        Long empId = employee != null ? employee.getId() : null;
        String username = (employee != null && employee.getUser() != null) ? employee.getUser().getUsername() : null;
        return logActivity(empId, username, category, action, status, description, "WEB");
    }

    @Transactional(readOnly = true)
    public EmployeeActivityPageResponse getMyActivities(String username, String categoryStr, int page, int size) {
        if (page < 0) page = 0;
        if (size <= 0 || size > 100) size = 20;

        Pageable pageable = PageRequest.of(page, size);
        Page<EmployeeActivityLog> resultPage;

        ActivityCategory category = null;
        if (categoryStr != null && !categoryStr.trim().isEmpty() && !categoryStr.equalsIgnoreCase("ALL")) {
            try {
                category = ActivityCategory.valueOf(categoryStr.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }

        if (category != null) {
            resultPage = activityLogRepository.findByUsernameAndCategoryOrderByCreatedAtDesc(username, category, pageable);
        } else {
            resultPage = activityLogRepository.findByUsernameOrderByCreatedAtDesc(username, pageable);
        }

        List<EmployeeActivityResponse> dtoList = resultPage.getContent().stream()
                .map(this::toResponse)
                .toList();

        return new EmployeeActivityPageResponse(
                dtoList,
                resultPage.getNumber(),
                resultPage.getSize(),
                resultPage.getTotalElements(),
                resultPage.getTotalPages(),
                resultPage.hasNext()
        );
    }

    private EmployeeActivityResponse toResponse(EmployeeActivityLog entity) {
        return new EmployeeActivityResponse(
                entity.getId(),
                entity.getEmployeeId(),
                entity.getUsername(),
                entity.getCategory(),
                entity.getAction(),
                entity.getStatus(),
                entity.getDescription(),
                entity.getSource(),
                entity.getCreatedAt()
        );
    }

    private String sanitizeDescription(String input) {
        if (input == null) return "Activity logged";
        String clean = input.replaceAll("(?i)(password|token|secret|bearer)\\s*[:=]\\s*[^\\s,;]+", "$1=***");
        if (clean.length() > 500) {
            return clean.substring(0, 497) + "...";
        }
        return clean;
    }
}
