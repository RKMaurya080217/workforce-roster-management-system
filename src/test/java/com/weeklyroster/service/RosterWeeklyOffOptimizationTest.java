package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.LeaveRequest;
import com.weeklyroster.entity.LeaveStatus;
import com.weeklyroster.entity.RosterAssignment;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.repository.EmailDeliveryLogRepository;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.LeaveRequestRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.RosterOverrideRepository;
import com.weeklyroster.repository.ShiftRepository;

class RosterWeeklyOffOptimizationTest {

    private EmployeeRepository employeeRepository;
    private ShiftRepository shiftRepository;
    private RosterCycleRepository cycleRepository;
    private RosterAssignmentRepository assignmentRepository;
    private RosterOverrideRepository overrideRepository;
    private LeaveRequestRepository leaveRepository;
    private EmailDeliveryLogRepository emailLogRepository;

    private RosterService rosterService;
    private List<Employee> sevenEmployees;
    private List<Shift> standardShifts;

    @BeforeEach
    void setUp() {
        employeeRepository = mock(EmployeeRepository.class);
        shiftRepository = mock(ShiftRepository.class);
        cycleRepository = mock(RosterCycleRepository.class);
        assignmentRepository = mock(RosterAssignmentRepository.class);
        overrideRepository = mock(RosterOverrideRepository.class);
        leaveRepository = mock(LeaveRequestRepository.class);
        emailLogRepository = mock(EmailDeliveryLogRepository.class);

        rosterService = new RosterService(employeeRepository, shiftRepository, cycleRepository,
                assignmentRepository, overrideRepository, leaveRepository, emailLogRepository);

        // Standard 7 Active Employees (5 Males, 2 Females)
        Employee e1 = createEmployee(1L, "EMP001", "Rajat", "Maurya", Gender.MALE);
        Employee e2 = createEmployee(2L, "EMP002", "Prachi", "Mishra", Gender.FEMALE);
        Employee e3 = createEmployee(3L, "EMP003", "Shriram", "Kumar", Gender.MALE);
        Employee e4 = createEmployee(4L, "EMP004", "Sapna", "Pandey", Gender.FEMALE);
        Employee e5 = createEmployee(5L, "EMP005", "Tushar", "Chandila", Gender.MALE);
        Employee e6 = createEmployee(6L, "EMP006", "Divyansh", "Sharma", Gender.MALE);
        Employee e7 = createEmployee(7L, "EMP007", "Aman", "Singh", Gender.MALE);
        sevenEmployees = List.of(e1, e2, e3, e4, e5, e6, e7);

        standardShifts = List.of(
                createShift(1L, ShiftType.MORNING, 2),
                createShift(2L, ShiftType.GENERAL, 2),
                createShift(3L, ShiftType.EVENING, 1),
                createShift(4L, ShiftType.NIGHT, 1),
                createShift(5L, ShiftType.OFF, 0)
        );

        when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(standardShifts);
        when(employeeRepository.findByActiveTrueOrderByIdAsc()).thenReturn(sevenEmployees);
        when(employeeRepository.countByActiveTrue()).thenReturn(7L);

        mockCycleSave();
    }

