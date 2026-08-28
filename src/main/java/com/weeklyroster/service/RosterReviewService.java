package com.weeklyroster.service;

import com.weeklyroster.dto.ApplicablePreference;
import com.weeklyroster.dto.request.RosterChangeDecisionRequest;
import com.weeklyroster.dto.request.SubmitRosterChangeRequest;
import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RosterReviewService {

    private final RosterChangeRequestRepository changeRequestRepository;
    private final RosterReviewRecordRepository reviewRecordRepository;
    private final RosterAssignmentRepository assignmentRepository;
    private final RosterCycleRepository cycleRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeePreferenceRepository preferenceRepository;
    private final RosterChangeImpactService impactService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final RosterHealthService healthService;

    @Autowired
    public RosterReviewService(
            RosterChangeRequestRepository changeRequestRepository,
            RosterReviewRecordRepository reviewRecordRepository,
            RosterAssignmentRepository assignmentRepository,
            RosterCycleRepository cycleRepository,
            EmployeeRepository employeeRepository,
            EmployeePreferenceRepository preferenceRepository,
            RosterChangeImpactService impactService,
            NotificationService notificationService,
            AuditService auditService,
            RosterHealthService healthService
    ) {
        this.changeRequestRepository = changeRequestRepository;
        this.reviewRecordRepository = reviewRecordRepository;
        this.assignmentRepository = assignmentRepository;
        this.cycleRepository = cycleRepository;
        this.employeeRepository = employeeRepository;
        this.preferenceRepository = preferenceRepository;
        this.impactService = impactService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.healthService = healthService;
    }

    @Transactional(readOnly = true)
    public EmployeeRosterReviewSummaryResponse getEmployeeReviewSummary(Long cycleId, String username) {
        Employee employee = employeeRepository.findByUserUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("Employee profile not found for user: " + username));

        RosterCycle cycle = resolveCycle(cycleId);
        if (cycle == null) {
            return new EmployeeRosterReviewSummaryResponse(
                    null, null, null, null, null, false,
                    "NO_ACTIVE_CYCLE", "⚪ No Active Roster Cycle", 0, 0, 0, 0,
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList()
            );
        }

        LocalDateTime deadline = calculateReviewDeadline(cycle);
        boolean isFinalOrLocked = (cycle.getStatus() == RosterStatus.FINAL || cycle.getLockedAt() != null);
        boolean isPastDeadline = LocalDateTime.now().isAfter(deadline);
        boolean isReviewOpen = !isFinalOrLocked && !isPastDeadline;

        List<RosterAssignment> employeeAssignments = assignmentRepository.findByEmployeeIdAndRosterDateBetween(
                employee.getId(), cycle.getStartDate(), cycle.getEndDate()
        );

        List<RosterAssignmentResponse> assignmentResponses = employeeAssignments.stream()
                .map(this::toAssignmentResponse)
                .collect(Collectors.toList());

        List<RosterChangeRequest> allEmpRequests = changeRequestRepository.findByEmployeeIdOrderByCreatedAtDesc(employee.getId());
        List<RosterChangeRequest> cycleRequests = allEmpRequests.stream()
                .filter(r -> r.getCycle() != null && r.getCycle().getId().equals(cycle.getId()))
                .collect(Collectors.toList());

        List<RosterChangeRequestResponse> pending = cycleRequests.stream()
                .filter(r -> r.getStatus() == RosterChangeStatus.PENDING)
                .map(this::toRequestResponse)
                .collect(Collectors.toList());

        List<RosterChangeRequestResponse> history = cycleRequests.stream()
                .filter(r -> r.getStatus() != RosterChangeStatus.PENDING)
                .map(this::toRequestResponse)
                .collect(Collectors.toList());

        int pendingCount = pending.size();
        int approvedCount = (int) cycleRequests.stream().filter(r -> r.getStatus() == RosterChangeStatus.APPROVED).count();
        int rejectedCount = (int) cycleRequests.stream().filter(r -> r.getStatus() == RosterChangeStatus.REJECTED).count();

        boolean hasReviewed = reviewRecordRepository.existsByEmployeeIdAndCycleId(employee.getId(), cycle.getId());

        String reviewStatus;
        String reviewStatusBadge;

        if (isFinalOrLocked) {
            reviewStatus = "LOCKED";
            reviewStatusBadge = "🔒 FINAL — LOCKED";
        } else if (pendingCount > 0) {
            reviewStatus = "CHANGE_REQUEST_PENDING";
            reviewStatusBadge = "🟠 CHANGE REQUEST PENDING";
        } else if (hasReviewed) {
            reviewStatus = "REVIEWED";
            reviewStatusBadge = "✓ REVIEW COMPLETED";
        } else {
            reviewStatus = "ACTION_REQUIRED";
            reviewStatusBadge = "🟠 ACTION REQUIRED";
        }

        List<String> prefShifts = new ArrayList<>();
        List<String> avoidShifts = new ArrayList<>();
        List<String> prefOffs = new ArrayList<>();

        Optional<EmployeePreference> optPref = preferenceRepository.findTopByEmployeeIdAndStatusOrderByCreatedAtDesc(employee.getId(), PreferenceStatus.APPROVED);
        if (optPref.isPresent()) {
            EmployeePreference p = optPref.get();
            if (p.getPreferredShiftTypes() != null) prefShifts = Arrays.asList(p.getPreferredShiftTypes().split("[,;|/\\s]+"));
            if (p.getAvoidShiftTypes() != null) avoidShifts = Arrays.asList(p.getAvoidShiftTypes().split("[,;|/\\s]+"));
            if (p.getPreferredOffDays() != null) prefOffs = Arrays.asList(p.getPreferredOffDays().split("[,;|/\\s]+"));
        }

        return new EmployeeRosterReviewSummaryResponse(
                cycle.getId(),
                cycle.getStartDate(),
                cycle.getEndDate(),
                cycle.getStatus(),
                deadline,
                isReviewOpen,
                reviewStatus,
                reviewStatusBadge,
                assignmentResponses.size(),
                pendingCount,
                approvedCount,
                rejectedCount,
                assignmentResponses,
                pending,
                history,
                prefShifts,
                avoidShifts,
                prefOffs
        );
    }

    @Transactional
    public RosterChangeRequestResponse submitChangeRequest(SubmitRosterChangeRequest req, String username) {
        Employee employee = employeeRepository.findByUserUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("Employee profile not found for user: " + username));

        RosterAssignment assignment = assignmentRepository.findById(req.assignmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found with id: " + req.assignmentId()));

        if (!assignment.getEmployee().getId().equals(employee.getId())) {
            throw new AccessDeniedException("You are not authorized to request changes for another employee's assignment.");
        }

        RosterCycle cycle = assignment.getCycle();
        if (cycle != null) {
            LocalDateTime deadline = calculateReviewDeadline(cycle);
            if (cycle.getStatus() == RosterStatus.FINAL || cycle.getLockedAt() != null || LocalDateTime.now().isAfter(deadline)) {
                throw new BusinessException("Roster review window is closed for cycle #" + cycle.getId() + ". Changes cannot be submitted after the Sunday 4:00 PM deadline.");
            }
        }

        Optional<RosterChangeRequest> existingPending = changeRequestRepository.findByAssignmentIdAndStatus(assignment.getId(), RosterChangeStatus.PENDING);
        if (existingPending.isPresent()) {
            throw new BusinessException("A change request for this assignment (" + assignment.getRosterDate() + ") is already pending admin review.");
        }

        ShiftType proposedShift = req.requestedWeeklyOff() ? ShiftType.OFF : (req.requestedShiftType() != null ? req.requestedShiftType() : ShiftType.OFF);
        if (employee.getGender() == Gender.FEMALE && (proposedShift == ShiftType.EVENING || proposedShift == ShiftType.NIGHT)) {
            throw new BusinessException("Female safety regulation: Female employees cannot request Evening or Night shifts.");
        }

        ShiftType currentShift = (assignment.getShift() != null) ? assignment.getShift().getShiftType() : ShiftType.OFF;

        RosterChangeRequest changeRequest = new RosterChangeRequest();
        changeRequest.setEmployee(employee);
        changeRequest.setCycle(cycle);
        changeRequest.setAssignment(assignment);
        changeRequest.setRosterDate(assignment.getRosterDate());
        changeRequest.setCurrentShiftType(currentShift);
        changeRequest.setCurrentWeeklyOff(assignment.isWeeklyOff());
        changeRequest.setRequestedShiftType(proposedShift);
        changeRequest.setRequestedWeeklyOff(req.requestedWeeklyOff() || proposedShift == ShiftType.OFF);
        changeRequest.setReason(req.reason().trim());
        changeRequest.setStatus(RosterChangeStatus.PENDING);
        changeRequest.setCreatedAt(LocalDateTime.now());

        RosterChangeRequest saved = changeRequestRepository.save(changeRequest);

        notificationService.createNotification(
                "admin",
                null,
                "New Roster Change Request",
                employee.getFirstName() + " " + employee.getLastName() + " requested shift change for " + assignment.getRosterDate() + " (" + currentShift + " → " + proposedShift + ").",
                NotificationType.ADMIN_ALERT,
                "approvals",
                saved.getId()
        );

        return toRequestResponse(saved);
    }

    @Transactional
    public RosterChangeRequestResponse cancelChangeRequest(Long requestId, String username) {
        Employee employee = employeeRepository.findByUserUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("Employee profile not found for user: " + username));

        RosterChangeRequest req = changeRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Change request not found with id: " + requestId));

        if (!req.getEmployee().getId().equals(employee.getId())) {
            throw new AccessDeniedException("You cannot cancel another employee's request.");
        }

        if (req.getStatus() != RosterChangeStatus.PENDING) {
            throw new BusinessException("Only PENDING change requests can be cancelled.");
        }

        req.setStatus(RosterChangeStatus.CANCELLED);
        req.setDecidedAt(LocalDateTime.now());
        req.setDecidedBy(username);
        RosterChangeRequest saved = changeRequestRepository.save(req);

        return toRequestResponse(saved);
    }

    @Transactional
    public boolean markReviewComplete(Long cycleId, String username) {
        Employee employee = employeeRepository.findByUserUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("Employee profile not found for user: " + username));

        RosterCycle cycle = resolveCycle(cycleId);
        if (cycle == null) throw new ResourceNotFoundException("Active roster cycle not found.");

        Optional<RosterReviewRecord> existing = reviewRecordRepository.findByEmployeeIdAndCycleId(employee.getId(), cycle.getId());
        if (existing.isEmpty()) {
            RosterReviewRecord record = new RosterReviewRecord(employee, cycle);
            reviewRecordRepository.save(record);
        }
        return true;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public TeamRosterReviewSummaryResponse getTeamReviewSummary(Long cycleId) {
        RosterCycle cycle = resolveCycle(cycleId);
        long totalEmp = employeeRepository.countByActiveTrue();
        if (totalEmp == 0) totalEmp = 7;

        long reviewedCount = (cycle != null) ? reviewRecordRepository.countByCycleId(cycle.getId()) : 0;
        long pendingReviewCount = Math.max(0, totalEmp - reviewedCount);

        List<RosterChangeRequest> allRequests = (cycle != null)
                ? changeRequestRepository.findByCycleIdOrderByCreatedAtDesc(cycle.getId())
                : changeRequestRepository.findAll();

        List<RosterChangeRequestResponse> pendingResponses = allRequests.stream()
                .filter(r -> r.getStatus() == RosterChangeStatus.PENDING)
                .map(this::toRequestResponse)
                .collect(Collectors.toList());

        long pendingCount = pendingResponses.size();
        long approvedCount = allRequests.stream().filter(r -> r.getStatus() == RosterChangeStatus.APPROVED).count();
        long rejectedCount = allRequests.stream().filter(r -> r.getStatus() == RosterChangeStatus.REJECTED).count();

        String attentionStatus = (pendingCount > 0)
                ? "🟠 ACTION REQUIRED: " + pendingCount + " shift change request(s) pending"
                : "✓ No pending roster requests";

        return new TeamRosterReviewSummaryResponse(
                cycle != null ? cycle.getId() : null,
                totalEmp,
                reviewedCount,
                pendingReviewCount,
                pendingCount,
                approvedCount,
                rejectedCount,
                attentionStatus,
                pendingResponses
        );
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public RosterChangeRequestResponse decideChangeRequest(Long requestId, boolean approve, RosterChangeDecisionRequest decision, String adminUsername) {
        RosterChangeRequest req = changeRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Change request not found with id: " + requestId));

        if (req.getStatus() != RosterChangeStatus.PENDING) {
            throw new BusinessException("Request has already been processed with status: " + req.getStatus());
        }

        Employee employee = req.getEmployee();
        RosterAssignment assignment = req.getAssignment();

        if (approve) {
            RosterChangeImpactResponse impact = impactService.previewImpact(
                    assignment.getId(),
                    req.getRequestedShiftType(),
                    req.isRequestedWeeklyOff()
            );

            if (!impact.canApply()) {
                String firstBlocker = !impact.blockers().isEmpty() ? impact.blockers().get(0) : "Violates hard constraints.";
                throw new BusinessException("Cannot approve request: " + firstBlocker);
            }

            String effectiveReason = (decision != null && decision.overrideReason() != null && !decision.overrideReason().isBlank())
                    ? decision.overrideReason()
                    : ("Employee Request: " + req.getReason());

            impactService.applyChangeWithValidation(
                    assignment.getId(),
                    req.getRequestedShiftType(),
                    req.isRequestedWeeklyOff(),
                    effectiveReason
            );

            req.setStatus(RosterChangeStatus.APPROVED);
            req.setAdminRemarks(decision != null ? decision.adminRemarks() : "Approved by Administrator");
            req.setDecidedAt(LocalDateTime.now());
            req.setDecidedBy(adminUsername);
            RosterChangeRequest saved = changeRequestRepository.save(req);

            if (employee.getUser() != null) {
                notificationService.createNotification(
                        employee.getUser().getUsername(),
                        employee.getId(),
                        "Roster Change Request Approved",
                        "Your roster change request for " + req.getRosterDate() + " (" + req.getRequestedShiftType() + ") has been approved by Administrator.",
                        NotificationType.ROSTER_PUBLISHED,
                        "roster",
                        req.getCycle() != null ? req.getCycle().getId() : null
                );
            }

            return toRequestResponse(saved);

        } else {
            req.setStatus(RosterChangeStatus.REJECTED);
            String remarks = (decision != null && decision.adminRemarks() != null && !decision.adminRemarks().isBlank())
                    ? decision.adminRemarks()
                    : "Request declined due to operational requirements.";
            req.setAdminRemarks(remarks);
            req.setDecidedAt(LocalDateTime.now());
            req.setDecidedBy(adminUsername);
            RosterChangeRequest saved = changeRequestRepository.save(req);

            if (employee.getUser() != null) {
                notificationService.createNotification(
                        employee.getUser().getUsername(),
                        employee.getId(),
                        "Roster Change Request Rejected",
                        "Your roster change request for " + req.getRosterDate() + " was not approved. Remark: " + remarks,
                        NotificationType.ROSTER_PUBLISHED,
                        "roster",
                        req.getCycle() != null ? req.getCycle().getId() : null
                );
            }

            return toRequestResponse(saved);
        }
    }

    private RosterCycle resolveCycle(Long cycleId) {
        if (cycleId != null) {
            return cycleRepository.findById(cycleId).orElse(null);
        }
        List<RosterCycle> all = cycleRepository.findAllByOrderByStartDateDesc();
        for (RosterCycle c : all) {
            if (c.getStatus() == RosterStatus.TENTATIVE) return c;
        }
        for (RosterCycle c : all) {
            if (c.getStatus() == RosterStatus.PUBLISHED) return c;
        }
        return all.isEmpty() ? null : all.get(0);
    }

    private LocalDateTime calculateReviewDeadline(RosterCycle cycle) {
        LocalDate cycleStart = cycle.getStartDate();
        LocalDate sundayBefore = cycleStart.minusDays(1);
        if (sundayBefore.getDayOfWeek() != DayOfWeek.SUNDAY) {
            sundayBefore = cycleStart.with(DayOfWeek.SUNDAY);
        }
        return LocalDateTime.of(sundayBefore, LocalTime.of(16, 0));
    }

    private RosterAssignmentResponse toAssignmentResponse(RosterAssignment a) {
        return new RosterAssignmentResponse(
                a.getId(),
                a.getCycle() != null ? a.getCycle().getId() : null,
                a.getRosterDate(),
                a.getEmployee() != null ? a.getEmployee().getId() : null,
                a.getEmployee() != null ? a.getEmployee().getEmployeeCode() : null,
                a.getEmployee() != null ? a.getEmployee().getFirstName() + " " + a.getEmployee().getLastName() : null,
                a.getEmployee() != null ? a.getEmployee().getGender() : null,
                a.getShift() != null ? a.getShift().getShiftType() : ShiftType.OFF,
                a.isWeeklyOff(),
                a.isOnLeave(),
                a.isOverridden(),
                a.getAssignmentReason()
        );
    }

    private RosterChangeRequestResponse toRequestResponse(RosterChangeRequest r) {
        Employee emp = r.getEmployee();
        String dayOfWeek = r.getRosterDate().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        boolean canCancel = (r.getStatus() == RosterChangeStatus.PENDING);

        return new RosterChangeRequestResponse(
                r.getId(),
                emp.getId(),
                emp.getEmployeeCode(),
                emp.getFirstName() + " " + emp.getLastName(),
                emp.getGender(),
                r.getCycle() != null ? r.getCycle().getId() : null,
                r.getAssignment() != null ? r.getAssignment().getId() : null,
                r.getRosterDate(),
                dayOfWeek,
                r.getCurrentShiftType(),
                r.isCurrentWeeklyOff(),
                r.getRequestedShiftType(),
                r.isRequestedWeeklyOff(),
                r.getReason(),
                r.getStatus(),
                r.getAdminRemarks(),
                r.getCreatedAt(),
                r.getDecidedAt(),
                r.getDecidedBy(),
                canCancel
        );
    }
}
