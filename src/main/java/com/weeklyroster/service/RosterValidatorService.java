package com.weeklyroster.service;

import com.weeklyroster.dto.response.RosterValidationFinding;
import com.weeklyroster.dto.response.RosterValidationResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.HolidayRepository;
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private HolidayRepository holidayRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.weeklyroster.repository.DateShiftPreferenceRepository dateShiftPreferenceRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.weeklyroster.repository.RecurringShiftPreferenceRepository recurringShiftPreferenceRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.weeklyroster.repository.EmployeePreferenceRepository preferenceRepository;

    public void setHolidayRepository(HolidayRepository holidayRepository) {
        this.holidayRepository = holidayRepository;
    }

    public void setDateShiftPreferenceRepository(com.weeklyroster.repository.DateShiftPreferenceRepository dateShiftPreferenceRepository) {
        this.dateShiftPreferenceRepository = dateShiftPreferenceRepository;
    }

    public void setRecurringShiftPreferenceRepository(com.weeklyroster.repository.RecurringShiftPreferenceRepository recurringShiftPreferenceRepository) {
        this.recurringShiftPreferenceRepository = recurringShiftPreferenceRepository;
    }

    public void setPreferenceRepository(com.weeklyroster.repository.EmployeePreferenceRepository preferenceRepository) {
        this.preferenceRepository = preferenceRepository;
    }

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

        List<Holiday> activeHolidays = (holidayRepository != null && start != null && end != null)
                ? holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(start, end)
                : Collections.emptyList();
        Set<LocalDate> holidayDates = activeHolidays.stream()
                .filter(Holiday::isActive)
                .map(Holiday::getHolidayDate)
                .collect(Collectors.toSet());

        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            checksRun++;
            List<RosterAssignment> dayAssignments = byDate.getOrDefault(d, Collections.emptyList());
            Map<ShiftType, Long> counts = dayAssignments.stream()
                    .filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null)
                    .collect(Collectors.groupingBy(a -> a.getShift().getShiftType(), Collectors.counting()));

            long workingCountOnDate = counts.values().stream().mapToLong(Long::longValue).sum();
            boolean isWeekend = (d.getDayOfWeek() == java.time.DayOfWeek.SATURDAY || d.getDayOfWeek() == java.time.DayOfWeek.SUNDAY);
            int minMorning = (isWeekend || workingCountOnDate <= 5) ? 1 : 2;
            long morningCount = counts.getOrDefault(ShiftType.MORNING, 0L);
            if (morningCount < minMorning) {
                findings.add(new RosterValidationFinding(
                        morningCount == 0 ? "ZERO_SHIFT_COVERAGE" : "INSUFFICIENT_MORNING_COVERAGE",
                        "Insufficient Morning Coverage",
                        ValidationSeverity.ERROR,
                        null, null, d,
                        "Morning shift has " + morningCount + " employees assigned on " + d + " (minimum required is " + minMorning + ").",
                        "Monday–Friday requires at least 2 employees on Morning shift; weekends or reduced workforce require at least 1."
                ));
            }

            for (ShiftType st : List.of(ShiftType.GENERAL, ShiftType.EVENING, ShiftType.NIGHT)) {
                long c = counts.getOrDefault(st, 0L);
                if (c == 0) {
                    if (st == ShiftType.GENERAL && (isWeekend || workingCountOnDate <= 3)) {
                        continue;
                    }
                    if (st == ShiftType.NIGHT) {
                        long activeMales = byEmployee.values().stream()
                                .filter(l -> !l.isEmpty() && l.get(0).getEmployee().getGender() == Gender.MALE && l.get(0).getEmployee().isActive())
                                .count();
                        int wDays = (cycle != null && cycle.getWorkingDays() != null) ? cycle.getWorkingDays() : 6;
                        boolean nightCapacityShortage = (wDays == 6) ? (activeMales < 7) : (activeMales * 2 < 7);
                        if (nightCapacityShortage) {
                            findings.add(new RosterValidationFinding(
                                    "WORKFORCE_CAPACITY_SHORTAGE",
                                    "Workforce Capacity Shortage",
                                    ValidationSeverity.WARNING,
                                    null, null, d,
                                    "Zero employees assigned to NIGHT shift on " + d + " due to male workforce capacity constraint in " + wDays + "-day cycle.",
                                    "Workforce capacity shortage: Assigning night would violate strict single weekly off rule."
                            ));
                            continue;
                        }
                    }
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
            int expectedOffs = (cycle != null) ? cycle.getExpectedWeeklyOffs() : 1;
            if (offCount == 0) {
                findings.add(new RosterValidationFinding(
                        "NO_WEEKLY_OFF",
                        "Missing Weekly OFF",
                        ValidationSeverity.ERROR,
                        emp.getEmployeeCode(), empName, null,
                        empName + " has 0 Weekly OFF days in this cycle.",
                        "Every employee must receive at least 1 Weekly OFF per 7-day cycle."
                ));
            } else if (offCount > expectedOffs) {
                findings.add(new RosterValidationFinding(
                        "EXCESS_WEEKLY_OFF",
                        "High Weekly OFF Count",
                        (expectedOffs == 1) ? ValidationSeverity.ERROR : ValidationSeverity.WARNING,
                        emp.getEmployeeCode(), empName, null,
                        empName + " has " + offCount + " Weekly OFF days in this cycle.",
                        "Configured allotment is " + expectedOffs + " Weekly OFF" + (expectedOffs > 1 ? "s" : "") + "."
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

            for (RosterAssignment curr : empAssignments) {
                if (!emp.isEligibleForDate(curr.getRosterDate())) {
                    String reason = (emp.getJoiningDate() != null && curr.getRosterDate().isBefore(emp.getJoiningDate()))
                            ? "prior to joining date (" + emp.getJoiningDate() + ")"
                            : "after resignation date (" + emp.getResignationDate() + ")";
                    findings.add(new RosterValidationFinding(
                            "INELIGIBLE_DATE_ASSIGNMENT",
                            "Ineligible Date Assignment",
                            ValidationSeverity.ERROR,
                            emp.getEmployeeCode(), empName, curr.getRosterDate(),
                            "Employee " + empName + " assigned on " + curr.getRosterDate() + " when ineligible (" + reason + ")",
                            "Employee must be eligible for the date based on joining and resignation dates."
                    ));
                }
                if (holidayDates.contains(curr.getRosterDate()) && curr.isWeeklyOff()) {
                    findings.add(new RosterValidationFinding(
                            "HOLIDAY_NOT_WEEKLY_OFF",
                            "Holiday Converted to Weekly OFF",
                            ValidationSeverity.ERROR,
                            emp.getEmployeeCode(), empName, curr.getRosterDate(),
                            "Date " + curr.getRosterDate() + " is an official Holiday but marked as Weekly OFF for " + empName + ".",
                            "Holiday must remain a distinct concept and must never be converted to Weekly OFF."
                    ));
                }
            }

            int cycleWorkingDays = (cycle.getWorkingDays() != null) ? cycle.getWorkingDays() : 6;
            long leaveCount = empAssignments.stream().filter(RosterAssignment::isOnLeave).count();
            long workCount = empAssignments.stream().filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() != ShiftType.OFF).count();
            long nonWorkedHolidays = empAssignments.stream()
                    .filter(a -> holidayDates.contains(a.getRosterDate()))
                    .filter(a -> a.isWeeklyOff() || a.isOnLeave() || a.getShift() == null || a.getShift().getShiftType() == ShiftType.OFF)
                    .count();
            int targetExpectedOffs = Math.min(expectedOffs, Math.max(0, 7 - (int) leaveCount));
            int expectedWorkDays = Math.max(0, cycleWorkingDays - (int) leaveCount - (int) nonWorkedHolidays);

            checksRun++;
            if (cycleWorkingDays == 6 && emp.isActive()) {
                if (offCount != targetExpectedOffs && leaveCount < 7) {
                    findings.add(new RosterValidationFinding(
                            "INVALID_WEEKLY_OFF_COUNT",
                            "Strict Weekly OFF Count Violation",
                            ValidationSeverity.ERROR,
                            emp.getEmployeeCode(), empName, null,
                            empName + " has " + offCount + " Weekly OFF assignments in cycle (strictly " + targetExpectedOffs + " required for 6-day cycle).",
                            "Hard invariant: Every active employee in a standard 6-day cycle must have exactly 1 Weekly OFF."
                    ));
                }
                if (leaveCount == 0 && workCount != expectedWorkDays) {
                    findings.add(new RosterValidationFinding(
                            "INVALID_WORKING_DAYS_COUNT",
                            "Working Days Count Violation",
                            ValidationSeverity.ERROR,
                            emp.getEmployeeCode(), empName, null,
                            empName + " has " + workCount + " working days in cycle (strictly " + expectedWorkDays + " required when " + nonWorkedHolidays + " non-worked holidays).",
                            "Hard invariant: Normal employee has 6 working days + 1 Weekly OFF when no leave, adjusted for non-worked official holidays."
                    ));
                }
            }

            if (!empAssignments.isEmpty()) {
                RosterAssignment first = empAssignments.get(0);
                List<RosterAssignment> prevWorked = assignmentRepository.findWorkedAssignmentsBefore(emp.getId(), first.getRosterDate());
                if (!prevWorked.isEmpty()) {
                    RosterAssignment prevA = prevWorked.get(0);
                    if (prevA.getRosterDate() != null && prevA.getRosterDate().equals(first.getRosterDate().minusDays(1))) {
                        if (prevA.getShift() != null && prevA.getShift().getShiftType() == ShiftType.NIGHT) {
                            checksRun++;
                            if (!first.isWeeklyOff() && !first.isOnLeave() && first.getShift() != null && first.getShift().getShiftType() != ShiftType.OFF) {
                                findings.add(new RosterValidationFinding(
                                        "NIGHT_REQUIRES_NEXT_DAY_OFF",
                                        "Night Shift Must Be Followed by Weekly OFF",
                                        ValidationSeverity.ERROR,
                                        emp.getEmployeeCode(), empName, first.getRosterDate(),
                                        empName + " worked NIGHT on preceding Sunday (" + prevA.getRosterDate() + ") but is assigned " + first.getShift().getShiftType().name() + " on " + first.getRosterDate() + " (must be Weekly OFF).",
                                        "CRIS mandatory fatigue safety rule: Whenever an employee is assigned NIGHT (22:00 - 07:00), they MUST receive WEEKLY OFF on the following calendar day."
                                ));
                            }
                        } else if (prevA.getShift() != null && !first.isWeeklyOff() && !first.isOnLeave() && first.getShift() != null) {
                            checksRun++;
                            Shift sPrev = prevA.getShift();
                            Shift sCur = first.getShift();
                            LocalDateTime endPrev = prevA.getRosterDate().atTime(sPrev.getEndTime());
                            if (sPrev.isOvernight() || sPrev.getEndTime().isBefore(sPrev.getStartTime())) {
                                endPrev = endPrev.plusDays(1);
                            }
                            LocalDateTime startCur = first.getRosterDate().atTime(sCur.getStartTime());
                            long restMins = Duration.between(endPrev, startCur).toMinutes();
                            if (restMins < 720) {
                                long hours = restMins / 60;
                                long mins = Math.abs(restMins % 60);
                                findings.add(new RosterValidationFinding(
                                        "REST_INTERVAL_VIOLATION",
                                        "12-Hour Rest Violation",
                                        ValidationSeverity.ERROR,
                                        emp.getEmployeeCode(), empName, first.getRosterDate(),
                                        sPrev.getShiftType().name() + " -> " + sCur.getShiftType().name() + " (" + hours + "h " + mins + "m rest; required 12h 00m)",
                                        "Consecutive shift gap across week boundary is below minimum 12h mandatory rest."
                                ));
                            }
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
                        if (consecutiveNights > 1) {
                            findings.add(new RosterValidationFinding(
                                    "CONSECUTIVE_NIGHTS",
                                    "Consecutive Night Shifts Forbidden",
                                    ValidationSeverity.ERROR,
                                    emp.getEmployeeCode(), empName, curr.getRosterDate(),
                                    empName + " assigned " + consecutiveNights + " consecutive night shifts ending on " + curr.getRosterDate(),
                                    "CRIS mandatory fatigue safety rule: Every Night shift must be followed by Weekly OFF. Consecutive night shifts are forbidden."
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

                        // BATCH 109: Night shift must be followed by Weekly OFF
                        if (s1.getShiftType() == ShiftType.NIGHT && s2.getShiftType() != ShiftType.OFF) {
                            findings.add(new RosterValidationFinding(
                                    "NIGHT_REQUIRES_NEXT_DAY_OFF",
                                    "Night Shift Must Be Followed by Weekly OFF",
                                    ValidationSeverity.ERROR,
                                    emp.getEmployeeCode(), empName, next.getRosterDate(),
                                    empName + " worked NIGHT on " + curr.getRosterDate() + " but is assigned " + s2.getShiftType().name() + " on " + next.getRosterDate() + " (must be Weekly OFF).",
                                    "CRIS mandatory fatigue safety rule: Whenever an employee is assigned NIGHT (22:00 - 07:00), they MUST receive WEEKLY OFF on the following calendar day."
                            ));
                        }

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

                // BATCH 113 & BATCH 115 & BATCH 124 & BATCH 125: Precedence Hierarchy for Preference Checking
                boolean prevDayWasNight = false;
                if (i > 0) {
                    RosterAssignment prevA = empAssignments.get(i - 1);
                    if (!prevA.isWeeklyOff() && !prevA.isOnLeave() && prevA.getShift() != null && prevA.getShift().getShiftType() == ShiftType.NIGHT) {
                        prevDayWasNight = true;
                    }
                } else {
                    List<RosterAssignment> prevWorked = assignmentRepository.findWorkedAssignmentsBefore(emp.getId(), curr.getRosterDate());
                    if (!prevWorked.isEmpty() && prevWorked.get(0).getRosterDate() != null
                            && prevWorked.get(0).getRosterDate().equals(curr.getRosterDate().minusDays(1))
                            && prevWorked.get(0).getShift() != null && prevWorked.get(0).getShift().getShiftType() == ShiftType.NIGHT) {
                        prevDayWasNight = true;
                    }
                }

                // BATCH 113 & BATCH 115 & BATCH 124 & BATCH 125: Date-specific shift preference validation (HARD CONSTRAINT)
                boolean hasDateSpecificPref = false;
                if (dateShiftPreferenceRepository != null) {
                    var dspOpt = dateShiftPreferenceRepository.findByEmployeeIdAndPreferenceDate(emp.getId(), curr.getRosterDate());
                    if (dspOpt.isPresent()) {
                        hasDateSpecificPref = true;
                        checksRun++;
                        ShiftType expected = dspOpt.get().getShiftType();
                        boolean femaleSafetyConflict = emp.getGender() == Gender.FEMALE && (expected == ShiftType.NIGHT || expected == ShiftType.EVENING);
                        boolean higherPrecedenceApplies = curr.isOnLeave() || curr.isManualAssignment() || curr.isOverridden() || femaleSafetyConflict || (prevDayWasNight && expected != ShiftType.OFF);

                        if (!higherPrecedenceApplies) {
                            if (expected == ShiftType.OFF) {
                                if (!curr.isWeeklyOff()) {
                                    findings.add(new RosterValidationFinding(
                                            "DATE_PREFERENCE_VIOLATION",
                                            "Date Shift Preference Mismatch",
                                            ValidationSeverity.ERROR,
                                            emp.getEmployeeCode(), empName, curr.getRosterDate(),
                                            empName + " has Date Preference for OFF on " + curr.getRosterDate() + " but was assigned " + (curr.getShift() != null ? curr.getShift().getShiftType().name() : "Duty"),
                                            "Admin configured date-specific preference is a mandatory hard constraint."
                                    ));
                                }
                            } else {
                                if (curr.isWeeklyOff() || curr.getShift() == null || curr.getShift().getShiftType() != expected) {
                                    findings.add(new RosterValidationFinding(
                                            "DATE_PREFERENCE_VIOLATION",
                                            "Date Shift Preference Mismatch",
                                            ValidationSeverity.ERROR,
                                            emp.getEmployeeCode(), empName, curr.getRosterDate(),
                                            empName + " has Date Preference for " + expected.name() + " on " + curr.getRosterDate() + " but was assigned " + (curr.isWeeklyOff() ? "OFF" : (curr.getShift() != null ? curr.getShift().getShiftType().name() : "Unknown")),
                                            "Admin configured date-specific preference is a mandatory hard constraint."
                                    ));
                                }
                            }
                        }
                    }
                }

                // BATCH 113 & BATCH 115 & BATCH 124 & BATCH 125: Recurring weekly shift preference validation (HARD CONSTRAINT)
                // Date-specific preference takes precedence over recurring preference
                if (!hasDateSpecificPref && recurringShiftPreferenceRepository != null) {
                    var recList = recurringShiftPreferenceRepository.findByEmployeeIdAndActiveTrue(emp.getId());
                    if (recList != null) {
                        for (var rp : recList) {
                            if (rp.getDayOfWeek() == curr.getRosterDate().getDayOfWeek()) {
                                boolean startOk = (rp.getStartDate() == null || !rp.getStartDate().isAfter(curr.getRosterDate()));
                                boolean endOk = (rp.getEndDate() == null || !rp.getEndDate().isBefore(curr.getRosterDate()));
                                if (startOk && endOk) {
                                    checksRun++;
                                    if (rp.getPreferenceType() == RecurringPreferenceType.DO_NOT_ASSIGN) {
                                        // Recurring Negative Restriction: Shift must NOT be assigned
                                        ShiftType restricted = rp.getShiftType();
                                        if (!curr.isWeeklyOff() && !curr.isOnLeave() && curr.getShift() != null && curr.getShift().getShiftType() == restricted) {
                                            findings.add(new RosterValidationFinding(
                                                    "RECURRING_RESTRICTION_VIOLATION",
                                                    "Recurring Shift Restriction Violated",
                                                    ValidationSeverity.ERROR,
                                                    emp.getEmployeeCode(), empName, curr.getRosterDate(),
                                                    empName + " has Recurring Restriction 'Do Not Assign " + restricted.name() + "' on " + curr.getRosterDate().getDayOfWeek() + " but was assigned " + restricted.name(),
                                                    "Admin configured shift restriction must be respected."
                                            ));
                                        }
                                    } else {
                                        // Recurring Positive Preference
                                        ShiftType expected = rp.getShiftType();
                                        boolean femaleSafetyConflict = emp.getGender() == Gender.FEMALE && (expected == ShiftType.NIGHT || expected == ShiftType.EVENING);
                                        boolean higherPrecedenceApplies = curr.isOnLeave() || curr.isManualAssignment() || curr.isOverridden() || femaleSafetyConflict || (prevDayWasNight && expected != ShiftType.OFF);

                                        if (!higherPrecedenceApplies) {
                                            if (expected == ShiftType.OFF) {
                                                if (!curr.isWeeklyOff()) {
                                                    findings.add(new RosterValidationFinding(
                                                            "RECURRING_PREFERENCE_VIOLATION",
                                                            "Recurring Shift Preference Mismatch",
                                                            ValidationSeverity.ERROR,
                                                            emp.getEmployeeCode(), empName, curr.getRosterDate(),
                                                            empName + " has Recurring Preference for OFF on " + curr.getRosterDate().getDayOfWeek() + " but was assigned " + (curr.getShift() != null ? curr.getShift().getShiftType().name() : "Duty"),
                                                            "Admin configured recurring weekly preference is a mandatory hard constraint."
                                                    ));
                                                }
                                            } else {
                                                if (curr.isWeeklyOff() || curr.getShift() == null || curr.getShift().getShiftType() != expected) {
                                                    findings.add(new RosterValidationFinding(
                                                            "RECURRING_PREFERENCE_VIOLATION",
                                                            "Recurring Shift Preference Mismatch",
                                                            ValidationSeverity.ERROR,
                                                            emp.getEmployeeCode(), empName, curr.getRosterDate(),
                                                            empName + " has Recurring Preference for " + expected.name() + " on " + curr.getRosterDate().getDayOfWeek() + " but was assigned " + (curr.isWeeklyOff() ? "OFF" : (curr.getShift() != null ? curr.getShift().getShiftType().name() : "Unknown")),
                                                            "Admin configured recurring weekly preference is a mandatory hard constraint."
                                                    ));
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // BATCH 113: Avoided shift types validation
                if (preferenceRepository != null && !curr.isWeeklyOff() && !curr.isOnLeave() && curr.getShift() != null) {
                    var prefs = preferenceRepository.findByEmployeeIdOrderByCreatedAtDesc(emp.getId());
                    if (prefs != null && !prefs.isEmpty()) {
                        var activePref = prefs.stream().filter(p -> p.getStatus() == PreferenceStatus.APPROVED).findFirst().orElse(null);
                        if (activePref != null && activePref.getAvoidShiftTypes() != null && !activePref.getAvoidShiftTypes().isBlank()) {
                            checksRun++;
                            Set<String> avoid = Arrays.stream(activePref.getAvoidShiftTypes().split(","))
                                    .map(String::trim).map(String::toUpperCase).collect(Collectors.toSet());
                            if (avoid.contains(curr.getShift().getShiftType().name())) {
                                findings.add(new RosterValidationFinding(
                                        "AVOID_SHIFT_VIOLATION",
                                        "Avoided Shift Assigned",
                                        ValidationSeverity.WARNING,
                                        emp.getEmployeeCode(), empName, curr.getRosterDate(),
                                        empName + " prefers to avoid " + curr.getShift().getShiftType().name() + " shift on " + curr.getRosterDate(),
                                        "Avoided shift types should be respected unless hard coverage constraints strictly require assignment."
                                ));
                            }
                        }
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
