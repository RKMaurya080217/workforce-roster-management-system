package com.weeklyroster.service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;

@SpringBootTest
class Batch63DiagnosticsTest {

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private EmployeePreferenceRepository preferenceRepository;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private RosterService rosterService;

    @Test
    @DisplayName("Inspect live database state and execute generation for 2026-09-28 -> 2026-10-04")
    void inspectAndRun() {
        System.out.println("========== BATCH 63 LIVE DB AUDIT ==========");

        List<Employee> allEmployees = employeeRepository.findAll();
        System.out.println("Total Employees in DB: " + allEmployees.size());
        for (Employee e : allEmployees) {
            System.out.printf("  ID: %d, Code: %s, Name: %s %s, Gender: %s, Active: %b%n",
                    e.getId(), e.getEmployeeCode(), e.getFirstName(), e.getLastName(), e.getGender(), e.isActive());
        }

        List<Shift> shifts = shiftRepository.findAll();
        System.out.println("\nShifts in DB:");
        for (Shift s : shifts) {
            System.out.printf("  Type: %s, Capacity: %d, Active: %b, Timing: %s - %s%n",
                    s.getShiftType(), s.getCapacity(), s.isActive(), s.getStartTime(), s.getEndTime());
        }

        LocalDate start = LocalDate.of(2026, 9, 28);
        LocalDate end = LocalDate.of(2026, 10, 4);

        System.out.println("\nApproved Leaves in Range [" + start + " to " + end + "]:");
        List<LeaveRequest> leaves = leaveRequestRepository.findApprovedLeavesInCycle(LeaveStatus.APPROVED, start, end);
        System.out.println("  Approved leaves count: " + leaves.size());
        for (LeaveRequest lr : leaves) {
            System.out.printf("  Employee: %s, Dates: %s to %s, Status: %s%n",
                    lr.getEmployee().getEmployeeCode(), lr.getStartDate(), lr.getEndDate(), lr.getStatus());
        }

        System.out.println("\nPreferences:");
        var prefs = preferenceRepository.findAll();
        System.out.println("  Preferences count: " + prefs.size());
        for (var p : prefs) {
            System.out.printf("  Employee ID: %s, Pref: %s%n", p.getEmployee() != null ? p.getEmployee().getId() : null, p);
        }

        System.out.println("\nRoster Cycles:");
        var cycles = cycleRepository.findAll();
        for (var c : cycles) {
            System.out.printf("  Cycle ID: %d, Range: %s to %s, Status: %s%n",
                    c.getId(), c.getStartDate(), c.getEndDate(), c.getStatus());
        }

        LocalDate prevStart = start.minusWeeks(1);
        LocalDate prevEnd = start.minusDays(1);
        System.out.println("\nPrevious Week [" + prevStart + " to " + prevEnd + "] Assignments:");
        var prevAssignments = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(prevStart, prevEnd);
        System.out.println("  Count: " + prevAssignments.size());
        for (var pa : prevAssignments) {
            System.out.printf("  Date: %s, Emp: %s, Shift: %s, Off: %b%n",
                    pa.getRosterDate(), pa.getEmployee().getEmployeeCode(),
                    pa.getShift() != null ? pa.getShift().getShiftType() : "NULL", pa.isWeeklyOff());
        }

        LocalDate prevCycleStart = start.minusWeeks(1);
        System.out.println("\nStep 1: Generating previous week: " + prevCycleStart + " to " + prevCycleStart.plusDays(6));
        try {
            var prevRes = rosterService.generateWeeklyRoster(prevCycleStart, GenerationMode.MANUAL);
            System.out.println(">>> PREVIOUS WEEK GENERATED! Cycle ID: " + prevRes.id());

            LocalDate lastSun = prevCycleStart.plusDays(6);
            System.out.println("\n--- Sunday " + lastSun + " Assignments (End of Previous Week) ---");
            assignmentRepository.findByRosterDate(lastSun).forEach(a -> {
                System.out.printf("  Emp: %s (%s, %s), Shift: %s, WeeklyOff: %b%n",
                        a.getEmployee().getEmployeeCode(), a.getEmployee().getFirstName(), a.getEmployee().getGender(),
                        a.getShift() != null ? a.getShift().getShiftType() : "OFF", a.isWeeklyOff());
            });
        } catch (Exception ex) {
            System.out.println(">>> PREVIOUS WEEK FAILED: " + ex.getMessage());
        }

        System.out.println("\nStep 2: Attempting Target Roster Generation for " + start + " to " + end + "...");
        try {
            var res = rosterService.generateWeeklyRoster(start, GenerationMode.MANUAL);
            System.out.println(">>> TARGET GENERATION SUCCEEDED! Cycle ID: " + res.id());

            var assignedList = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(start, end);
            System.out.println("Generated assignments count: " + assignedList.size());
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                final LocalDate curD = d;
                System.out.println("--- Date: " + curD + " (" + curD.getDayOfWeek() + ") ---");
                long mCount = assignedList.stream().filter(a -> a.getRosterDate().equals(curD) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.MORNING).count();
                long gCount = assignedList.stream().filter(a -> a.getRosterDate().equals(curD) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.GENERAL).count();
                long eCount = assignedList.stream().filter(a -> a.getRosterDate().equals(curD) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.EVENING).count();
                long nCount = assignedList.stream().filter(a -> a.getRosterDate().equals(curD) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT).count();
                long offCount = assignedList.stream().filter(a -> a.getRosterDate().equals(curD) && (a.isWeeklyOff() || a.isOnLeave())).count();

                System.out.printf("  Summary: M=%d, G=%d, E=%d, N=%d, OFF=%d (Total working=%d)%n",
                        mCount, gCount, eCount, nCount, offCount, (mCount + gCount + eCount + nCount));

                assignedList.stream()
                        .filter(a -> a.getRosterDate().equals(curD))
                        .forEach(a -> System.out.printf("    Emp: %s (%s, %s), Shift: %s, WeeklyOff: %b%n",
                                a.getEmployee().getEmployeeCode(), a.getEmployee().getFirstName(), a.getEmployee().getGender(),
                                a.getShift() != null ? a.getShift().getShiftType() : "NULL",
                                a.isWeeklyOff()));
            }

            // Invariant assertions
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                final LocalDate curD = d;
                long gCount = assignedList.stream().filter(a -> a.getRosterDate().equals(curD) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.GENERAL).count();
                org.junit.jupiter.api.Assertions.assertTrue(gCount >= 1, "General shift must have at least 1 staff on " + curD);
            }