    private void mockCycleSave() {
        lenient().when(cycleRepository.findByStartDateAndEndDate(any(LocalDate.class), any(LocalDate.class))).thenReturn(Optional.empty());
        lenient().when(cycleRepository.save(any(RosterCycle.class))).thenAnswer(inv -> {
            RosterCycle c = inv.getArgument(0);
            c.setId(101L);
            return c;
        });
        lenient().when(assignmentRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(assignmentRepository.findWorkedAssignmentsBefore(anyLong(), any(LocalDate.class))).thenReturn(Collections.emptyList());
    }

    private Employee createEmployee(Long id, String code, String first, String last, Gender gender) {
        Employee e = new Employee();
        e.setId(id);
        e.setEmployeeCode(code);
        e.setFirstName(first);
        e.setLastName(last);
        e.setEmail(code.toLowerCase() + "@company.com");
        e.setGender(gender);
        e.setActive(true);
        return e;
    }

    private Shift createShift(Long id, ShiftType type, int capacity) {
        Shift s = new Shift();
        s.setId(id);
        s.setShiftType(type);
        s.setCapacity(capacity);
        s.setActive(true);
        if (type == ShiftType.MORNING) {
            s.setStartTime(java.time.LocalTime.of(7, 0));
            s.setEndTime(java.time.LocalTime.of(15, 0));
        } else if (type == ShiftType.GENERAL) {
            s.setStartTime(java.time.LocalTime.of(9, 30));
            s.setEndTime(java.time.LocalTime.of(18, 0));
        } else if (type == ShiftType.EVENING) {
            s.setStartTime(java.time.LocalTime.of(14, 0));
            s.setEndTime(java.time.LocalTime.of(22, 0));
        } else if (type == ShiftType.NIGHT) {
            s.setStartTime(java.time.LocalTime.of(22, 0));
            s.setEndTime(java.time.LocalTime.of(7, 0));
            s.setOvernight(true);
        }
        return s;
    }

    @Test
    @DisplayName("Verify 7-employee roster generation with 0-OFF weekdays and clustered weekend OFFs")
    void testClusteredWeekendOff_And_ZeroOffWeekdays() {
        // Start on Monday: 2026-08-24 (Monday) to 2026-08-30 (Sunday)
        LocalDate monday = LocalDate.of(2026, 8, 24);
        RosterCycleResponse response = rosterService.generateWeeklyRoster(monday);

        assertNotNull(response);
        assertEquals(49, response.assignments().size()); // 7 employees * 7 days = 49

        // Group assignments by date
        Map<LocalDate, List<RosterAssignmentResponse>> byDate = response.assignments().stream()
                .collect(Collectors.groupingBy(RosterAssignmentResponse::rosterDate));

        assertEquals(7, byDate.size());

        // Count OFFs per day
        Map<LocalDate, Long> offCountByDate = byDate.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().stream().filter(RosterAssignmentResponse::weeklyOff).count()));

        // Check Saturday and Sunday OFF counts
        LocalDate saturday = monday.plusDays(5);
        LocalDate sunday = monday.plusDays(6);

        long satOffs = offCountByDate.getOrDefault(saturday, 0L);
        long sunOffs = offCountByDate.getOrDefault(sunday, 0L);

        assertTrue(satOffs >= 1 && satOffs <= 3, "Saturday should have clustered OFFs (1-3): actual = " + satOffs);
        assertTrue(sunOffs >= 1 && sunOffs <= 3, "Sunday should have clustered OFFs (1-3): actual = " + sunOffs);

        // Check that at least one weekday has 0 OFF
        long zeroOffDays = offCountByDate.entrySet().stream()
                .filter(e -> e.getKey().getDayOfWeek() != DayOfWeek.SATURDAY && e.getKey().getDayOfWeek() != DayOfWeek.SUNDAY)
                .filter(e -> e.getValue() == 0)
                .count();

        assertTrue(zeroOffDays >= 1, "At least one weekday should have 0 OFF (full workforce): actual zero-off weekdays = " + zeroOffDays);

        // Maximum OFF per day must never exceed 3
        for (Map.Entry<LocalDate, Long> entry : offCountByDate.entrySet()) {
            assertTrue(entry.getValue() <= 3, "Daily OFF count must never exceed 3 on " + entry.getKey() + ": actual = " + entry.getValue());
        }

        // Each employee gets exactly 1 weekly OFF in the 7-day cycle
        Map<Long, Long> empOffCounts = response.assignments().stream()
                .filter(RosterAssignmentResponse::weeklyOff)
                .collect(Collectors.groupingBy(RosterAssignmentResponse::employeeId, Collectors.counting()));

