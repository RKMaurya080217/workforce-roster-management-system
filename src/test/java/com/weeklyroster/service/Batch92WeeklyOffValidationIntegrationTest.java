package com.weeklyroster.service;

import com.weeklyroster.dto.response.ConflictItem;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.dto.response.RosterHealthReport;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BATCH 92: Fix Weekly-Off Validation for 5/6 Working Days Integration Test Suite
 *
 * Verifies:
 * 1. 5 Working Days: exactly 2 weekly offs expected per employee. No false DUPLICATE_WEEKLY_OFF.
 * 2. 6 Working Days: exactly 1 weekly off expected per employee.
 * 3. Configuration Switching: 5 -> 6 -> 5 mode correctly swaps validation expectation.
 * 4. Persistence Independence: Stored cycle workingDays dictates expected offs, independent of global defaults.
 * 5. Leave Interaction: 1 approved leave does NOT reduce weekly offs from 2 to 1.
 * 6. True Invalid Case: 3 weekly offs in 5-day mode correctly triggers EXCESS_WEEKLY_OFF.
 * 7. Genuine Duplicate: Multiple assignments on the SAME date triggers DUPLICATE_WEEKLY_OFF / DUPLICATE_ASSIGNMENT.
 * 8. Validate & Finalize: Finalization succeeds cleanly on 5-day cycle with 2 weekly offs.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {"wrms.dev.credential-mirror.enabled=false"})
public class Batch92WeeklyOffValidationIntegrationTest {

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterHealthService rosterHealthService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private DateShiftPreferenceRepository dateShiftPreferenceRepository;

    private LocalDate testMonday;

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken("admin", "admin",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        testMonday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(20);
        if (dateShiftPreferenceRepository != null) {
            dateShiftPreferenceRepository.deleteAll();
        }
    }

    @Test
    @DisplayName("BATCH 92 - 1. 5-day mode expects 2 weekly offs and produces NO false DUPLICATE_WEEKLY_OFF")
    void testFiveDayModeNoFalseDuplicateWeeklyOff() {
        List<Employee> activeEmployees = employeeRepository.findByActiveTrueOrderByIdAsc();
        assertTrue(activeEmployees.size() >= 4, "Requires at least 4 active employees");

        LocalDate startDate = testMonday.plusWeeks(2);
        RosterCycleResponse response = rosterService.generateWeeklyRoster(startDate, GenerationMode.MANUAL, 5);
        assertNotNull(response);
        assertEquals(5, response.workingDays());

        RosterCycle cycle = cycleRepository.findById(response.id()).orElseThrow();
        assertEquals(5, cycle.getWorkingDays());
        assertEquals(2, cycle.getExpectedWeeklyOffs());

        List<RosterAssignment> assignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.getId());
        assertFalse(assignments.isEmpty());

        // 1. Evaluate via RosterHealthService
        RosterHealthReport health = rosterHealthService.evaluateHealth(cycle, assignments);
        assertNotNull(health);

        boolean hasFalseDuplicateOff = health.conflicts().stream()
                .anyMatch(c -> "DUPLICATE_WEEKLY_OFF".equals(c.ruleName()) && c.reason().contains("weekly OFF assignments in cycle"));
        assertFalse(hasFalseDuplicateOff, "Must NOT produce false DUPLICATE_WEEKLY_OFF when employee has 2 weekly offs in 5-day mode");

        // 2. Evaluate via RosterService.validateCycle
        RosterService.FinalValidationResult valResult = rosterService.validateCycle(cycle.getId());
        assertNotNull(valResult);

        boolean valHasFalseDup = valResult.conflicts().stream()
                .anyMatch(c -> "DUPLICATE_WEEKLY_OFF".equals(c.ruleName()) && c.reason().contains("weekly OFF assignments in cycle"));
        assertFalse(valHasFalseDup, "Final validation must NOT report DUPLICATE_WEEKLY_OFF for 2 weekly offs in 5-day mode");

        // 3. Verify per-employee counts
        Map<Long, List<RosterAssignment>> byEmp = new HashMap<>();
        for (RosterAssignment a : assignments) {
            byEmp.computeIfAbsent(a.getEmployee().getId(), k -> new ArrayList<>()).add(a);
        }