            // Check exactly 1 weekly off per employee
            for (Employee e : allEmployees) {
                long offCount = assignedList.stream().filter(a -> a.getEmployee().getId().equals(e.getId()) && a.isWeeklyOff()).count();
                System.out.printf("Employee %s (%s) Weekly Off Count: %d%n", e.getEmployeeCode(), e.getFirstName(), offCount);
                org.junit.jupiter.api.Assertions.assertEquals(1, offCount, "Employee " + e.getEmployeeCode() + " must have exactly 1 weekly off");
            }

            // Check females never in Evening or Night
            for (var a : assignedList) {
                if (a.getEmployee().getGender() == Gender.FEMALE && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null) {
                    var st = a.getShift().getShiftType();
                    org.junit.jupiter.api.Assertions.assertTrue(st == ShiftType.MORNING || st == ShiftType.GENERAL,
                            "Female " + a.getEmployee().getEmployeeCode() + " cannot be in " + st);
                }
            }

            System.out.println(">>> ALL INVARIANTS VERIFIED SUCCESSFULLY!");
        } catch (Exception ex) {
            System.out.println(">>> TARGET GENERATION FAILED: " + ex.getMessage());
            ex.printStackTrace();
            org.junit.jupiter.api.Assertions.fail("Generation failed: " + ex.getMessage());
        }

        System.out.println("============================================");
    }

    @Test
    @DisplayName("Diagnose candidate selection and weekly offs for Day 7")
    void diagnoseAlgorithmDetails() throws Exception {
        System.out.println("\n========== DIAGNOSE ALGORITHM DETAILS ==========");
        List<Employee> employees = employeeRepository.findAll().stream().filter(Employee::isActive).toList();
        LocalDate start = LocalDate.of(2026, 9, 28);
        LocalDate end = LocalDate.of(2026, 10, 4);

        var targetService = org.springframework.test.util.AopTestUtils.getTargetObject(rosterService);
        var planWeeklyOffsMethod = RosterService.class.getDeclaredMethod("planWeeklyOffs", List.class, LocalDate.class, LocalDate.class, Map.class, int.class);
        planWeeklyOffsMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<Long, LocalDate> weeklyOffs = (Map<Long, LocalDate>) planWeeklyOffsMethod.invoke(targetService, employees, start, end, Collections.emptyMap(), 0);

        System.out.println("Weekly Off Plan (Attempt 0):");
        for (Employee e : employees) {
            LocalDate off = weeklyOffs.get(e.getId());
            System.out.printf("  Emp: %s (%s, %s) -> Off: %s (%s)%n",
                    e.getEmployeeCode(), e.getFirstName(), e.getGender(), off, off != null ? off.getDayOfWeek() : "NONE");
        }

        LocalDate day7 = LocalDate.of(2026, 10, 4);
        List<Employee> day7Available = employees.stream()
                .filter(e -> !day7.equals(weeklyOffs.get(e.getId())))
                .toList();

        System.out.println("\nDay 7 (" + day7 + ") Available Staff Count: " + day7Available.size());
        for (Employee e : day7Available) {
            System.out.printf("  Available Emp: %s (%s, %s)%n", e.getEmployeeCode(), e.getFirstName(), e.getGender());
        }

        var calcFeasibleMethod = RosterService.class.getDeclaredMethod("calculateDailyFeasibleDemands", List.class, Map.class);
        calcFeasibleMethod.setAccessible(true);

        Map<ShiftType, Integer> configuredDemands = Map.of(
                ShiftType.NIGHT, 1,
                ShiftType.EVENING, 2,
                ShiftType.MORNING, 2,
                ShiftType.GENERAL, 2
        );

        @SuppressWarnings("unchecked")
        Map<ShiftType, Integer> feasibleDemands = (Map<ShiftType, Integer>) calcFeasibleMethod.invoke(targetService, day7Available, configuredDemands);
        System.out.println("\nDay 7 Feasible Demands:");
        feasibleDemands.forEach((st, cnt) -> System.out.println("  " + st + ": " + cnt));

        // Now let's trace generateDay for each day 0..6
        var generateDayMethod = RosterService.class.getDeclaredMethod("generateDay",
                RosterCycle.class, List.class, Map.class, Set.class, Map.class, LocalDate.class,
                Map.class, Map.class, Map.class, Map.class, int.class, Map.class, int.class);
        generateDayMethod.setAccessible(true);

        RosterCycle testCycle = new RosterCycle();
        testCycle.setStartDate(start);
        testCycle.setEndDate(end);

        Map<ShiftType, Shift> shifts = shiftRepository.findAll().stream().collect(Collectors.toMap(Shift::getShiftType, s -> s));
        Set<Long> offTaken = new HashSet<>();
        Map<Long, Shift> lastShiftMap = new HashMap<>();
        Map<Long, LocalDate> lastShiftDateMap = new HashMap<>();
        Map<Long, Integer> cycleNightCounts = new HashMap<>();
        Map<Long, Map<ShiftType, Integer>> shiftCountsMap = new HashMap<>();

        for (Employee emp : employees) {
            cycleNightCounts.put(emp.getId(), 0);
            List<RosterAssignment> prevWorked = assignmentRepository.findWorkedAssignmentsBefore(emp.getId(), start);
            if (!prevWorked.isEmpty()) {
                RosterAssignment lastA = prevWorked.get(0);
                lastShiftMap.put(emp.getId(), lastA.getShift());
                lastShiftDateMap.put(emp.getId(), lastA.getRosterDate());
            }
            Map<ShiftType, Integer> counts = new EnumMap<>(ShiftType.class);
            for (ShiftType type : ShiftType.values()) counts.put(type, 0);
            shiftCountsMap.put(emp.getId(), counts);
        }

        List<RosterAssignment> candidateAssignments = new ArrayList<>();
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = start.plusDays(offset);
            Object dayResult = generateDayMethod.invoke(targetService, testCycle, employees, weeklyOffs, offTaken, shifts, date,
                    lastShiftMap, lastShiftDateMap, cycleNightCounts, shiftCountsMap, 2, Collections.emptyMap(), 0);

            var assignmentsField = dayResult.getClass().getDeclaredMethod("assignments");
            assignmentsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<RosterAssignment> dAssigns = (List<RosterAssignment>) assignmentsField.invoke(dayResult);
            candidateAssignments.addAll(dAssigns);

            System.out.println("\n--- Date: " + date + " (" + date.getDayOfWeek() + ") ---");
            for (RosterAssignment a : dAssigns) {
                System.out.printf("  Emp: %s (%s, %s), Shift: %s, Off: %b%n",
                        a.getEmployee().getEmployeeCode(), a.getEmployee().getFirstName(), a.getEmployee().getGender(),
                        a.getShift() != null ? a.getShift().getShiftType() : "NULL", a.isWeeklyOff());
            }
        }

        System.out.println("\n--- Post-Day Generation: Running enforceAndRepairExactWeeklyOff ---");
        var repairMethod = RosterService.class.getDeclaredMethod("enforceAndRepairExactWeeklyOff",
                RosterCycle.class, List.class, List.class, Map.class, int.class, Map.class, Map.class);
        repairMethod.setAccessible(true);
        repairMethod.invoke(targetService, testCycle, candidateAssignments, employees, shifts, 2, weeklyOffs, Collections.emptyMap());

        System.out.println("--- After enforceAndRepairExactWeeklyOff for Day 7 (" + day7 + ") ---");
        candidateAssignments.stream()
                .filter(a -> a.getRosterDate().equals(day7))
                .forEach(a -> System.out.printf("  Emp: %s (%s, %s), Shift: %s, Off: %b%n",
                        a.getEmployee().getEmployeeCode(), a.getEmployee().getFirstName(), a.getEmployee().getGender(),
                        a.getShift() != null ? a.getShift().getShiftType() : "NULL", a.isWeeklyOff()));

        var validateMethod = RosterService.class.getDeclaredMethod("evaluateFinalValidation",
                RosterCycle.class, List.class, Map.class, int.class, Map.class, Map.class);
        validateMethod.setAccessible(true);
        Object valResult = validateMethod.invoke(targetService, testCycle, candidateAssignments, shifts, 2, Collections.emptyMap(), Collections.emptyMap());
        System.out.println("\nFinal Validation: " + valResult);

        System.out.println("================================================");
    }
}
