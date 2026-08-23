package com.weeklyroster.service;

import com.weeklyroster.dto.request.CreateHandoverRequest;
import com.weeklyroster.dto.request.UpdateHandoverRequest;
import com.weeklyroster.dto.response.HandoverResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.ShiftHandoverRepository;
import com.weeklyroster.repository.ShiftRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional
public class ShiftHandoverService {

    private final ShiftHandoverRepository handoverRepository;
    private final ShiftRepository shiftRepository;
    private final EmployeeRepository employeeRepository;
    private final NotificationService notificationService;
    private final EmployeeActivityLogService activityLogService;
    private final AuditService auditService;

    public ShiftHandoverService(ShiftHandoverRepository handoverRepository,
                                ShiftRepository shiftRepository,
                                EmployeeRepository employeeRepository,
                                NotificationService notificationService,
                                EmployeeActivityLogService activityLogService,
                                AuditService auditService) {
        this.handoverRepository = handoverRepository;
        this.shiftRepository = shiftRepository;
        this.employeeRepository = employeeRepository;
        this.notificationService = notificationService;
        this.activityLogService = activityLogService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<HandoverResponse> getMyHandovers(Long employeeId) {
        return handoverRepository.findByFromEmployeeIdOrderByHandoverDateDescCreatedAtDesc(employeeId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<HandoverResponse> getIncomingHandovers(Long employeeId) {
        return handoverRepository.findByToEmployeeIdOrderByHandoverDateDescCreatedAtDesc(employeeId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<HandoverResponse> getRecentHandovers() {
        return handoverRepository.findTop20ByOrderByHandoverDateDescCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<HandoverResponse> getAllHandovers(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null) {
            return handoverRepository.findByHandoverDateBetweenOrderByHandoverDateDescCreatedAtDesc(startDate, endDate).stream()
                    .map(this::toResponse)
                    .toList();
        }
        return handoverRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public HandoverResponse getHandoverById(Long id) {
        ShiftHandover h = handoverRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Handover not found with id: " + id));
        return toResponse(h);
    }

    public HandoverResponse createHandover(Long fromEmployeeId, CreateHandoverRequest req, String username) {
        Employee fromEmp = employeeRepository.findById(fromEmployeeId)
                .orElseThrow(() -> new ResourceNotFoundException("From Employee not found with id: " + fromEmployeeId));

        Shift shift = shiftRepository.findById(req.shiftId())
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found with id: " + req.shiftId()));

        Employee toEmp = null;
        if (req.toEmployeeId() != null) {
            if (req.toEmployeeId().equals(fromEmployeeId)) {
                throw new BusinessException("An employee cannot handover a shift to themselves.");
            }
            toEmp = employeeRepository.findById(req.toEmployeeId())
                    .orElseThrow(() -> new ResourceNotFoundException("Target Employee not found with id: " + req.toEmployeeId()));
        }

        ShiftHandover h = new ShiftHandover();
        h.setHandoverDate(req.handoverDate());
        h.setShift(shift);
        h.setFromEmployee(fromEmp);
        h.setToEmployee(toEmp);
        h.setSummary(req.summary().trim());
        h.setPendingTasks(req.pendingTasks() != null ? req.pendingTasks().trim() : null);
        h.setCompletedTasks(req.completedTasks() != null ? req.completedTasks().trim() : null);
        h.setImportantNotes(req.importantNotes() != null ? req.importantNotes().trim() : null);
        if (req.priority() != null) {
            h.setPriority(req.priority());
        }
        h.setStatus(HandoverStatus.OPEN);
        h.setCreatedAt(LocalDateTime.now());
        h.setUpdatedAt(LocalDateTime.now());

        ShiftHandover saved = handoverRepository.save(h);

        activityLogService.logActivity(fromEmp.getId(), username, ActivityCategory.HANDOVER,
                "HANDOVER_CREATED", ActivityStatus.SUCCESS,
                "Created shift handover note #" + saved.getId() + " for " + shift.getShiftType().name());

        auditService.log(AuditAction.HANDOVER_CREATED, "SHIFT_HANDOVER", saved.getId(), null,
                fromEmp.getId(), fromEmp.getFirstName() + " " + fromEmp.getLastName(),
                null, saved.getStatus().name(), "Created shift handover: " + saved.getSummary(), "MANUAL");

        if (toEmp != null) {
            notificationService.createNotification(toEmp.getEmployeeCode().toLowerCase(), toEmp.getId(),
                    "Shift Handover Received",
                    fromEmp.getFirstName() + " " + fromEmp.getLastName() + " created a handover note for " + shift.getShiftType().name() + " shift.",
                    NotificationType.HANDOVER_ASSIGNED, "handovers", saved.getId());
        }

        return toResponse(saved);
    }

    public HandoverResponse acknowledgeHandover(Long id, Long employeeId, String remarks, String username) {
        ShiftHandover h = handoverRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Handover not found with id: " + id));

        if (h.getToEmployee() != null && !h.getToEmployee().getId().equals(employeeId)) {
            throw new BusinessException("Only the designated incoming employee can acknowledge this handover.");
        }

        h.setStatus(HandoverStatus.ACKNOWLEDGED);
        if (remarks != null && !remarks.isBlank()) {
            String currentNotes = h.getImportantNotes() != null ? h.getImportantNotes() + " | " : "";
            h.setImportantNotes(currentNotes + "Ack: " + remarks.trim());
        }
        h.setUpdatedAt(LocalDateTime.now());
        ShiftHandover saved = handoverRepository.save(h);

        activityLogService.logActivity(employeeId, username, ActivityCategory.HANDOVER,
                "HANDOVER_ACKNOWLEDGED", ActivityStatus.SUCCESS,
                "Acknowledged shift handover note #" + saved.getId());

        auditService.log(AuditAction.HANDOVER_UPDATED, "SHIFT_HANDOVER", saved.getId(), null,
                employeeId, null, "OPEN", "ACKNOWLEDGED", "Acknowledged handover note #" + saved.getId(), "MANUAL");

        if (h.getFromEmployee() != null) {
            notificationService.createNotification(h.getFromEmployee().getEmployeeCode().toLowerCase(), h.getFromEmployee().getId(),
                    "Shift Handover Acknowledged",
                    username + " acknowledged your shift handover note.",
                    NotificationType.HANDOVER_ASSIGNED, "handovers", saved.getId());
        }

        return toResponse(saved);
    }

    public HandoverResponse updateHandover(Long id, Long employeeId, boolean isAdmin, UpdateHandoverRequest req, String username) {
        ShiftHandover h = handoverRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Handover not found with id: " + id));

        if (!isAdmin && !h.getFromEmployee().getId().equals(employeeId) &&
                (h.getToEmployee() == null || !h.getToEmployee().getId().equals(employeeId))) {
            throw new BusinessException("You are not authorized to update this handover note.");
        }

        if (req.toEmployeeId() != null) {
            Employee toEmp = employeeRepository.findById(req.toEmployeeId())
                    .orElseThrow(() -> new ResourceNotFoundException("Target Employee not found with id: " + req.toEmployeeId()));
            h.setToEmployee(toEmp);
        }

        if (req.summary() != null && !req.summary().isBlank()) {
            h.setSummary(req.summary().trim());
        }
        if (req.pendingTasks() != null) {
            h.setPendingTasks(req.pendingTasks().trim());
        }
        if (req.completedTasks() != null) {
            h.setCompletedTasks(req.completedTasks().trim());
        }
        if (req.importantNotes() != null) {
            h.setImportantNotes(req.importantNotes().trim());
        }
        if (req.priority() != null) {
            h.setPriority(req.priority());
        }
        if (req.status() != null) {
            h.setStatus(req.status());
        }
        h.setUpdatedAt(LocalDateTime.now());

        ShiftHandover updated = handoverRepository.save(h);

        activityLogService.logActivity(employeeId, username, ActivityCategory.HANDOVER,
                "HANDOVER_UPDATED", ActivityStatus.SUCCESS,
                "Updated shift handover note #" + updated.getId() + " (Status: " + updated.getStatus().name() + ")");

        auditService.log(AuditAction.HANDOVER_UPDATED, "SHIFT_HANDOVER", updated.getId(), null,
                employeeId, null, null, updated.getStatus().name(), "Updated handover note #" + updated.getId(), "MANUAL");

        return toResponse(updated);
    }

    private HandoverResponse toResponse(ShiftHandover h) {
        Employee from = h.getFromEmployee();
        Employee to = h.getToEmployee();
        Shift s = h.getShift();
        return new HandoverResponse(
                h.getId(),
                h.getHandoverDate(),
                s != null ? s.getId() : null,
                s != null ? s.getShiftType().name() : null,
                from != null ? from.getId() : null,
                from != null ? from.getEmployeeCode() : null,
                from != null ? (from.getFirstName() + " " + (from.getLastName() != null ? from.getLastName() : "")).trim() : null,
                to != null ? to.getId() : null,
                to != null ? to.getEmployeeCode() : null,
                to != null ? (to.getFirstName() + " " + (to.getLastName() != null ? to.getLastName() : "")).trim() : null,
                h.getSummary(),
                h.getPendingTasks(),
                h.getCompletedTasks(),
                h.getImportantNotes(),
                h.getPriority(),
                h.getStatus(),
                h.getCreatedAt(),
                h.getUpdatedAt()
        );
    }
}