        for (Employee emp : sevenEmployees) {
            assertEquals(1L, empOffCounts.getOrDefault(emp.getId(), 0L),
                    "Employee " + emp.getEmployeeCode() + " should receive exactly 1 weekly OFF in cycle");
        }
    }

    @Test
    @DisplayName("Verify mandatory daily coverage (Morning >= 1, General >= 1, Evening >= 1, Night = 1) across all 7 days")
    void testMandatoryShiftCoverage_AllDays() {
        LocalDate monday = LocalDate.of(2026, 8, 24);
        RosterCycleResponse response = rosterService.generateWeeklyRoster(monday);

        Map<LocalDate, List<RosterAssignmentResponse>> byDate = response.assignments().stream()
                .collect(Collectors.groupingBy(RosterAssignmentResponse::rosterDate));

        for (Map.Entry<LocalDate, List<RosterAssignmentResponse>> entry : byDate.entrySet()) {
            LocalDate date = entry.getKey();
            List<RosterAssignmentResponse> dayList = entry.getValue();

            long morningCount = dayList.stream().filter(a -> !a.weeklyOff() && !a.onLeave() && a.shiftType() == ShiftType.MORNING).count();
            long generalCount = dayList.stream().filter(a -> !a.weeklyOff() && !a.onLeave() && a.shiftType() == ShiftType.GENERAL).count();
            long eveningCount = dayList.stream().filter(a -> !a.weeklyOff() && !a.onLeave() && a.shiftType() == ShiftType.EVENING).count();
            long nightCount = dayList.stream().filter(a -> !a.weeklyOff() && !a.onLeave() && a.shiftType() == ShiftType.NIGHT).count();

            assertTrue(morningCount >= 1, "Morning must be >= 1 on " + date + ": actual = " + morningCount);
            assertTrue(generalCount >= 1, "General must be >= 1 on " + date + ": actual = " + generalCount);
            assertTrue(eveningCount >= 1, "Evening must be >= 1 on " + date + ": actual = " + eveningCount);
            assertEquals(1, nightCount, "Night must be EXACTLY 1 on " + date + ": actual = " + nightCount);
        }
    }

    @Test
    @DisplayName("Verify hard constraints: female restriction and max 2 nights per employee")
    void testHardConstraints_FemaleAndNightLimits() {
        LocalDate monday = LocalDate.of(2026, 8, 24);
        RosterCycleResponse response = rosterService.generateWeeklyRoster(monday);

        // Female restriction
        for (RosterAssignmentResponse a : response.assignments()) {
            if (a.gender() == Gender.FEMALE && !a.weeklyOff() && !a.onLeave()) {
                assertNotEquals(ShiftType.EVENING, a.shiftType(), "Female employee " + a.employeeName() + " must never receive Evening");
                assertNotEquals(ShiftType.NIGHT, a.shiftType(), "Female employee " + a.employeeName() + " must never receive Night");
            }
        }

        // Night limit per employee <= 2
        Map<Long, Long> empNightCounts = response.assignments().stream()
                .filter(a -> !a.weeklyOff() && !a.onLeave() && a.shiftType() == ShiftType.NIGHT)
                .collect(Collectors.groupingBy(RosterAssignmentResponse::employeeId, Collectors.counting()));

        for (Map.Entry<Long, Long> entry : empNightCounts.entrySet()) {
            assertTrue(entry.getValue() <= 2, "Employee #" + entry.getKey() + " must not exceed 2 Night shifts: actual = " + entry.getValue());
        }
    }

    @Test
    @DisplayName("Verify weekend fairness rotation: employees with fewer past weekend OFFs receive weekend OFF")
    void testWeekendFairnessRotation() {
        LocalDate monday = LocalDate.of(2026, 8, 24);

        // Simulate that EMP001 and EMP003 already had 5 weekend OFFs in past cycles
        // while EMP005, EMP006, EMP007 had 0 weekend OFFs
        List<RosterAssignment> pastAssignmentsEmp1 = createMockWeekendOffAssignments(sevenEmployees.get(0), 5);
        List<RosterAssignment> pastAssignmentsEmp3 = createMockWeekendOffAssignments(sevenEmployees.get(2), 5);

        when(assignmentRepository.findTop30ByEmployeeIdOrderByRosterDateDesc(1L)).thenReturn(pastAssignmentsEmp1);
        when(assignmentRepository.findTop30ByEmployeeIdOrderByRosterDateDesc(3L)).thenReturn(pastAssignmentsEmp3);
        when(assignmentRepository.findTop30ByEmployeeIdOrderByRosterDateDesc(5L)).thenReturn(Collections.emptyList());
        when(assignmentRepository.findTop30ByEmployeeIdOrderByRosterDateDesc(6L)).thenReturn(Collections.emptyList());
        when(assignmentRepository.findTop30ByEmployeeIdOrderByRosterDateDesc(7L)).thenReturn(Collections.emptyList());

        RosterCycleResponse response = rosterService.generateWeeklyRoster(monday);

        LocalDate saturday = monday.plusDays(5);
        LocalDate sunday = monday.plusDays(6);

        // Find employees who got weekend OFF in this cycle
        List<Long> weekendOffEmpIds = response.assignments().stream()
                .filter(RosterAssignmentResponse::weeklyOff)
                .filter(a -> a.rosterDate().equals(saturday) || a.rosterDate().equals(sunday))
                .map(RosterAssignmentResponse::employeeId)
                .toList();

        // Employees with 0 past weekend OFFs (EMP005, EMP006, EMP007) should be prioritized for weekend OFF
        assertTrue(weekendOffEmpIds.contains(5L) || weekendOffEmpIds.contains(6L) || weekendOffEmpIds.contains(7L),
                "Employees with fewer past weekend OFFs should receive weekend OFF");
    }

    @Test
    @DisplayName("Verify approved leave and weekly OFF are separate (employee receives 1 leave and 1 weekly OFF)")
    void testApprovedLeave_PreservesBothLeaveAndWeeklyOff() {
        LocalDate monday = LocalDate.of(2026, 8, 24);
        LocalDate wednesday = monday.plusDays(2);

        // EMP001 has approved leave on Wednesday
        when(leaveRepository.existsByEmployeeIdAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                anyLong(), any(), any(), any())).thenReturn(false);
        when(leaveRepository.existsByEmployeeIdAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                eq(1L), eq(LeaveStatus.APPROVED), eq(wednesday), eq(wednesday))).thenReturn(true);

        RosterCycleResponse response = rosterService.generateWeeklyRoster(monday);

        // Check assignments for EMP001
        List<RosterAssignmentResponse> emp1Assignments = response.assignments().stream()
                .filter(a -> a.employeeId().equals(1L))
                .toList();

        long leaveCount = emp1Assignments.stream().filter(RosterAssignmentResponse::onLeave).count();
        long offCount = emp1Assignments.stream().filter(RosterAssignmentResponse::weeklyOff).count();
        long workCount = emp1Assignments.stream().filter(a -> !a.onLeave() && !a.weeklyOff() && a.shiftType() != ShiftType.OFF).count();

        assertEquals(1, leaveCount, "EMP001 should have 1 LEAVE assignment on Wednesday");
        assertEquals(1, offCount, "EMP001 should receive exactly 1 weekly OFF");
        assertEquals(5, workCount, "EMP001 should work remaining 5 days (1 Leave + 1 OFF + 5 Work = 7 days)");
    }

    private List<RosterAssignment> createMockWeekendOffAssignments(Employee emp, int count) {
        List<RosterAssignment> list = new ArrayList<>();
        LocalDate baseSunday = LocalDate.of(2026, 8, 16); // Sunday
        for (int i = 0; i < count; i++) {
            RosterAssignment a = new RosterAssignment();
            a.setEmployee(emp);
            a.setRosterDate(baseSunday.minusWeeks(i));
            a.setWeeklyOff(true);
            a.setOnLeave(false);
            list.add(a);
        }
        return list;
    }
}
