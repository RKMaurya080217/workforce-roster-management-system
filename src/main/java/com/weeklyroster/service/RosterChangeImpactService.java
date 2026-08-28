package com.weeklyroster.service;

import com.weeklyroster.dto.ApplicablePreference;
import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.dto.response.RosterChangeImpactResponse;
import com.weeklyroster.dto.response.RosterHealthReport;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RosterChangeImpactService {

    private final RosterAssignmentRepository assignmentRepository;
    private final RosterCycleRepository cycleRepository;
    private final RosterHealthService healthService;
    private final RosterService rosterService;
    private final EmployeePreferenceRepository preferenceRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final RosterOverrideRepository overrideRepository;
    private final ShiftRepository shiftRepository;
    private final AuditService auditService;
    private final RosterVersionService rosterVersionService;
    private final NotificationService notificationService;

    public RosterChangeImpactService(
            RosterAssignmentRepository assignmentRepository,
            RosterCycleRepository cycleRepository,
            RosterHealthService healthService,
            RosterService rosterService,
            EmployeePreferenceRepository preferenceRepository,
            LeaveRequestRepository leaveRequestRepository,
            RosterOverrideRepository overrideRepository,
            ShiftRepository shiftRepository,
            AuditService auditService,
            NotificationService notificationService
    ) {
        this(assignmentRepository, cycleRepository, healthService, rosterService, preferenceRepository,
             leaveRequestRepository, overrideRepository, shiftRepository, auditService, null, notificationService);
    }

    @Autowired
    public RosterChangeImpactService(
            RosterAssignmentRepository assignmentRepository,
            RosterCycleRepository cycleRepository,
            RosterHealthService healthService,
            RosterService rosterService,
            EmployeePreferenceRepository preferenceRepository,
            LeaveRequestRepository leaveRequestRepository,
            RosterOverrideRepository overrideRepository,
            ShiftRepository shiftRepository,
            AuditService auditService,
                                    @Autowired(required = false) RosterVersionService rosterVersionService,
            NotificationService notificationService
    ) {
        this.assignmentRepository = assignmentRepository;
        this.cycleRepository = cycleRepository;
        this.healthService = healthService;
        this.rosterService = rosterService;
        this.preferenceRepository = preferenceRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.overrideRepository = overrideRepository;
        this.shiftRepository = shiftRepository;
        this.auditService = auditService;
        this.rosterVersionService = rosterVersionService;
        this.notificationService = notificationService;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public RosterChangeImpactResponse previewImpact(Long assignmentId, ShiftType newShiftType, boolean weeklyOff) {
        RosterAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Roster assignment not found with id: " + assignmentId));

        Employee employee = assignment.getEmployee();
        LocalDate date = assignment.getRosterDate();
        RosterCycle cycle = assignment.getCycle();
        String dayOfWeek = date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);

        ShiftType currentShiftType = assignment.getShift() != null ? assignment.getShift().getShiftType() : ShiftType.OFF;
        boolean currentWeeklyOff = assignment.isWeeklyOff();

        ShiftType proposedShiftType = weeklyOff ? ShiftType.OFF : (newShiftType != null ? newShiftType : currentShiftType);

        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> positivePoints = new ArrayList<>();

        // 1. Validate Final / Locked Roster
        if (cycle != null && (cycle.getStatus() == RosterStatus.FINAL || cycle.getLockedAt() != null)) {
            blockers.add("Roster cycle is FINAL / LOCKED. Normal manual changes are disabled.");
        }

        // 2. Validate Approved Leave
        if (assignment.isOnLeave()) {
            blockers.add("Employee is on approved leave on " + date + ".");
        } else {
            List<LeaveRequest> approvedLeaves = leaveRequestRepository.findByStatusOrderByRequestedAtAsc(LeaveStatus.APPROVED);
            boolean onLeave = approvedLeaves.stream().anyMatch(l ->
                    l.getEmployee().getId().equals(employee.getId()) &&
                    !date.isBefore(l.getStartDate()) && !date.isAfter(l.getEndDate())
            );
            if (onLeave && !weeklyOff) {
                blockers.add("Employee has an approved leave scheduled for " + date + ".");
            }
        }

        // 3. Validate Female Restrictions
        String genderImpact = "Safe";
        String genderMessage = "Compliant with female workforce daytime scheduling policy";
        if (employee.getGender() == Gender.FEMALE) {
            if (proposedShiftType == ShiftType.EVENING || proposedShiftType == ShiftType.NIGHT) {
                genderImpact = "Blocked";
                genderMessage = "Female employees cannot be assigned to " + proposedShiftType + " shift under safety regulations.";
                blockers.add("Female safety policy violation: " + proposedShiftType + " shift forbidden for female staff.");
            }
        }

        // 4. Validate 12-Hour Rest Rule (Previous and Next Day)
        String restImpact = "Safe";
        String restMessage = "Maintains mandatory >= 12-hour rest interval";

        Map<ShiftType, Shift> shiftMap = getActiveShiftMap();
        Shift proposedShift = shiftMap.get(proposedShiftType);

        if (!weeklyOff && proposedShiftType != ShiftType.OFF) {
            // Previous day rest
            List<RosterAssignment> prevList = assignmentRepository.findByEmployeeIdAndRosterDate(employee.getId(), date.minusDays(1));
            if (!prevList.isEmpty()) {
                RosterAssignment prev = prevList.get(0);
                if (!prev.isWeeklyOff() && !prev.isOnLeave() && prev.getShift() != null && prev.getShift().getShiftType() != ShiftType.OFF) {
                    if (!rosterService.hasMinimumRest(date.minusDays(1), prev.getShift(), date, proposedShift)) {
                        restImpact = "Blocked";
                        restMessage = "12-hour rest rule violated from previous day (" + prev.getShift().getShiftType() + " → " + proposedShiftType + ").";
                        blockers.add(restMessage);
                    }
                }
            }

            // Next day rest
            List<RosterAssignment> nextList = assignmentRepository.findByEmployeeIdAndRosterDate(employee.getId(), date.plusDays(1));
            if (!nextList.isEmpty()) {
                RosterAssignment next = nextList.get(0);
                if (!next.isWeeklyOff() && !next.isOnLeave() && next.getShift() != null && next.getShift().getShiftType() != ShiftType.OFF) {
                    if (!rosterService.hasMinimumRest(date, proposedShift, date.plusDays(1), next.getShift())) {
                        restImpact = "Blocked";
                        restMessage = "12-hour rest rule violated into next day (" + proposedShiftType + " → " + next.getShift().getShiftType() + ").";
                        blockers.add(restMessage);
                    }
                }
            }
        }

        // 5. Validate Night Shift Limits
        String nightImpact = "Unchanged";
        String nightMessage = "Night quota unaffected";

        List<RosterAssignment> cycleAssignments = (cycle != null)
                ? assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(cycle)
                : Collections.emptyList();

        long currentEmpNights = cycleAssignments.stream()
                .filter(a -> a.getEmployee().getId().equals(employee.getId()) && !a.getId().equals(assignment.getId()))
                .filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT)
                .count();

        if (proposedShiftType == ShiftType.NIGHT) {
            long newEmpNights = currentEmpNights + 1;
            if (newEmpNights > 2) {
                nightImpact = "Blocked";
                nightMessage = "Exceeds maximum 2 Night shifts per cycle (would become " + newEmpNights + ").";
                blockers.add("Maximum 2 Night shifts limit exceeded for " + employee.getFirstName() + ".");
            } else {
                nightImpact = "Safe";
                nightMessage = "Within allowed limit (" + newEmpNights + " of 2 maximum).";
                positivePoints.add("Valid Night duty allocation.");
            }
        } else if (currentShiftType == ShiftType.NIGHT) {
            nightImpact = "Safe";
            nightMessage = "Reduced from Night duty (now " + currentEmpNights + " Night(s)).";
        }

        // 6. Evaluate Preference Compliance
        String preferenceImpact = "Neutral";
        String preferenceMessage = "No specific preference conflict";

        ApplicablePreference pref = loadEmployeePreference(employee);
        if (pref != null) {
            if (weeklyOff) {
                if (pref.isDayPreferredOff(date.getDayOfWeek())) {
                    preferenceImpact = "Improved";
                    preferenceMessage = "Matches employee's approved preferred weekly off day (" + dayOfWeek + ").";
                    positivePoints.add(preferenceMessage);
                }
            } else {
                if (pref.isShiftAvoided(proposedShiftType)) {
                    preferenceImpact = "Avoided";
                    preferenceMessage = "Employee preferred to avoid " + proposedShiftType + " shifts.";
                    warnings.add("Employee avoid-shift conflict: Preferred to avoid " + proposedShiftType + ".");
                } else if (pref.isShiftPreferred(proposedShiftType)) {
                    preferenceImpact = "Improved";
                    preferenceMessage = "Matches employee's approved preferred shift (" + proposedShiftType + ").";
                    positivePoints.add(preferenceMessage);
                }
            }
        }

        // 7. Evaluate In-Memory Health Delta & Coverage Delta
        Double currentHealthScore = 94.0;
        Double projectedHealthScore = 94.0;
        String coverageImpact = "Safe";
        String coverageMessage = "Shift staffing targets satisfied";
        String teamImpactMessage = "No adverse impact on team coverage";

        if (cycle != null && !cycleAssignments.isEmpty()) {
            RosterHealthReport beforeReport = healthService.evaluateHealth(cycle, cycleAssignments);
            currentHealthScore = beforeReport.healthScore();

            // Create in-memory simulated assignments
            List<RosterAssignment> simulated = new ArrayList<>();
            for (RosterAssignment a : cycleAssignments) {
                if (a.getId().equals(assignment.getId())) {
                    RosterAssignment copy = new RosterAssignment();
                    copy.setId(a.getId());
                    copy.setCycle(a.getCycle());
                    copy.setEmployee(a.getEmployee());
                    copy.setRosterDate(a.getRosterDate());
                    copy.setWeeklyOff(weeklyOff);
                    copy.setOnLeave(a.isOnLeave());
                    copy.setShift(proposedShift);
                    copy.setOverridden(true);
                    simulated.add(copy);
                } else {
                    simulated.add(a);
                }
            }

            RosterHealthReport afterReport = healthService.evaluateHealth(cycle, simulated);
            projectedHealthScore = afterReport.healthScore();

            // Check if removing old shift created a daily coverage deficit
            if (currentShiftType != proposedShiftType && !currentWeeklyOff) {
                long remainingOnOldShift = simulated.stream()
                        .filter(a -> a.getRosterDate().equals(date) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == currentShiftType)
                        .count();
                if (remainingOnOldShift == 0 && (currentShiftType == ShiftType.MORNING || currentShiftType == ShiftType.GENERAL || currentShiftType == ShiftType.EVENING || currentShiftType == ShiftType.NIGHT)) {
                    coverageImpact = "Warning";
                    coverageMessage = "May leave " + currentShiftType + " shift unstaffed on " + date + ".";
                    teamImpactMessage = "Changing " + employee.getFirstName() + " to " + proposedShiftType + " leaves " + currentShiftType + " with 0 assigned staff.";
                    warnings.add(teamImpactMessage);
                }
            }
        }

        // 8. Evaluate Shift Continuity Impact
        String continuityImpact = "Neutral";
        String continuityMessage = "Shift rotation sequence maintained";

        if (projectedHealthScore > currentHealthScore) {
            continuityImpact = "Improved";
            continuityMessage = "Shift continuity and multi-day block consistency improved.";
            positivePoints.add("Improves multi-day shift continuity.");
        } else if (projectedHealthScore < currentHealthScore && blockers.isEmpty()) {
            continuityImpact = "Degraded";
            continuityMessage = "Introduces additional shift switching.";
            warnings.add("May reduce multi-day shift continuity.");
        }

        // 9. Workload Impact
        String workloadImpact = "Neutral";
        String workloadMessage = "Workload remains unchanged";
        if (currentWeeklyOff && !weeklyOff) {
            workloadImpact = "Increased";
            workloadMessage = "Duty days increase (+8 working hours).";
            positivePoints.add("Adds working duty to active roster.");
        } else if (!currentWeeklyOff && weeklyOff) {
            workloadImpact = "Decreased";
            workloadMessage = "Duty days decrease (-8 working hours).";
        }

        // 10. Overall Status Determination
        String impactStatus;
        String impactBadgeLabel;
        boolean canApply;
        boolean requiresAdminConfirmation;

        if (!blockers.isEmpty()) {
            impactStatus = "BLOCKED";
            impactBadgeLabel = "🔴 BLOCKED — HARD CONSTRAINT VIOLATION";
            canApply = false;
            requiresAdminConfirmation = false;
        } else if (!warnings.isEmpty()) {
            impactStatus = "WARNING";
            impactBadgeLabel = "🟠 WARNING — OPERATIONAL IMPACT";
            canApply = true;
            requiresAdminConfirmation = true;
        } else {
            impactStatus = "SAFE";
            impactBadgeLabel = "🟢 SAFE TO APPLY";
            canApply = true;
            requiresAdminConfirmation = false;
        }

        return new RosterChangeImpactResponse(
                assignment.getId(),
                cycle != null ? cycle.getId() : null,
                employee.getId(),
                employee.getEmployeeCode(),
                employee.getFirstName() + " " + employee.getLastName(),
                employee.getGender(),
                date,
                dayOfWeek,
                currentShiftType,
                proposedShiftType,
                currentWeeklyOff,
                weeklyOff,
                currentHealthScore,
                projectedHealthScore,
                impactStatus,
                impactBadgeLabel,
                canApply,
                requiresAdminConfirmation,
                coverageImpact,
                coverageMessage,
                restImpact,
                restMessage,
                preferenceImpact,
                preferenceMessage,
                continuityImpact,
                continuityMessage,
                workloadImpact,
                workloadMessage,
                nightImpact,
                nightMessage,
                genderImpact,
                genderMessage,
                teamImpactMessage,
                blockers,
                warnings,
                positivePoints
        );
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public RosterAssignmentResponse applyChangeWithValidation(Long assignmentId, ShiftType newShiftType, boolean weeklyOff, String reason) {
        RosterChangeImpactResponse impact = previewImpact(assignmentId, newShiftType, weeklyOff);
        if (!impact.canApply()) {
            String firstBlocker = !impact.blockers().isEmpty() ? impact.blockers().get(0) : "Cannot apply change due to safety policy violation.";
            throw new BusinessException("Roster change blocked: " + firstBlocker);
        }

        RosterAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Roster assignment not found with id: " + assignmentId));

        ShiftType previousShiftType = assignment.getShift() != null ? assignment.getShift().getShiftType() : ShiftType.OFF;
        Map<ShiftType, Shift> shiftMap = getActiveShiftMap();

        assignment.setOverridden(true);
        assignment.setWeeklyOff(weeklyOff);
        if (weeklyOff) {
            assignment.setShift(shiftMap.get(ShiftType.OFF));
        } else if (newShiftType != null) {
            assignment.setShift(shiftMap.get(newShiftType));
        }

        String effectiveReason = (reason != null && !reason.isBlank()) ? reason : "Admin Manual Override";
        assignment.setAssignmentReason("Admin Override: " + effectiveReason);

        RosterAssignment saved = assignmentRepository.save(assignment);

        // Record Override Entity
        RosterOverride override = new RosterOverride();
        override.setAssignment(saved);
        override.setPreviousShiftType(previousShiftType);
        override.setNewShiftType(weeklyOff ? ShiftType.OFF : newShiftType);
        override.setWeeklyOff(weeklyOff);
        override.setReason(effectiveReason);
        override.setCreatedAt(LocalDateTime.now());
        overrideRepository.save(override);

        // Record Audit Trail
        auditService.log(
                AuditAction.SHIFT_OVERRIDDEN,
                "ROSTER_ASSIGNMENT",
                saved.getId(),
                saved.getCycle() != null ? saved.getCycle().getId() : null,
                saved.getEmployee() != null ? saved.getEmployee().getId() : null,
                saved.getEmployee() != null ? saved.getEmployee().getFirstName() + " " + saved.getEmployee().getLastName() : null,
                previousShiftType.name(),
                (weeklyOff ? "OFF" : newShiftType.name()),
                effectiveReason,
                "ADMIN"
        );

        // Notification Dispatch
        if (saved.getEmployee() != null && saved.getEmployee().getUser() != null) {
            notificationService.createNotification(
                    saved.getEmployee().getUser().getUsername(),
                    saved.getEmployee().getId(),
                    "Roster Assignment Updated",
                    "Your roster assignment for " + saved.getRosterDate() + " was updated to " + (weeklyOff ? "Weekly OFF" : newShiftType) + " by Administrator.",
                    NotificationType.ROSTER_PUBLISHED,
                    "roster",
                    saved.getId()
            );
        }

                // Record Version Snapshot
        if (rosterVersionService != null && saved.getCycle() != null) {
            String actor = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication() != null
                    ? org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName()
                    : "admin";
            rosterVersionService.recordVersionSnapshot(
                    saved.getCycle(),
                    "ADMIN_MODIFICATION",
                    effectiveReason,
                    actor,
                    (int) Math.round(impact.projectedHealthScore()),
                    "Impact: Coverage=" + impact.coverageImpact() + ", Rest=" + impact.restImpact() + ", Preference=" + impact.preferenceImpact()
            );
        }

        return toAssignmentResponse(saved);
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

    private ApplicablePreference loadEmployeePreference(Employee employee) {
        Optional<EmployeePreference> opt = preferenceRepository.findTopByEmployeeIdAndStatusOrderByCreatedAtDesc(employee.getId(), PreferenceStatus.APPROVED);
        if (opt.isEmpty()) {
            List<EmployeePreference> list = preferenceRepository.findByEmployeeIdOrderByCreatedAtDesc(employee.getId());
            if (!list.isEmpty()) opt = Optional.of(list.get(0));
        }
        if (opt.isEmpty()) return null;
        EmployeePreference p = opt.get();
        return new ApplicablePreference(
                p.getId(),
                employee.getId(),
                parseShiftTypes(p.getPreferredShiftTypes()),
                parseShiftTypes(p.getAvoidShiftTypes()),
                parseDaysOfWeek(p.getPreferredOffDays()),
                parseDaysOfWeek(p.getPreferredWorkingDays()),
                p.getTemporaryRestrictions(),
                true
        );
    }

    private Set<ShiftType> parseShiftTypes(String text) {
        if (text == null || text.isBlank()) return Collections.emptySet();
        Set<ShiftType> set = EnumSet.noneOf(ShiftType.class);
        for (String p : text.toUpperCase().split("[,;|/\\s]+")) {
            String trimmed = p.trim();
            if (trimmed.isEmpty()) continue;
            try {
                set.add(ShiftType.valueOf(trimmed));
            } catch (Exception ignored) {}
        }
        return set;
    }

    private Set<DayOfWeek> parseDaysOfWeek(String text) {
        if (text == null || text.isBlank()) return Collections.emptySet();
        Set<DayOfWeek> set = EnumSet.noneOf(DayOfWeek.class);
        for (String p : text.toUpperCase().split("[,;|/\\s]+")) {
            String u = p.trim();
            if (u.startsWith("MON")) set.add(DayOfWeek.MONDAY);
            else if (u.startsWith("TUE")) set.add(DayOfWeek.TUESDAY);
            else if (u.startsWith("WED")) set.add(DayOfWeek.WEDNESDAY);
            else if (u.startsWith("THU")) set.add(DayOfWeek.THURSDAY);
            else if (u.startsWith("FRI")) set.add(DayOfWeek.FRIDAY);
            else if (u.startsWith("SAT")) set.add(DayOfWeek.SATURDAY);
            else if (u.startsWith("SUN")) set.add(DayOfWeek.SUNDAY);
        }
        return set;
    }

    private Map<ShiftType, Shift> getActiveShiftMap() {
        Map<ShiftType, Shift> map = new EnumMap<>(ShiftType.class);
        for (Shift s : shiftRepository.findAll()) {
            map.put(s.getShiftType(), s);
        }
        return map;
    }
}
