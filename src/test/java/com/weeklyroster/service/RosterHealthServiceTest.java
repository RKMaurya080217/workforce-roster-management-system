package com.weeklyroster.service;

import com.weeklyroster.dto.response.RosterHealthReport;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.LeaveRequestRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RosterHealthServiceTest {

    @Mock
    private RosterCycleRepository cycleRepository;
    @Mock
    private RosterAssignmentRepository assignmentRepository;
    @Mock
    private LeaveRequestRepository leaveRepository;

    @InjectMocks
    private RosterHealthService rosterHealthService;

    private RosterCycle cycle;
    private Shift morningShift;
    private Shift generalShift;
    private Shift eveningShift;
    private Shift nightShift;
    private Shift offShift;
    private Employee maleEmp1;
    private Employee maleEmp2;
    private Employee femaleEmp1;
    private Employee femaleEmp2;

    @BeforeEach
    void setUp() {
        cycle = new RosterCycle();
        cycle.setId(1L);
        cycle.setStartDate(LocalDate.of(2026, 8, 24)); // Monday
        cycle.setEndDate(LocalDate.of(2026, 8, 30));   // Sunday
        cycle.setStatus(RosterStatus.GENERATED);

        morningShift = new Shift();
        morningShift.setId(1L);
        morningShift.setShiftType(ShiftType.MORNING);
        morningShift.setStartTime(LocalTime.of(6, 0));
        morningShift.setEndTime(LocalTime.of(14, 0));

        generalShift = new Shift();
        generalShift.setId(2L);
        generalShift.setShiftType(ShiftType.GENERAL);
        generalShift.setStartTime(LocalTime.of(9, 0));
        generalShift.setEndTime(LocalTime.of(17, 0));

        eveningShift = new Shift();
        eveningShift.setId(3L);
        eveningShift.setShiftType(ShiftType.EVENING);
        eveningShift.setStartTime(LocalTime.of(14, 0));
        eveningShift.setEndTime(LocalTime.of(22, 0));

        nightShift = new Shift();
        nightShift.setId(4L);
        nightShift.setShiftType(ShiftType.NIGHT);
        nightShift.setStartTime(LocalTime.of(22, 0));
        nightShift.setEndTime(LocalTime.of(6, 0));
        nightShift.setOvernight(true);

        offShift = new Shift();
        offShift.setId(5L);
        offShift.setShiftType(ShiftType.OFF);

        maleEmp1 = createEmployee(1L, "EMP001", "John", "Doe", Gender.MALE);
        maleEmp2 = createEmployee(2L, "EMP002", "Bob", "Builder", Gender.MALE);
        femaleEmp1 = createEmployee(3L, "EMP003", "Alice", "Wonder", Gender.FEMALE);
        femaleEmp2 = createEmployee(4L, "EMP004", "Clara", "Oswald", Gender.FEMALE);
    }

    private Employee createEmployee(Long id, String code, String first, String last, Gender gender) {
        Employee e = new Employee();
        e.setId(id);
        e.setEmployeeCode(code);
        e.setFirstName(first);
        e.setLastName(last);
        e.setGender(gender);
        e.setActive(true);
        return e;
    }

    private RosterAssignment createAssignment(Long id, Employee emp, Shift shift, LocalDate date, boolean off, boolean leave) {
        RosterAssignment a = new RosterAssignment();
        a.setId(id);
        a.setCycle(cycle);
        a.setEmployee(emp);
        a.setShift(shift);
        a.setRosterDate(date);
        a.setWeeklyOff(off);
        a.setOnLeave(leave);
        return a;
    }

    @Test
    @DisplayName("Health evaluation flags female night shift as CRITICAL conflict")
    void testFemaleNightShiftViolation() {
        List<RosterAssignment> assignments = new ArrayList<>();
        LocalDate monday = LocalDate.of(2026, 8, 24);

        // Assign female employee to night shift
        assignments.add(createAssignment(1L, femaleEmp1, nightShift, monday, false, false));

        RosterHealthReport report = rosterHealthService.evaluateHealth(cycle, assignments);

        assertNotNull(report);
        assertFalse(report.readyToPublish());
        assertTrue(report.criticalConflictsCount() > 0);
        assertTrue(report.conflicts().stream().anyMatch(c -> c.ruleName().equals("FEMALE_DAY_ONLY")));
    }

    @Test
    @DisplayName("Health evaluation flags under-coverage as CRITICAL conflict")
    void testUnderCoverage() {
        List<RosterAssignment> assignments = new ArrayList<>();
        LocalDate monday = LocalDate.of(2026, 8, 24);

        // Only morning and evening shift present, missing GENERAL and NIGHT
        assignments.add(createAssignment(1L, maleEmp1, morningShift, monday, false, false));
        assignments.add(createAssignment(2L, femaleEmp1, eveningShift, monday, false, false));

        RosterHealthReport report = rosterHealthService.evaluateHealth(cycle, assignments);

        assertFalse(report.readyToPublish());
        assertTrue(report.conflicts().stream().anyMatch(c -> c.ruleName().startsWith("MIN_COVERAGE")));
    }

    @Test
    @DisplayName("Health evaluation flags 12-hour rest violation as HIGH severity")
    void testRestPeriodViolation() {
        List<RosterAssignment> assignments = new ArrayList<>();
        LocalDate day1 = LocalDate.of(2026, 8, 24);
        LocalDate day2 = LocalDate.of(2026, 8, 25);

        // Evening (ends 22:00) followed immediately next morning by Morning (starts 06:00) -> 8 hours rest (< 12)
        assignments.add(createAssignment(1L, maleEmp1, eveningShift, day1, false, false));
        assignments.add(createAssignment(2L, maleEmp1, morningShift, day2, false, false));

        RosterHealthReport report = rosterHealthService.evaluateHealth(cycle, assignments);

        assertTrue(report.conflicts().stream().anyMatch(c -> c.ruleName().equals("REST_INTERVAL_12H")));
    }

    @Test
    @DisplayName("Health evaluation flags excess night shifts in a single cycle")
    void testMaxNightShiftsViolation() {
        List<RosterAssignment> assignments = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 8, 24);

        // Assign 3 night shifts in a single week
        assignments.add(createAssignment(1L, maleEmp1, nightShift, start, false, false));
        assignments.add(createAssignment(2L, maleEmp1, nightShift, start.plusDays(2), false, false));
        assignments.add(createAssignment(3L, maleEmp1, nightShift, start.plusDays(4), false, false));

        RosterHealthReport report = rosterHealthService.evaluateHealth(cycle, assignments);

        assertTrue(report.conflicts().stream().anyMatch(c -> c.ruleName().equals("MAX_NIGHT_LIMIT")));
    }

    @Test
    @DisplayName("Health evaluation flags active working shift during approved leave")
    void testActiveShiftDuringApprovedLeave() {
        List<RosterAssignment> assignments = new ArrayList<>();
        LocalDate monday = LocalDate.of(2026, 8, 24);

        assignments.add(createAssignment(1L, maleEmp1, morningShift, monday, false, false));

        LeaveRequest leave = new LeaveRequest();
        leave.setId(10L);
        leave.setEmployee(maleEmp1);
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setStartDate(monday);
        leave.setEndDate(monday.plusDays(1));

        when(leaveRepository.findApprovedLeavesInCycle(eq(LeaveStatus.APPROVED), any(), any())).thenReturn(List.of(leave));

        RosterHealthReport report = rosterHealthService.evaluateHealth(cycle, assignments);

        assertTrue(report.conflicts().stream().anyMatch(c -> c.ruleName().equals("LEAVE_NON_COMPLIANCE")));
    }
}
