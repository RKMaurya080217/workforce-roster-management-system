package com.weeklyroster.service;

import com.weeklyroster.dto.response.RosterValidationFinding;
import com.weeklyroster.dto.response.RosterValidationResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.LeaveRequestRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.ShiftRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class RosterValidatorService {

    private final RosterCycleRepository cycleRepository;
    private final RosterAssignmentRepository assignmentRepository;
    private final ShiftRepository shiftRepository;
    private final LeaveRequestRepository leaveRequestRepository;

    public RosterValidatorService(RosterCycleRepository cycleRepository,
                                  RosterAssignmentRepository assignmentRepository,
                                  ShiftRepository shiftRepository,
                                  LeaveRequestRepository leaveRequestRepository) {
        this.cycleRepository = cycleRepository;
        this.assignmentRepository = assignmentRepository;
        this.shiftRepository = shiftRepository;
        this.leaveRequestRepository = leaveRequestRepository;
    }

    public RosterValidationResponse validateRoster(Long cycleId) {
        RosterCycle cycle = cycleRepository.findById(cycleId)
                .orElseThrow(() -> new ResourceNotFoundException("Roster cycle not found with id: " + cycleId));

        List<RosterAssignment> assignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycleId);
        List<Shift> activeShifts = shiftRepository.findByActiveTrueOrderByIdAsc();
        Map<ShiftType, Shift> shiftMap = activeShifts.stream()
                .collect(Collectors.toMap(Shift::getShiftType, s -> s, (a, b) -> a));

        List<RosterValidationFinding> findings = new ArrayList<>();
        int checksRun = 0;

        Map<LocalDate, List<RosterAssignment>> byDate = assignments.stream()
                .collect(Collectors.groupingBy(RosterAssignment::getRosterDate));

        Map<Long, List<RosterAssignment>> byEmployee = assignments.stream()
                .collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

        LocalDate start = cycle.getStartDate();
        LocalDate end = cycle.getEndDate();

        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            checksRun++;
            List<RosterAssignment> dayAssignments = byDate.getOrDefault(d, Collections.emptyList());
            Map<ShiftType, Long> counts = dayAssignments.stream()
                    .filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null)
                    .collect(Collectors.groupingBy(a -> a.getShift().getShiftType(), Collectors.counting()));

            for (ShiftType st : ShiftType.values()) {
                long c = counts.getOrDefault(st, 0L);
                if (c == 0) {
                    findings.add(new RosterValidationFinding(
                            "ZERO_SHIFT_COVERAGE",
                            "Missing Shift Coverage",
                            ValidationSeverity.ERROR,
                            null, null, d,
                            "Zero employees assigned to " + st.name() + " shift on " + d,
                            "Required minimum coverage is at least 1 employee per shift."
                    ));
                }
            }

            for (Map.Entry<ShiftType, Long> entry : counts.entrySet()) {
                Shift s = shiftMap.get(entry.getKey());
                if (s != null && s.getCapacity() > 0 && entry.getValue() > s.getCapacity()) {
                    findings.add(new RosterValidationFinding(
                            "CAPACITY_EXCEEDED",
                            "Shift Capacity Exceeded",
                            ValidationSeverity.WARNING,
                            null, null, d,
                            entry.getKey().name() + " assigned " + entry.getValue() + " staff, exceeding capacity of " + s.getCapacity(),
                            "Review shift limits for operational balance."
                    ));
                }
            }

            Map<Long, Long> empCountsOnDate = dayAssignments.stream()
                    .collect(Collectors.groupingBy(a -> a.getEmployee().getId(), Collectors.counting()));
            for (Map.Entry<Long, Long> ec : empCountsOnDate.entrySet()) {
                if (ec.getValue() > 1) {
                    Employee emp = dayAssignments.stream().filter(a -> a.getEmployee().getId().equals(ec.getKey())).findFirst().get().getEmployee();
                    findings.add(new RosterValidationFinding(
                            "DUPLICATE_DATE_ASSIGNMENT",
                            "Duplicate Daily Assignment",
                            ValidationSeverity.ERROR,
                            emp.getEmployeeCode(),
                            emp.getFirstName() + " " + (emp.getLastName() != null ? emp.getLastName() : ""),
                            d,
                            "Employee assigned " + ec.getValue() + " times on " + d,
                            "An employee can only have exactly one shift or weekly off per day."
                    ));
                }
            }
        }

        for (Map.Entry<Long, List<RosterAssignment>> ee : byEmployee.entrySet()) {
            List<RosterAssignment> empAssignments = ee.getValue().stream()
                    .sorted(Comparator.comparing(RosterAssignment::getRosterDate))
                    .toList();
            if (empAssignments.isEmpty()) continue;

            Employee emp = empAssignments.get(0).getEmployee();
            String empName = emp.getFirstName() + " " + (emp.getLastName() != null ? emp.getLastName() : "");

            if (!emp.isActive()) {
                findings.add(new RosterValidationFinding(
                        "INACTIVE_EMPLOYEE",
                        "Inactive Employee Assigned",
                        ValidationSeverity.ERROR,
                        emp.getEmployeeCode(), empName, null,
                        "Inactive employee " + emp.getEmployeeCode() + " is assigned to the roster cycle.",
                        "Only active personnel can be included in roster generation."
                ));
            }

            long offCount = empAssignments.stream().filter(RosterAssignment::isWeeklyOff).count();
            checksRun++;
            if (offCount == 0) {
                findings.add(new RosterValidationFinding(
                        "NO_WEEKLY_OFF",
                        "Missing Weekly OFF",
                        ValidationSeverity.ERROR,
                        emp.getEmployeeCode(), empName, null,
                        empName + " has 0 Weekly OFF days in this cycle.",
                        "Every employee must receive at least 1 Weekly OFF per 7-day cycle."
                ));
            } else if (offCount > 2) {
                findings.add(new RosterValidationFinding(
                        "EXCESS_WEEKLY_OFF",
                        "High Weekly OFF Count",
                        ValidationSeverity.WARNING,
                        emp.getEmployeeCode(), empName, null,
                        empName + " has " + offCount + " Weekly OFF days in this cycle.",
                        "Standard allotment is 1 Weekly OFF."
                ));
            }

            long nightCount = empAssignments.stream()
                    .filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT)
                    .count();
            checksRun++;
            if (nightCount > 2) {
                findings.add(new RosterValidationFinding(
                        "MAX_NIGHTS_EXCEEDED",
                        "Max Night Shifts Exceeded",
                        ValidationSeverity.ERROR,
                        emp.getEmployeeCode(), empName, null,
                        empName + " has " + nightCount + " night shifts (max allowed is 2).",
                        "Fatigue safety policy limits night shifts to at most 2 per week."
                ));
            }

            if (emp.getGender() == Gender.FEMALE) {
                checksRun++;
                for (RosterAssignment a : empAssignments) {
                    if (!a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null) {
                        ShiftType st = a.getShift().getShiftType();
                        if (st == ShiftType.NIGHT || st == ShiftType.EVENING) {
                            findings.add(new RosterValidationFinding(
                                    "FEMALE_SAFETY_VIOLATION",
                                    "Female Shift Restriction Violation",
                                    ValidationSeverity.ERROR,
                                    emp.getEmployeeCode(), empName, a.getRosterDate(),
                                    "Female employee assigned to " + st.name() + " shift on " + a.getRosterDate(),
                                    "CRIS statutory safety regulations restrict female personnel from Evening/Night shifts."
                            ));
                        }
                    }
                }
            }

            int consecutiveNights = 0;
            int consecutiveWorkDays = 0;
            for (int i = 0; i < empAssignments.size(); i++) {
                checksRun++;
                RosterAssignment curr = empAssignments.get(i);

                if (!curr.isWeeklyOff() && !curr.isOnLeave()) {
                    consecutiveWorkDays++;
                    if (curr.getShift() != null && curr.getShift().getShiftType() == ShiftType.NIGHT) {
                        consecutiveNights++;
                        if (consecutiveNights > 2) {
                            findings.add(new RosterValidationFinding(
                                    "CONSECUTIVE_NIGHTS",
                                    "Excessive Consecutive Nights",
                                    ValidationSeverity.ERROR,
                                    emp.getEmployeeCode(), empName, curr.getRosterDate(),
                                    empName + " assigned " + consecutiveNights + " consecutive night shifts ending on " + curr.getRosterDate(),
                                    "Personnel must not exceed 2 consecutive night duties."
                            ));
                        }
                    } else {
                        consecutiveNights = 0;
                    }
                } else {
                    consecutiveWorkDays = 0;
                    consecutiveNights = 0;
                }

                if (i < empAssignments.size() - 1) {
                    RosterAssignment next = empAssignments.get(i + 1);
                    if (!curr.isWeeklyOff() && !curr.isOnLeave() && curr.getShift() != null &&
                            !next.isWeeklyOff() && !next.isOnLeave() && next.getShift() != null) {

                        Shift s1 = curr.getShift();
                        Shift s2 = next.getShift();

                        LocalDateTime end1 = curr.getRosterDate().atTime(s1.getEndTime());
                        if (s1.isOvernight() || s1.getEndTime().isBefore(s1.getStartTime())) {
                            end1 = end1.plusDays(1);
                        }
                        LocalDateTime start2 = next.getRosterDate().atTime(s2.getStartTime());

                        long restMinutes = Duration.between(end1, start2).toMinutes();
                        if (restMinutes < 720) {
                            long hours = restMinutes / 60;
                            long mins = Math.abs(restMinutes % 60);
                            findings.add(new RosterValidationFinding(
                                    "REST_INTERVAL_VIOLATION",
                                    "12-Hour Rest Violation",
                                    ValidationSeverity.ERROR,
                                    emp.getEmployeeCode(), empName, next.getRosterDate(),
                                    s1.getShiftType().name() + " -> " + s2.getShiftType().name() + " (" + hours + "h " + mins + "m rest; required 12h 00m)",
                                    "Consecutive shift gap between " + curr.getRosterDate() + " and " + next.getRosterDate() + " is below minimum 12h mandatory rest."
                            ));
                        }
                    }
                }

                if (!curr.isWeeklyOff() && !curr.isOnLeave()) {
                    List<LeaveRequest> overlappingLeaves = leaveRequestRepository.findOverlappingLeaves(
                            emp.getId(), curr.getRosterDate(), curr.getRosterDate(), LeaveStatus.APPROVED);
                    if (!overlappingLeaves.isEmpty()) {
                        findings.add(new RosterValidationFinding(
                                "LEAVE_COLLISION",
                                "Shift Assigned During Approved Leave",
                                ValidationSeverity.ERROR,
                                emp.getEmployeeCode(), empName, curr.getRosterDate(),
                                empName + " is on approved leave on " + curr.getRosterDate() + " but assigned " + (curr.getShift() != null ? curr.getShift().getShiftType().name() : "Duty"),
                                "Approved leaves must be respected with ON_LEAVE status."
                        ));
                    }
                }
            }
        }

        int errorCount = (int) findings.stream().filter(f -> f.severity() == ValidationSeverity.ERROR).count();
        int warningCount = (int) findings.stream().filter(f -> f.severity() == ValidationSeverity.WARNING).count();
        int passCount = Math.max(0, checksRun - errorCount - warningCount);

        ValidationSeverity overall = ValidationSeverity.PASS;
        if (errorCount > 0) {
            overall = ValidationSeverity.ERROR;
        } else if (warningCount > 0) {
            overall = ValidationSeverity.WARNING;
        }

        return new RosterValidationResponse(
                cycle.getId(),
                cycle.getStartDate(),
                cycle.getEndDate(),
                overall,
                checksRun,
                passCount,
                warningCount,
                errorCount,
                findings,
                LocalDateTime.now()
        );
    }

    public RosterValidationResponse validateActiveRoster() {
        RosterCycle activeCycle = cycleRepository.findTopByOrderByStartDateDesc()
                .orElse(null);
        if (activeCycle != null) {
            return validateRoster(activeCycle.getId());
        }
        return new RosterValidationResponse(
                null,
                LocalDate.now(),
                LocalDate.now().plusDays(6),
                ValidationSeverity.PASS,
                0,
                0,
                0,
                0,
                Collections.emptyList(),
                LocalDateTime.now()
        );
    }
}
