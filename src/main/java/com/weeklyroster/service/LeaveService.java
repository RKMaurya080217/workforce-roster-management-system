package com.weeklyroster.service;

import com.weeklyroster.dto.request.ApplyLeaveRequest;
import com.weeklyroster.dto.request.CancelLeaveRequest;
import com.weeklyroster.dto.request.LeaveDecisionRequest;
import com.weeklyroster.dto.request.ModifyLeaveRequest;
import com.weeklyroster.dto.response.LeaveResponse;
import com.weeklyroster.entity.ActivityCategory;
import com.weeklyroster.entity.ActivityStatus;
import com.weeklyroster.entity.AuditAction;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.LeaveRequest;
import com.weeklyroster.entity.LeaveStatus;
import com.weeklyroster.entity.NotificationType;
import com.weeklyroster.entity.RosterAssignment;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.LeaveRequestRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.ShiftRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeaveService {
    private final LeaveRequestRepository leaveRepository;
    private final EmployeeRepository employeeRepository;
    private final RosterAssignmentRepository assignmentRepository;
    private final ShiftRepository shiftRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final EmployeeActivityLogService activityLogService;

    @org.springframework.beans.factory.annotation.Autowired
    public LeaveService(LeaveRequestRepository leaveRepository, EmployeeRepository employeeRepository,
                        RosterAssignmentRepository assignmentRepository, ShiftRepository shiftRepository,
                        AuditService auditService, NotificationService notificationService,
                        EmployeeActivityLogService activityLogService) {
        this.leaveRepository = leaveRepository;
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.shiftRepository = shiftRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.activityLogService = activityLogService;
    }

    public LeaveService(LeaveRequestRepository leaveRepository, EmployeeRepository employeeRepository,
                        RosterAssignmentRepository assignmentRepository, ShiftRepository shiftRepository) {
        this(leaveRepository, employeeRepository, assignmentRepository, shiftRepository, null, null, null);
    }

    @Transactional
    public LeaveResponse apply(ApplyLeaveRequest request) {
        if (request.endDate().isBefore(request.startDate())) {
            throw new BusinessException("Leave end date cannot be before start date");
        }
        Employee employee = employeeRepository.findById(request.employeeId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));
        if (!employee.isActive()) {
            throw new BusinessException("Inactive employee cannot apply leave");
        }

        verifyEmployeeAccess(employee.getId());

        boolean hasOverlap = leaveRepository.existsOverlappingLeave(
                employee.getId(),
                List.of(LeaveStatus.PENDING, LeaveStatus.APPROVED, LeaveStatus.PENDING_MODIFICATION),
                request.startDate(),
                request.endDate()
        );
        if (hasOverlap) {
            throw new BusinessException("A pending or approved leave request already exists for the requested dates");
        }

        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(employee);
        leave.setStartDate(request.startDate());
        leave.setEndDate(request.endDate());
        leave.setReason(request.reason());
        leave.setStatus(LeaveStatus.PENDING);
        leave.setRequestedAt(LocalDateTime.now());
        LeaveRequest saved = leaveRepository.save(leave);
        if (saved != null) leave = saved;

        if (auditService != null) {
            auditService.log(AuditAction.LEAVE_APPLIED, "LEAVE_REQUEST", leave.getId(), null,
                    employee.getId(), employee.getFirstName() + " " + employee.getLastName(),
                    null, "PENDING", "Leave application: " + request.reason(), "MANUAL");
        }

        if (activityLogService != null) {
            activityLogService.logEmployeeActivity(
                    employee,
                    ActivityCategory.LEAVE,
                    "LEAVE_APPLIED",
                    ActivityStatus.PENDING,
                    "Leave application submitted for " + request.startDate() + " to " + request.endDate() + " (Reason: " + (request.reason() != null ? request.reason() : "N/A") + ")"
            );
        }

        if (notificationService != null) {
            notificationService.notifyAdmins("New Leave Request",
                    employee.getFirstName() + " " + employee.getLastName() + " applied for leave from " + request.startDate() + " to " + request.endDate(),
                    NotificationType.LEAVE_DECISION, "leaves", leave.getId());
        }

        return toResponse(leave);
    }

    @Transactional
    public LeaveResponse requestModification(Long id, ModifyLeaveRequest request) {
        if (request.newEndDate().isBefore(request.newStartDate())) {
            throw new BusinessException("Leave end date cannot be before start date");
        }

        LeaveRequest leave = leaveRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Leave request not found"));

        verifyEmployeeAccess(leave.getEmployee().getId());

        if (leave.getStatus() != LeaveStatus.APPROVED) {
            throw new BusinessException("Only approved leave requests can be modified. Current status: " + leave.getStatus());
        }

        boolean hasOverlap = leaveRepository.existsOverlappingLeaveExcludingId(
                leave.getEmployee().getId(),
                List.of(LeaveStatus.PENDING, LeaveStatus.APPROVED, LeaveStatus.PENDING_MODIFICATION),
                request.newStartDate(),
                request.newEndDate(),
                leave.getId()
        );
        if (hasOverlap) {
            throw new BusinessException("Another active leave request already exists overlapping the requested new dates");
        }

        leave.setPendingStartDate(request.newStartDate());
        leave.setPendingEndDate(request.newEndDate());
        leave.setModificationReason(request.reason());
        leave.setModifiedAt(LocalDateTime.now());
        leave.setStatus(LeaveStatus.PENDING_MODIFICATION);
        LeaveRequest savedMod = leaveRepository.save(leave);
        if (savedMod != null) leave = savedMod;

        if (auditService != null) {
            auditService.log(AuditAction.LEAVE_MODIFIED, "LEAVE_REQUEST", leave.getId(), null,
                    leave.getEmployee().getId(), leave.getEmployee().getFirstName() + " " + leave.getEmployee().getLastName(),
                    leave.getStartDate() + " to " + leave.getEndDate(),
                    request.newStartDate() + " to " + request.newEndDate(),
                    "Modification request: " + request.reason(), "MANUAL");
        }

        if (activityLogService != null) {
            activityLogService.logEmployeeActivity(
                    leave.getEmployee(),
                    ActivityCategory.LEAVE,
                    "LEAVE_MODIFICATION_REQUESTED",
                    ActivityStatus.PENDING,
                    "Leave modification request submitted for " + request.newStartDate() + " to " + request.newEndDate() + " (Reason: " + (request.reason() != null ? request.reason() : "N/A") + ")"
            );
        }

        if (notificationService != null) {
            notificationService.notifyAdmins("Leave Modification Requested",
                    leave.getEmployee().getFirstName() + " " + leave.getEmployee().getLastName() + " requested to modify leave to " + request.newStartDate() + " to " + request.newEndDate(),
                    NotificationType.LEAVE_DECISION, "leaves", leave.getId());
        }

        return toResponse(leave);
    }

    @Transactional
    public LeaveResponse requestCancellation(Long id, CancelLeaveRequest request) {
        LeaveRequest leave = leaveRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Leave request not found"));

        verifyEmployeeAccess(leave.getEmployee().getId());

        if (leave.getStatus() != LeaveStatus.APPROVED && leave.getStatus() != LeaveStatus.PENDING_MODIFICATION) {
            throw new BusinessException("Only approved leave requests can be cancelled. Current status: " + leave.getStatus());
        }

        leave.setCancellationReason(request.reason());
        leave.setModifiedAt(LocalDateTime.now());
        leave.setStatus(LeaveStatus.PENDING_CANCELLATION);
        LeaveRequest savedCanc = leaveRepository.save(leave);
        if (savedCanc != null) leave = savedCanc;

        if (auditService != null) {
            auditService.log(AuditAction.LEAVE_CANCELLED, "LEAVE_REQUEST", leave.getId(), null,
                    leave.getEmployee().getId(), leave.getEmployee().getFirstName() + " " + leave.getEmployee().getLastName(),
                    "APPROVED", "PENDING_CANCELLATION",
                    "Cancellation request: " + request.reason(), "MANUAL");
        }

        if (activityLogService != null) {
            activityLogService.logEmployeeActivity(
                    leave.getEmployee(),
                    ActivityCategory.LEAVE,
                    "LEAVE_CANCELLATION_REQUESTED",
                    ActivityStatus.PENDING,
                    "Leave cancellation request submitted for " + leave.getStartDate() + " to " + leave.getEndDate() + " (Reason: " + (request.reason() != null ? request.reason() : "N/A") + ")"
            );
        }

        if (notificationService != null) {
            notificationService.notifyAdmins("Leave Cancellation Requested",
                    leave.getEmployee().getFirstName() + " " + leave.getEmployee().getLastName() + " requested to cancel approved leave.",
                    NotificationType.LEAVE_DECISION, "leaves", leave.getId());
        }

        return toResponse(leave);
    }

    @Transactional(readOnly = true)
    public List<LeaveResponse> myLeaves(Long employeeId) {
        verifyEmployeeAccess(employeeId);
        return leaveRepository.findByEmployeeIdOrderByRequestedAtDesc(employeeId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<LeaveResponse> pending() {
        return leaveRepository.findByStatusInOrderByRequestedAtAsc(
                List.of(LeaveStatus.PENDING, LeaveStatus.PENDING_MODIFICATION, LeaveStatus.PENDING_CANCELLATION)
        ).stream().map(this::toResponse).toList();
    }

    @Transactional
    public LeaveResponse approve(Long id, LeaveDecisionRequest request) {
        LeaveRequest leave = leaveRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Leave request not found"));

        if (leave.getStatus() == LeaveStatus.PENDING_MODIFICATION) {
            return approveModification(id, request);
        } else if (leave.getStatus() == LeaveStatus.PENDING_CANCELLATION) {
            return approveCancellation(id, request);
        }

        leave.setStatus(LeaveStatus.APPROVED);
        leave.setAdminRemarks(request != null ? request.remarks() : null);
        leave.setReviewedAt(LocalDateTime.now());

        Shift offShift = shiftRepository.findByShiftType(ShiftType.OFF).orElse(null);
        List<RosterAssignment> affectedAssignments = assignmentRepository
                .findByEmployeeIdAndRosterDateBetween(leave.getEmployee().getId(), leave.getStartDate(), leave.getEndDate());
        for (RosterAssignment assignment : affectedAssignments) {
            assignment.setOnLeave(true);
            if (offShift != null) {
                assignment.setShift(offShift);
            }
        }
        if (!affectedAssignments.isEmpty()) {
            assignmentRepository.saveAll(affectedAssignments);
        }

        if (auditService != null) {
            auditService.log(AuditAction.LEAVE_APPROVED, "LEAVE_REQUEST", leave.getId(), null,
                    leave.getEmployee().getId(), leave.getEmployee().getFirstName() + " " + leave.getEmployee().getLastName(),
                    "PENDING", "APPROVED", "Admin approved leave request: " + (request != null ? request.remarks() : ""), "MANUAL");
        }

        if (activityLogService != null) {
            activityLogService.logEmployeeActivity(
                    leave.getEmployee(),
                    ActivityCategory.LEAVE,
                    "LEAVE_APPROVED",
                    ActivityStatus.SUCCESS,
                    "Leave request for " + leave.getStartDate() + " to " + leave.getEndDate() + " was approved by administrator."
            );
        }

        if (notificationService != null) {
            notificationService.notifyEmployee(leave.getEmployee(), "Leave Request Approved",
                    "Your leave request for " + leave.getStartDate() + " to " + leave.getEndDate() + " has been approved.",
                    NotificationType.LEAVE_DECISION, "leaves", leave.getId());
        }

        return toResponse(leave);
    }

    @Transactional
    public LeaveResponse reject(Long id, LeaveDecisionRequest request) {
        LeaveRequest leave = leaveRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Leave request not found"));

        if (leave.getStatus() == LeaveStatus.PENDING_MODIFICATION) {
            return rejectModification(id, request);
        } else if (leave.getStatus() == LeaveStatus.PENDING_CANCELLATION) {
            return rejectCancellation(id, request);
        }

        boolean wasApproved = leave.getStatus() == LeaveStatus.APPROVED;
        leave.setStatus(LeaveStatus.REJECTED);
        leave.setAdminRemarks(request != null ? request.remarks() : null);
        leave.setReviewedAt(LocalDateTime.now());

        if (wasApproved) {
            List<RosterAssignment> affectedAssignments = assignmentRepository
                    .findByEmployeeIdAndRosterDateBetween(leave.getEmployee().getId(), leave.getStartDate(), leave.getEndDate());
            for (RosterAssignment assignment : affectedAssignments) {
                assignment.setOnLeave(false);
            }
            if (!affectedAssignments.isEmpty()) {
                assignmentRepository.saveAll(affectedAssignments);
            }
        }

        if (auditService != null) {
            auditService.log(AuditAction.LEAVE_REJECTED, "LEAVE_REQUEST", leave.getId(), null,
                    leave.getEmployee().getId(), leave.getEmployee().getFirstName() + " " + leave.getEmployee().getLastName(),
                    "PENDING", "REJECTED", "Admin rejected leave request: " + (request != null ? request.remarks() : ""), "MANUAL");
        }

        if (activityLogService != null) {
            activityLogService.logEmployeeActivity(
                    leave.getEmployee(),
                    ActivityCategory.LEAVE,
                    "LEAVE_REJECTED",
                    ActivityStatus.FAILED,
                    "Leave request for " + leave.getStartDate() + " to " + leave.getEndDate() + " was rejected by administrator." + (request != null && request.remarks() != null ? " Remarks: " + request.remarks() : "")
            );
        }

        if (notificationService != null) {
            notificationService.notifyEmployee(leave.getEmployee(), "Leave Request Rejected",
                    "Your leave request for " + leave.getStartDate() + " to " + leave.getEndDate() + " was rejected." + (request != null && request.remarks() != null ? " Remarks: " + request.remarks() : ""),
                    NotificationType.LEAVE_DECISION, "leaves", leave.getId());
        }

        return toResponse(leave);
    }

    @Transactional
    public LeaveResponse approveModification(Long id, LeaveDecisionRequest request) {
        LeaveRequest leave = leaveRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Leave request not found"));

        if (leave.getStatus() != LeaveStatus.PENDING_MODIFICATION) {
            throw new BusinessException("Leave request is not in pending modification state");
        }

        if (leave.getOriginalStartDate() == null) {
            leave.setOriginalStartDate(leave.getStartDate());
        }
        if (leave.getOriginalEndDate() == null) {
            leave.setOriginalEndDate(leave.getEndDate());
        }

        LocalDate oldStart = leave.getStartDate();
        LocalDate oldEnd = leave.getEndDate();
        LocalDate newStart = leave.getPendingStartDate();
        LocalDate newEnd = leave.getPendingEndDate();

        leave.setStartDate(newStart);
        leave.setEndDate(newEnd);
        leave.setPendingStartDate(null);
        leave.setPendingEndDate(null);
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setAdminRemarks(request != null ? request.remarks() : "Modification approved");
        leave.setReviewedAt(LocalDateTime.now());

        Shift offShift = shiftRepository.findByShiftType(ShiftType.OFF).orElse(null);

        // Synchronize Roster Assignments
        // 1. Check assignments in old date range
        List<RosterAssignment> oldAssignments = assignmentRepository
                .findByEmployeeIdAndRosterDateBetween(leave.getEmployee().getId(), oldStart, oldEnd);
        for (RosterAssignment assignment : oldAssignments) {
            LocalDate d = assignment.getRosterDate();
            boolean stillInLeave = !d.isBefore(newStart) && !d.isAfter(newEnd);
            if (!stillInLeave) {
                assignment.setOnLeave(false);
            } else {
                assignment.setOnLeave(true);
                if (offShift != null) {
                    assignment.setShift(offShift);
                }
            }
        }
        if (!oldAssignments.isEmpty()) {
            assignmentRepository.saveAll(oldAssignments);
        }

        // 2. Check assignments in new date range
        List<RosterAssignment> newAssignments = assignmentRepository
                .findByEmployeeIdAndRosterDateBetween(leave.getEmployee().getId(), newStart, newEnd);
        for (RosterAssignment assignment : newAssignments) {
            assignment.setOnLeave(true);
            if (offShift != null) {
                assignment.setShift(offShift);
            }
        }
        if (!newAssignments.isEmpty()) {
            assignmentRepository.saveAll(newAssignments);
        }

        if (auditService != null) {
            auditService.log(AuditAction.LEAVE_APPROVED, "LEAVE_REQUEST", leave.getId(), null,
                    leave.getEmployee().getId(), leave.getEmployee().getFirstName() + " " + leave.getEmployee().getLastName(),
                    oldStart + " to " + oldEnd, newStart + " to " + newEnd, "Admin approved leave modification", "MANUAL");
        }

        if (activityLogService != null) {
            activityLogService.logEmployeeActivity(
                    leave.getEmployee(),
                    ActivityCategory.LEAVE,
                    "LEAVE_MODIFIED",
                    ActivityStatus.SUCCESS,
                    "Leave modification approved. New dates: " + newStart + " to " + newEnd + "."
            );
        }

        if (notificationService != null) {
            notificationService.notifyEmployee(leave.getEmployee(), "Leave Modification Approved",
                    "Your leave dates have been updated to " + newStart + " to " + newEnd + ".",
                    NotificationType.LEAVE_DECISION, "leaves", leave.getId());
        }

        return toResponse(leave);
    }

    @Transactional
    public LeaveResponse rejectModification(Long id, LeaveDecisionRequest request) {
        LeaveRequest leave = leaveRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Leave request not found"));

        if (leave.getStatus() != LeaveStatus.PENDING_MODIFICATION) {
            throw new BusinessException("Leave request is not in pending modification state");
        }

        leave.setPendingStartDate(null);
        leave.setPendingEndDate(null);
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setAdminRemarks(request != null ? request.remarks() : "Modification rejected");
        leave.setReviewedAt(LocalDateTime.now());

        if (auditService != null) {
            auditService.log(AuditAction.LEAVE_REJECTED, "LEAVE_REQUEST", leave.getId(), null,
                    leave.getEmployee().getId(), leave.getEmployee().getFirstName() + " " + leave.getEmployee().getLastName(),
                    "PENDING_MODIFICATION", "APPROVED", "Admin rejected leave modification", "MANUAL");
        }

        if (activityLogService != null) {
            activityLogService.logEmployeeActivity(
                    leave.getEmployee(),
                    ActivityCategory.LEAVE,
                    "LEAVE_MODIFICATION_REJECTED",
                    ActivityStatus.FAILED,
                    "Leave modification request was rejected by administrator. Original dates remain active."
            );
        }

        if (notificationService != null) {
            notificationService.notifyEmployee(leave.getEmployee(), "Leave Modification Rejected",
                    "Your request to modify leave dates was rejected. Original dates remain intact.",
                    NotificationType.LEAVE_DECISION, "leaves", leave.getId());
        }

        return toResponse(leave);
    }

    @Transactional
    public LeaveResponse approveCancellation(Long id, LeaveDecisionRequest request) {
        LeaveRequest leave = leaveRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Leave request not found"));

        if (leave.getStatus() != LeaveStatus.PENDING_CANCELLATION && leave.getStatus() != LeaveStatus.APPROVED) {
            throw new BusinessException("Leave request is not in pending cancellation state");
        }

        leave.setStatus(LeaveStatus.CANCELLED);
        leave.setAdminRemarks(request != null ? request.remarks() : "Cancellation approved");
        leave.setReviewedAt(LocalDateTime.now());

        List<RosterAssignment> affectedAssignments = assignmentRepository
                .findByEmployeeIdAndRosterDateBetween(leave.getEmployee().getId(), leave.getStartDate(), leave.getEndDate());
        for (RosterAssignment assignment : affectedAssignments) {
            assignment.setOnLeave(false);
        }
        if (!affectedAssignments.isEmpty()) {
            assignmentRepository.saveAll(affectedAssignments);
        }

        if (auditService != null) {
            auditService.log(AuditAction.LEAVE_CANCELLED, "LEAVE_REQUEST", leave.getId(), null,
                    leave.getEmployee().getId(), leave.getEmployee().getFirstName() + " " + leave.getEmployee().getLastName(),
                    "APPROVED", "CANCELLED", "Admin approved leave cancellation", "MANUAL");
        }

        if (activityLogService != null) {
            activityLogService.logEmployeeActivity(
                    leave.getEmployee(),
                    ActivityCategory.LEAVE,
                    "LEAVE_CANCELLED",
                    ActivityStatus.SUCCESS,
                    "Leave cancellation approved. Leave from " + leave.getStartDate() + " to " + leave.getEndDate() + " has been cancelled."
            );
        }

        if (notificationService != null) {
            notificationService.notifyEmployee(leave.getEmployee(), "Leave Cancellation Approved",
                    "Your leave request has been cancelled.",
                    NotificationType.LEAVE_DECISION, "leaves", leave.getId());
        }

        return toResponse(leave);
    }

    @Transactional
    public LeaveResponse rejectCancellation(Long id, LeaveDecisionRequest request) {
        LeaveRequest leave = leaveRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Leave request not found"));

        if (leave.getStatus() != LeaveStatus.PENDING_CANCELLATION) {
            throw new BusinessException("Leave request is not in pending cancellation state");
        }

        leave.setStatus(LeaveStatus.APPROVED);
        leave.setAdminRemarks(request != null ? request.remarks() : "Cancellation rejected");
        leave.setReviewedAt(LocalDateTime.now());

        if (auditService != null) {
            auditService.log(AuditAction.LEAVE_REJECTED, "LEAVE_REQUEST", leave.getId(), null,
                    leave.getEmployee().getId(), leave.getEmployee().getFirstName() + " " + leave.getEmployee().getLastName(),
                    "PENDING_CANCELLATION", "APPROVED", "Admin rejected leave cancellation", "MANUAL");
        }

        if (activityLogService != null) {
            activityLogService.logEmployeeActivity(
                    leave.getEmployee(),
                    ActivityCategory.LEAVE,
                    "LEAVE_CANCELLATION_REJECTED",
                    ActivityStatus.FAILED,
                    "Leave cancellation was rejected by administrator. Leave remains active."
            );
        }

        if (notificationService != null) {
            notificationService.notifyEmployee(leave.getEmployee(), "Leave Cancellation Rejected",
                    "Your request to cancel leave was rejected. Leave remains active.",
                    NotificationType.LEAVE_DECISION, "leaves", leave.getId());
        }

        return toResponse(leave);
    }

    private void verifyEmployeeAccess(Long targetEmployeeId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new org.springframework.security.access.AccessDeniedException("Authentication required");
        }
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!isAdmin) {
            Employee employee = employeeRepository.findByUserUsername(authentication.getName())
                    .orElseThrow(() -> new ResourceNotFoundException("Employee profile not found"));
            if (!employee.getId().equals(targetEmployeeId)) {
                throw new org.springframework.security.access.AccessDeniedException("Access denied: You can only access your own leave requests");
            }
        }
    }

    public LeaveResponse toResponse(LeaveRequest leave) {
        Employee employee = leave.getEmployee();
        return new LeaveResponse(
                leave.getId(),
                employee.getId(),
                employee.getEmployeeCode(),
                employee.getFirstName() + " " + employee.getLastName(),
                leave.getStartDate(),
                leave.getEndDate(),
                leave.getReason(),
                leave.getStatus(),
                leave.getAdminRemarks(),
                leave.getRequestedAt(),
                leave.getReviewedAt(),
                leave.getOriginalStartDate(),
                leave.getOriginalEndDate(),
                leave.getPendingStartDate(),
                leave.getPendingEndDate(),
                leave.getModificationReason(),
                leave.getCancellationReason(),
                leave.getModifiedAt()
        );
    }
}