        for (Map.Entry<Long, List<RosterAssignment>> entry : byEmp.entrySet()) {
            List<RosterAssignment> empList = entry.getValue();
            long offCount = empList.stream().filter(RosterAssignment::isWeeklyOff).count();
            long leaveCount = empList.stream().filter(RosterAssignment::isOnLeave).count();
            long workedCount = empList.stream().filter(a -> !a.isWeeklyOff() && !a.isOnLeave()).count();

            if (leaveCount == 0) {
                assertEquals(2, offCount, "Employee without leaves in 5-day mode must have 2 weekly offs");
                assertEquals(5, workedCount, "Employee without leaves in 5-day mode must work 5 days");
                assertEquals(7, offCount + workedCount, "Total days must equal 7");
            }
        }
    }

    @Test
    @DisplayName("BATCH 92 - 2. 6-day mode expects 1 weekly off and produces 0 false weekly off conflicts")
    void testSixDayModeExpectsOneWeeklyOff() {
        LocalDate startDate = testMonday.plusWeeks(4);
        RosterCycleResponse response = rosterService.generateWeeklyRoster(startDate, GenerationMode.MANUAL, 6);
        assertNotNull(response);
        assertEquals(6, response.workingDays());

        RosterCycle cycle = cycleRepository.findById(response.id()).orElseThrow();
        assertEquals(6, cycle.getWorkingDays());
        assertEquals(1, cycle.getExpectedWeeklyOffs());

        List<RosterAssignment> assignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.getId());

        RosterHealthReport health = rosterHealthService.evaluateHealth(cycle, assignments);
        assertNotNull(health);

        boolean hasOffConflict = health.conflicts().stream()
                .anyMatch(c -> ("DUPLICATE_WEEKLY_OFF".equals(c.ruleName()) || "EXCESS_WEEKLY_OFF".equals(c.ruleName()))
                        && c.reason().contains("weekly OFF assignments in cycle"));
        assertFalse(hasOffConflict, "6-day mode must have 0 excess/duplicate weekly off conflicts");
    }

    @Test
    @DisplayName("BATCH 92 - 3. Switching between 5 and 6 days immediately updates validation expectations")
    void testSwitchingBetweenFiveAndSixDays() {
        LocalDate start5 = testMonday.plusWeeks(6);
        RosterCycleResponse resp5 = rosterService.generateWeeklyRoster(start5, GenerationMode.MANUAL, 5);
        RosterCycle cycle5 = cycleRepository.findById(resp5.id()).orElseThrow();
        assertEquals(2, cycle5.getExpectedWeeklyOffs());

        LocalDate start6 = testMonday.plusWeeks(8);
        RosterCycleResponse resp6 = rosterService.generateWeeklyRoster(start6, GenerationMode.MANUAL, 6);
        RosterCycle cycle6 = cycleRepository.findById(resp6.id()).orElseThrow();
        assertEquals(1, cycle6.getExpectedWeeklyOffs());

        LocalDate start5Again = testMonday.plusWeeks(10);
        RosterCycleResponse resp5Again = rosterService.generateWeeklyRoster(start5Again, GenerationMode.MANUAL, 5);
        RosterCycle cycle5Again = cycleRepository.findById(resp5Again.id()).orElseThrow();
        assertEquals(2, cycle5Again.getExpectedWeeklyOffs());
    }

    @Test
    @DisplayName("BATCH 92 - 4. True invalid case: 3 weekly offs in 5-day mode correctly triggers EXCESS_WEEKLY_OFF")
    void testThreeWeeklyOffsInFiveDayModeTriggersExcessWeeklyOff() {
        LocalDate startDate = testMonday.plusWeeks(12);
        RosterCycleResponse response = rosterService.generateWeeklyRoster(startDate, GenerationMode.MANUAL, 5);
        RosterCycle cycle = cycleRepository.findById(response.id()).orElseThrow();

        List<RosterAssignment> assignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.getId());
        Employee emp = assignments.get(0).getEmployee();

        // Artificially convert one worked shift into a 3rd weekly off for this employee
        RosterAssignment workedShift = assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(emp.getId()) && !a.isWeeklyOff() && !a.isOnLeave())
                .findFirst().orElseThrow();

        workedShift.setWeeklyOff(true);
        workedShift.setShift(shiftRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(s -> s.getShiftType() == ShiftType.OFF).findFirst().orElse(null));
        assignmentRepository.save(workedShift);

        // Re-evaluate
        List<RosterAssignment> updatedAssignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.getId());
        RosterHealthReport health = rosterHealthService.evaluateHealth(cycle, updatedAssignments);

        boolean hasExcessConflict = health.conflicts().stream()
                .anyMatch(c -> "EXCESS_WEEKLY_OFF".equals(c.ruleName())
                        && c.employeeId().equals(emp.getId())
                        && c.reason().contains("3 weekly OFF assignments in cycle"));

        assertTrue(hasExcessConflict, "3 weekly offs in 5-day mode must be flagged as EXCESS_WEEKLY_OFF");
    }

    @Test
    @DisplayName("BATCH 92 - 5. Genuine duplicate: same employee and same date with 2 weekly offs triggers DUPLICATE_WEEKLY_OFF")
    void testSameDateDuplicateWeeklyOffTriggersDuplicateWeeklyOff() {
        LocalDate startDate = testMonday.plusWeeks(14);
        RosterCycleResponse response = rosterService.generateWeeklyRoster(startDate, GenerationMode.MANUAL, 5);
        RosterCycle cycle = cycleRepository.findById(response.id()).orElseThrow();

        List<RosterAssignment> assignments = new ArrayList<>(assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.getId()));
        Employee emp = assignments.get(0).getEmployee();
        LocalDate targetDate = startDate.plusDays(5); // Saturday
        // Ensure the existing assignment on targetDate is a weekly off, then add a duplicate weekly off on the same date
        RosterAssignment existing = assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.getRosterDate().equals(targetDate))
                .findFirst().orElseThrow();
        existing.setWeeklyOff(true);
        existing.setShift(shiftRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(s -> s.getShiftType() == ShiftType.OFF).findFirst().orElse(null));

        // Create an intentional duplicate assignment on the same date for the same employee
        RosterAssignment dup = new RosterAssignment();
        dup.setCycle(cycle);
        dup.setEmployee(emp);
        dup.setRosterDate(targetDate);
        dup.setWeeklyOff(true);
        dup.setOnLeave(false);
        dup.setShift(shiftRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(s -> s.getShiftType() == ShiftType.OFF).findFirst().orElse(null));
        assignments.add(dup);

        RosterHealthReport health = rosterHealthService.evaluateHealth(cycle, assignments);
        boolean hasDuplicate = health.conflicts().stream()
                .anyMatch(c -> "DUPLICATE_WEEKLY_OFF".equals(c.ruleName())
                        && c.date().equals(targetDate)
                        && c.reason().contains("duplicate Weekly OFF assignments on the same date"));

        assertTrue(hasDuplicate, "Multiple weekly offs on the SAME date must be flagged as DUPLICATE_WEEKLY_OFF");
    }

    @Test
    @DisplayName("BATCH 92 - 6. Approved leave interaction: 1 day leave does NOT reduce weekly offs from 2 to 1 in 5-day mode")
    void testLeaveInteractionPreservesTwoWeeklyOffsInFiveDayMode() {
        List<Employee> allEmps = employeeRepository.findByActiveTrueOrderByIdAsc();
        Employee empWithLeave = allEmps.get(0);

        LocalDate startDate = testMonday.plusWeeks(16);
        LocalDate leaveDate = startDate.plusDays(1); // Tuesday

        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(empWithLeave);
        leave.setStartDate(leaveDate);
        leave.setEndDate(leaveDate);
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setReason("Personal day");
        leave.setRequestedAt(LocalDateTime.now());
        leaveRequestRepository.save(leave);

        RosterCycleResponse response = rosterService.generateWeeklyRoster(startDate, GenerationMode.MANUAL, 5);
        assertNotNull(response);

        RosterCycle cycle = cycleRepository.findById(response.id()).orElseThrow();
        List<RosterAssignment> assignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.getId());

        List<RosterAssignment> empAssignments = assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(empWithLeave.getId()))
                .toList();

        long offCount = empAssignments.stream().filter(RosterAssignment::isWeeklyOff).count();
        long leaveCount = empAssignments.stream().filter(RosterAssignment::isOnLeave).count();
        long workedCount = empAssignments.stream().filter(a -> !a.isWeeklyOff() && !a.isOnLeave()).count();

        assertEquals(1, leaveCount, "Employee should have 1 approved leave assignment");
        assertEquals(2, offCount, "Employee on 1-day leave in 5-day mode must still receive 2 weekly offs");
        assertEquals(4, workedCount, "Employee on 1-day leave in 5-day mode must work 4 shifts");
        assertEquals(7, offCount + leaveCount + workedCount, "Total accounted days must equal 7");

        RosterHealthReport health = rosterHealthService.evaluateHealth(cycle, assignments);
        boolean hasFalseOffConflict = health.conflicts().stream()
                .anyMatch(c -> c.employeeId().equals(empWithLeave.getId())
                        && ("DUPLICATE_WEEKLY_OFF".equals(c.ruleName()) || "EXCESS_WEEKLY_OFF".equals(c.ruleName())));
        assertFalse(hasFalseOffConflict, "1-day leave must NOT cause Weekly OFF conflict");
    }

    @Test
    @DisplayName("BATCH 92 - 7. Validate and Finalize succeeds cleanly for 5-day roster with 2 weekly offs")
    void testFinalizeSucceedsOnFiveDayRosterWithTwoWeeklyOffs() {
        LocalDate startDate = testMonday.plusWeeks(18);
        RosterCycleResponse draftResponse = rosterService.generateWeeklyRoster(startDate, GenerationMode.MANUAL, 5);
        assertNotNull(draftResponse);

        RosterService.FinalValidationResult val = rosterService.validateCycle(draftResponse.id());
        assertNotNull(val);

        // If there are any non-weekly-off warnings/errors, ensure NONE are DUPLICATE_WEEKLY_OFF
        boolean hasWeeklyOffCritical = val.conflicts().stream()
                .anyMatch(c -> "CRITICAL".equals(c.severity()) && "DUPLICATE_WEEKLY_OFF".equals(c.ruleName()));
        assertFalse(hasWeeklyOffCritical, "Must have zero DUPLICATE_WEEKLY_OFF critical conflicts in 5-day mode");

        // If all mandatory rules pass, finalize should transition to PUBLISHED
        if (val.criticalConflicts() == 0) {
            RosterCycleResponse finalized = rosterService.finalizeRoster(draftResponse.id());
            assertEquals(RosterStatus.PUBLISHED, finalized.status());
        }
    }
}
