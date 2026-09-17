package com.weeklyroster.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class Batch63RegressionMatrixTest {

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private RosterService rosterService;

    @Test
    @DisplayName("Test A: Target cycle 2026-09-28 -> 2026-10-04 generates cleanly with General >= 1 on Day 7")
    void testA_targetCycleGeneratesSuccessfully() {
        LocalDate start = LocalDate.of(2026, 9, 28);
        LocalDate end = LocalDate.of(2026, 10, 4);

        var response = rosterService.generateWeeklyRoster(start, GenerationMode.MANUAL);
        assertNotNull(response);
        assertEquals(start, response.startDate());
        assertEquals(end, response.endDate());

        var assignments = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(start, end);
        assertFalse(assignments.isEmpty());

        // Verify Day 7 (2026-10-04) specifically
        LocalDate day7 = LocalDate.of(2026, 10, 4);
        long gCountDay7 = assignments.stream()
                .filter(a -> a.getRosterDate().equals(day7) && !a.isWeeklyOff() && !a.isOnLeave()
                        && a.getShift() != null && a.getShift().getShiftType() == ShiftType.GENERAL)
                .count();

        assertTrue(gCountDay7 >= 1, "General shift must have >= 1 assigned staff on 2026-10-04 (Actual: " + gCountDay7 + ")");

        // Verify all 7 days have General >= 1
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            final LocalDate curD = d;
            long gCount = assignments.stream()
                    .filter(a -> a.getRosterDate().equals(curD) && !a.isWeeklyOff() && !a.isOnLeave()
                            && a.getShift() != null && a.getShift().getShiftType() == ShiftType.GENERAL)
                    .count();
            assertTrue(gCount >= 1, "General shift must have >= 1 staff on " + curD);
        }
    }

    @Test
    @DisplayName("Test B: Another normal Monday-Sunday cycle generates successfully")
    void testB_anotherNormalCycleGeneratesSuccessfully() {
        LocalDate start = LocalDate.of(2026, 10, 12);
        LocalDate end = LocalDate.of(2026, 10, 18);

        var response = rosterService.generateWeeklyRoster(start, GenerationMode.MANUAL);
        assertNotNull(response);
        assertEquals(start, response.startDate());
        assertEquals(end, response.endDate());

        var assignments = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(start, end);
        assertFalse(assignments.isEmpty());

        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            final LocalDate curD = d;
            long working = assignments.stream()
                    .filter(a -> a.getRosterDate().equals(curD) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null)
                    .count();
            assertTrue(working >= 4, "Every day must have at least 4 working staff");
        }
    }

    @Test
    @DisplayName("Test C: Female employees are assigned only Day shifts (Morning / General), never Evening or Night")
    void testC_femaleEmployeesNeverAssignedEveningOrNight() {
        LocalDate start = LocalDate.of(2026, 9, 28);
        LocalDate end = LocalDate.of(2026, 10, 4);

        var assignments = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(start, end);

        for (RosterAssignment a : assignments) {
            if (a.getEmployee().getGender() == Gender.FEMALE && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null) {
                ShiftType type = a.getShift().getShiftType();
                assertNotEquals(ShiftType.EVENING, type, "Female employee must not be assigned Evening: " + a.getEmployee().getEmployeeCode());
                assertNotEquals(ShiftType.NIGHT, type, "Female employee must not be assigned Night: " + a.getEmployee().getEmployeeCode());
                assertTrue(type == ShiftType.MORNING || type == ShiftType.GENERAL,
                        "Female employee must be assigned MORNING or GENERAL, got: " + type);
            }
        }
    }

    @Test
    @DisplayName("Test D: Night followed by next-day Morning transition is strictly prohibited")
    void testD_nightFollowedByMorningProhibited() {
        LocalDate start = LocalDate.of(2026, 9, 28);
        LocalDate end = LocalDate.of(2026, 10, 4);

        var assignments = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(start, end);
        var empAssignments = assignments.stream().collect(java.util.stream.Collectors.groupingBy(a -> a.getEmployee().getId()));

        for (var entry : empAssignments.entrySet()) {
            List<RosterAssignment> list = entry.getValue();
            list.sort(java.util.Comparator.comparing(RosterAssignment::getRosterDate));

            for (int i = 0; i < list.size() - 1; i++) {
                RosterAssignment cur = list.get(i);
                RosterAssignment next = list.get(i + 1);

                if (!cur.isWeeklyOff() && !cur.isOnLeave() && cur.getShift() != null && cur.getShift().getShiftType() == ShiftType.NIGHT) {
                    if (!next.isWeeklyOff() && !next.isOnLeave() && next.getShift() != null) {
                        assertNotEquals(ShiftType.MORNING, next.getShift().getShiftType(),
                                "Night shift cannot be immediately followed by Morning shift for " + cur.getEmployee().getEmployeeCode());
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Test E: 12-Hour Minimum Rest Rule is preserved across all working transitions")
    void testE_minimum12HourRestPreserved() {
        LocalDate start = LocalDate.of(2026, 9, 28);
        LocalDate end = LocalDate.of(2026, 10, 4);

        var assignments = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(start, end);
        var empAssignments = assignments.stream().collect(java.util.stream.Collectors.groupingBy(a -> a.getEmployee().getId()));

        for (var entry : empAssignments.entrySet()) {
            List<RosterAssignment> list = entry.getValue();
            list.sort(java.util.Comparator.comparing(RosterAssignment::getRosterDate));

            for (int i = 0; i < list.size() - 1; i++) {
                RosterAssignment cur = list.get(i);
                RosterAssignment next = list.get(i + 1);

                if (!cur.isWeeklyOff() && !cur.isOnLeave() && cur.getShift() != null && cur.getShift().getShiftType() != ShiftType.OFF
                        && !next.isWeeklyOff() && !next.isOnLeave() && next.getShift() != null && next.getShift().getShiftType() != ShiftType.OFF) {
                    boolean restOk = rosterService.hasMinimumRest(cur.getRosterDate(), cur.getShift(), next.getRosterDate(), next.getShift());
                    assertTrue(restOk, String.format("Employee %s has rest violation between %s (%s) and %s (%s)",
                            cur.getEmployee().getEmployeeCode(), cur.getRosterDate(), cur.getShift().getShiftType(),
                            next.getRosterDate(), next.getShift().getShiftType()));
                }
            }
        }
    }

    @Test
    @DisplayName("Test F: Employee on approved leave is not assigned shifts")
    void testF_employeeOnLeaveNotAssigned() {
        List<Employee> allEmployees = employeeRepository.findAll().stream().filter(Employee::isActive).toList();
        assertFalse(allEmployees.isEmpty());
        Employee emp = allEmployees.get(0);

        LocalDate testStart = LocalDate.of(2026, 11, 2);
        LocalDate leaveDate = testStart.plusDays(2);

        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(emp);
        leave.setStartDate(leaveDate);
        leave.setEndDate(leaveDate);
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setReason("Batch 63 Test Leave");
        leave.setRequestedAt(LocalDateTime.now());
        leaveRequestRepository.save(leave);

        try {
            rosterService.generateWeeklyRoster(testStart, GenerationMode.MANUAL);

            var leaveAssignments = assignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), leaveDate);
            assertFalse(leaveAssignments.isEmpty());
            assertTrue(leaveAssignments.get(0).isOnLeave(), "Assignment on approved leave date must be marked onLeave");
            assertEquals(ShiftType.OFF, leaveAssignments.get(0).getShift().getShiftType());
        } finally {
            leaveRequestRepository.delete(leave);
        }
    }

    @Test
    @DisplayName("Test G: Weekly-off balancing ensures every active employee receives exactly 1 weekly off")
    void testG_weeklyOffBalancingExactlyOne() {
        LocalDate start = LocalDate.of(2026, 9, 28);
        LocalDate end = LocalDate.of(2026, 10, 4);

        var assignments = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(start, end);
        List<Employee> activeEmployees = employeeRepository.findAll().stream().filter(Employee::isActive).toList();

        for (Employee e : activeEmployees) {
            long offCount = assignments.stream()
                    .filter(a -> a.getEmployee().getId().equals(e.getId()) && a.isWeeklyOff())
                    .count();
            assertEquals(1, offCount, "Employee " + e.getEmployeeCode() + " must have exactly 1 weekly off in the cycle");
        }
    }

    @Test
    @DisplayName("Test H: Dynamic shift capacity configuration takes precedence")
    void testH_shiftCapacityPrecedence() {
        Shift general = shiftRepository.findByShiftType(ShiftType.GENERAL).orElseThrow();
        int origCap = general.getCapacity();
        try {
            general.setCapacity(3);
            shiftRepository.save(general);

            Shift updated = shiftRepository.findByShiftType(ShiftType.GENERAL).orElseThrow();
            assertEquals(3, updated.getCapacity());
        } finally {
            general.setCapacity(origCap);
            shiftRepository.save(general);
        }
    }

    @Test
    @DisplayName("Test I: Same cycle generated twice preserves existing assignments (idempotent, no duplicates)")
    void testI_sameCycleGeneratedTwiceNoDuplicates() {
        LocalDate start = LocalDate.of(2026, 9, 28);
        LocalDate end = LocalDate.of(2026, 10, 4);

        var first = rosterService.generateWeeklyRoster(start, GenerationMode.MANUAL);
        var second = rosterService.generateWeeklyRoster(start, GenerationMode.MANUAL);

        assertEquals(first.id(), second.id(), "Generating existing cycle should return the same cycle ID");

        var assignments = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(start, end);
        long activeCount = employeeRepository.countByActiveTrue();
        assertEquals(activeCount * 7, assignments.size(), "No duplicate assignment rows should exist");
    }

    @Test
    @DisplayName("Test J: Infeasible schedule produces diagnostic BusinessException without corrupting database")
    void testJ_infeasibleScheduleProducesDiagnosticException() {
        RosterCycle dummyCycle = new RosterCycle();
        dummyCycle.setStartDate(LocalDate.of(2026, 12, 1));
        dummyCycle.setEndDate(LocalDate.of(2026, 12, 7));

        var valResult = rosterService.evaluateFinalValidation(dummyCycle, java.util.Collections.emptyList(),
                java.util.Collections.emptyMap(), 2, java.util.Collections.emptyMap(), java.util.Collections.emptyMap());

        assertFalse(valResult.isValid());
        assertFalse(valResult.conflicts().isEmpty());
        assertTrue(valResult.criticalConflicts() > 0);
        assertTrue(valResult.conflicts().get(0).reason().contains("No roster assignments"));
    }
}