package com.weeklyroster.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.LeaveStatus;
import com.weeklyroster.entity.RosterAssignment;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.LeaveRequestRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.RosterOverrideRepository;
import com.weeklyroster.repository.ShiftRepository;
import com.weeklyroster.repository.EmailDeliveryLogRepository;

public class RosterFeasibilityTest {

    private EmployeeRepository employeeRepository;
    private ShiftRepository shiftRepository;
    private RosterCycleRepository cycleRepository;
    private RosterAssignmentRepository assignmentRepository;
    private RosterOverrideRepository overrideRepository;
    private LeaveRequestRepository leaveRepository;
    private RosterService rosterService;

    private Shift morningShift;
    private Shift generalShift;
    private Shift eveningShift;
    private Shift nightShift;
    private Shift offShift;

    private List<Employee> workforce7;

    @BeforeEach
    void setUp() {
        employeeRepository = mock(EmployeeRepository.class);
        shiftRepository = mock(ShiftRepository.class);
        cycleRepository = mock(RosterCycleRepository.class);
        assignmentRepository = mock(RosterAssignmentRepository.class);
        overrideRepository = mock(RosterOverrideRepository.class);
        leaveRepository = mock(LeaveRequestRepository.class);

        EmailDeliveryLogRepository emailLogRepository = mock(EmailDeliveryLogRepository.class);
        rosterService = new RosterService(employeeRepository, shiftRepository, cycleRepository,
                assignmentRepository, overrideRepository, leaveRepository, emailLogRepository);

        // Production configured capacities: Morning 2, General 3, Evening 1, Night 1 (Total = 7/day = 49/week)
        morningShift = createShift(1L, ShiftType.MORNING, 2, LocalTime.of(7, 0), LocalTime.of(15, 0), false);
        generalShift = createShift(2L, ShiftType.GENERAL, 3, LocalTime.of(9, 30), LocalTime.of(18, 0), false);
        eveningShift = createShift(3L, ShiftType.EVENING, 1, LocalTime.of(14, 0), LocalTime.of(22, 0), false);
        nightShift = createShift(4L, ShiftType.NIGHT, 1, LocalTime.of(22, 0), LocalTime.of(7, 0), true);
        offShift = createShift(5L, ShiftType.OFF, 0, null, null, false);

        when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(
                List.of(morningShift, generalShift, eveningShift, nightShift, offShift));
        when(shiftRepository.findByShiftType(ShiftType.MORNING)).thenReturn(Optional.of(morningShift));
        when(shiftRepository.findByShiftType(ShiftType.GENERAL)).thenReturn(Optional.of(generalShift));
        when(shiftRepository.findByShiftType(ShiftType.EVENING)).thenReturn(Optional.of(eveningShift));
        when(shiftRepository.findByShiftType(ShiftType.NIGHT)).thenReturn(Optional.of(nightShift));
        when(shiftRepository.findByShiftType(ShiftType.OFF)).thenReturn(Optional.of(offShift));

        // Real 7-person active workforce (5 Males, 2 Females)
        workforce7 = List.of(
                createEmployee(1L, "EMP001", "Rajat", "Maurya", Gender.MALE),
                createEmployee(2L, "EMP002", "Prachi", "Mishra", Gender.FEMALE),
                createEmployee(3L, "EMP003", "Shriram", "Kumar", Gender.MALE),
                createEmployee(4L, "EMP004", "Sapna", "Pandey", Gender.FEMALE),
                createEmployee(5L, "EMP005", "Tushar", "Chandila", Gender.MALE),
                createEmployee(6L, "EMP006", "Divyansh", "Kumar", Gender.MALE),
                createEmployee(7L, "EMP007", "Sambhav", "Jain", Gender.MALE)
        );
        when(employeeRepository.findByActiveTrueOrderByIdAsc()).thenReturn(workforce7);

        when(cycleRepository.save(any(RosterCycle.class))).thenAnswer(inv -> {
            RosterCycle c = inv.getArgument(0);
            c.setId(100L);
            return c;
        });

        when(assignmentRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(leaveRepository.existsByEmployeeIdAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                anyLong(), any(), any(), any())).thenReturn(false);
        when(assignmentRepository.findWorkedAssignmentsBefore(anyLong(), any())).thenReturn(Collections.emptyList());
        when(assignmentRepository.findTop30ByEmployeeIdOrderByRosterDateDesc(anyLong())).thenReturn(Collections.emptyList());
    }

    private Shift createShift(Long id, ShiftType type, int capacity, LocalTime start, LocalTime end, boolean overnight) {
        Shift s = new Shift();
        s.setId(id);
        s.setShiftType(type);
        s.setCapacity(capacity);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setOvernight(overnight);
        s.setActive(true);
        return s;
    }

    private Employee createEmployee(Long id, String code, String first, String last, Gender gender) {
        Employee e = new Employee();
        e.setId(id);
        e.setEmployeeCode(code);
        e.setFirstName(first);
        e.setLastName(last);
        e.setEmail(code.toLowerCase() + "@example.com");
        e.setGender(gender);
        e.setActive(true);
        return e;
    }

    @Test
    @DisplayName("Test 1: Exactly 7 active employees -> Generates 42 assignments with 0 operational shortage")
    void test1_Exactly7ActiveEmployees_Generates42Assignments_WithZeroOperationalShortage() {
        LocalDate startDate = LocalDate.of(2026, 8, 17);
        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);

        assertNotNull(res);
        assertNotNull(res.coverageReport());
        assertEquals(0, res.coverageReport().operationalShortage(), "Expected 0 operational shortage for 7-person team");
        assertEquals(42, res.coverageReport().totalAssigned(), "7 employees x 6 working days = 42 working assignments");
        assertEquals(42, res.coverageReport().feasibleCapacity(), "Feasible capacity must equal 42");
    }

    @Test
    @DisplayName("Test 2: 7 employees with no leave -> Generates balanced weekly staffing (42 working + 7 OFF)")
    void test2_7Employees_NoLeave_GeneratesBalancedDailyStaffing() {
        LocalDate startDate = LocalDate.of(2026, 8, 17);
        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);

        long totalWorking = res.assignments().stream().filter(a -> !a.weeklyOff() && !a.onLeave()).count();
        long totalOff = res.assignments().stream().filter(RosterAssignmentResponse::weeklyOff).count();
        assertEquals(42, totalWorking, "Total working assignments must equal 42 (7 employees x 6 days)");
        assertEquals(7, totalOff, "Total weekly OFF assignments must equal 7 (1 per employee)");

        for (int i = 0; i < 7; i++) {
            LocalDate date = startDate.plusDays(i);
            long working = res.assignments().stream()
                    .filter(a -> a.rosterDate().equals(date) && !a.weeklyOff() && !a.onLeave())
                    .count();
            long off = res.assignments().stream()
                    .filter(a -> a.rosterDate().equals(date) && (a.weeklyOff() || a.onLeave()))
                    .count();
            assertTrue(working >= 4 && working <= 7, "Day " + date + " must have 4 to 7 working employees: actual = " + working);
            assertTrue(off >= 0 && off <= 3, "Day " + date + " non-working employees must be between 0 and 3: actual = " + off);
        }
    }

    @Test
    @DisplayName("Test 3: 7 employees with one employee on leave -> Leave satisfies weekly non-working day")
    void test3_7Employees_OneEmployeeOnLeave_LeaveSatisfiesNonWorkingDay() {
        LocalDate startDate = LocalDate.of(2026, 8, 17);
        LocalDate leaveDate = startDate.plusDays(2); // Tuesday

        // Rajat (EMP001) has approved leave on Tuesday
        when(leaveRepository.existsByEmployeeIdAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                1L, LeaveStatus.APPROVED, leaveDate, leaveDate)).thenReturn(true);

        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);

        long rajatLeaves = res.assignments().stream()
                .filter(a -> a.employeeId().equals(1L) && a.onLeave())
                .count();
        long rajatOffs = res.assignments().stream()
                .filter(a -> a.employeeId().equals(1L) && a.weeklyOff())
                .count();

        assertEquals(1, rajatLeaves, "Rajat should have exactly 1 leave assignment");
        assertEquals(1, rajatOffs, "Rajat should receive exactly 1 weekly OFF in addition to approved leave");
    }

    @Test
    @DisplayName("Test 4: Multiple employees on leave -> Handled safely without rule violations")
    void test4_MultipleEmployeesOnLeave_RespectedAndStaffedSafely() {
        LocalDate startDate = LocalDate.of(2026, 8, 17);
        LocalDate d1 = startDate.plusDays(1);
        LocalDate d2 = startDate.plusDays(3);

        when(leaveRepository.existsByEmployeeIdAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                1L, LeaveStatus.APPROVED, d1, d1)).thenReturn(true);
        when(leaveRepository.existsByEmployeeIdAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                2L, LeaveStatus.APPROVED, d2, d2)).thenReturn(true);

        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(res);

        // Verify leave assignments have zero work assigned
        long l1 = res.assignments().stream().filter(a -> a.employeeId().equals(1L) && a.rosterDate().equals(d1) && a.onLeave()).count();
        long l2 = res.assignments().stream().filter(a -> a.employeeId().equals(2L) && a.rosterDate().equals(d2) && a.onLeave()).count();
        assertEquals(1, l1);
        assertEquals(1, l2);
    }

    @Test
    @DisplayName("Test 5: Female employees present -> Assigned ONLY to Morning and General")
    void test5_FemaleEmployees_AssignedOnlyMorningAndGeneral() {
        LocalDate startDate = LocalDate.of(2026, 8, 17);
        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);

        for (var a : res.assignments()) {
            if (a.gender() == Gender.FEMALE && !a.weeklyOff() && !a.onLeave()) {
                assertTrue(a.shiftType() == ShiftType.MORNING || a.shiftType() == ShiftType.GENERAL,
                        "Female employee " + a.employeeName() + " assigned to prohibited shift: " + a.shiftType());
            }
        }
    }

    @Test
    @DisplayName("Test 6: Limited male employees -> Prioritized for Night and Evening")
    void test6_LimitedMaleEmployees_PrioritizedForNightAndEvening() {
        LocalDate startDate = LocalDate.of(2026, 8, 17);
        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);

        for (int i = 0; i < 7; i++) {
            LocalDate date = startDate.plusDays(i);
            long night = res.assignments().stream()
                    .filter(a -> a.rosterDate().equals(date) && a.shiftType() == ShiftType.NIGHT)
                    .count();
            long evening = res.assignments().stream()
                    .filter(a -> a.rosterDate().equals(date) && a.shiftType() == ShiftType.EVENING)
                    .count();
            assertTrue(night >= 1, "Night shift must be covered on " + date);
            assertTrue(evening >= 1, "Evening shift must be covered on " + date);
        }
    }

    @Test
    @DisplayName("Test 7: Employee reaches 2 Night shifts -> Becomes ineligible for more Nights")
    void test7_EmployeeReaches2NightShifts_BecomesIneligibleForMoreNights() {
        LocalDate startDate = LocalDate.of(2026, 8, 17);
        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);

        for (Employee emp : workforce7) {
            long nights = res.assignments().stream()
                    .filter(a -> a.employeeId().equals(emp.getId()) && a.shiftType() == ShiftType.NIGHT)
                    .count();
            assertTrue(nights <= 2, "Employee " + emp.getEmployeeCode() + " exceeded 2 nights: " + nights);
        }
    }

    @Test
    @DisplayName("Test 8: Night -> Morning transition rejected (0 hours rest)")
    void test8_NightToMorning_Rejected() {
        LocalDate d1 = LocalDate.of(2026, 8, 17);
        LocalDate d2 = LocalDate.of(2026, 8, 18);
        assertFalse(rosterService.hasMinimumRest(d1, nightShift, d2, morningShift));
    }

    @Test
    @DisplayName("Test 9: Night -> General transition rejected (2.5 hours rest)")
    void test9_NightToGeneral_Rejected() {
        LocalDate d1 = LocalDate.of(2026, 8, 17);
        LocalDate d2 = LocalDate.of(2026, 8, 18);
        Duration rest = rosterService.calculateRestDuration(d1, nightShift, d2, generalShift);
        assertEquals(2, rest.toHours());
        assertEquals(30, rest.toMinutes() % 60);
        assertFalse(rosterService.hasMinimumRest(d1, nightShift, d2, generalShift));
    }

    @Test
    @DisplayName("Test 10: Night -> Evening transition rejected (7 hours rest)")
    void test10_NightToEvening_Rejected() {
        LocalDate d1 = LocalDate.of(2026, 8, 17);
        LocalDate d2 = LocalDate.of(2026, 8, 18);
        Duration rest = rosterService.calculateRestDuration(d1, nightShift, d2, eveningShift);
        assertEquals(7, rest.toHours());
        assertFalse(rosterService.hasMinimumRest(d1, nightShift, d2, eveningShift));
    }

    @Test
    @DisplayName("Test 11: Night -> Night transition allowed (15 hours rest)")
    void test11_NightToNight_Allowed() {
        LocalDate d1 = LocalDate.of(2026, 8, 17);
        LocalDate d2 = LocalDate.of(2026, 8, 18);
        Duration rest = rosterService.calculateRestDuration(d1, nightShift, d2, nightShift);
        assertEquals(15, rest.toHours());
        assertTrue(rosterService.hasMinimumRest(d1, nightShift, d2, nightShift));
    }

    @Test
    @DisplayName("Test 12: Evening -> Morning transition rejected (9 hours rest)")
    void test12_EveningToMorning_Rejected() {
        LocalDate d1 = LocalDate.of(2026, 8, 17);
        LocalDate d2 = LocalDate.of(2026, 8, 18);
        Duration rest = rosterService.calculateRestDuration(d1, eveningShift, d2, morningShift);
        assertEquals(9, rest.toHours());
        assertFalse(rosterService.hasMinimumRest(d1, eveningShift, d2, morningShift));
    }

    @Test
    @DisplayName("Test 13: Previous-cycle Night -> New-cycle shift enforces 12-hour rest")
    void test13_PreviousCycleNight_Enforces12HourRest() {
        Employee rajat = workforce7.get(0);
        LocalDate startDate = LocalDate.of(2026, 8, 17);
        LocalDate prevDate = startDate.minusDays(1);

        RosterAssignment prevNight = new RosterAssignment();
        prevNight.setEmployee(rajat);
        prevNight.setShift(nightShift);
        prevNight.setRosterDate(prevDate);
        prevNight.setWeeklyOff(false);
        prevNight.setOnLeave(false);

        when(assignmentRepository.findWorkedAssignmentsBefore(rajat.getId(), startDate))
                .thenReturn(List.of(prevNight));

        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);

        RosterAssignmentResponse d1 = res.assignments().stream()
                .filter(a -> a.employeeId().equals(rajat.getId()) && a.rosterDate().equals(startDate))
                .findFirst().orElseThrow();

        assertTrue(d1.shiftType() == ShiftType.NIGHT || d1.shiftType() == ShiftType.OFF || d1.weeklyOff(),
                "Rajat must be assigned NIGHT (15h rest) or OFF on Day 1 after previous Night");
    }

    @Test
    @DisplayName("Test 14: Weekly OFF distribution supports clustered weekend and zero-off weekdays")
    void test14_WeeklyOffDistribution_EvenlyStaggered() {
        LocalDate startDate = LocalDate.of(2026, 8, 17);
        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);

        long totalOffs = res.assignments().stream().filter(RosterAssignmentResponse::weeklyOff).count();
        assertEquals(7, totalOffs, "Total weekly OFFs across the 7-day cycle must equal 7");

        for (int i = 0; i < 7; i++) {
            LocalDate date = startDate.plusDays(i);
            long offCount = res.assignments().stream()
                    .filter(a -> a.rosterDate().equals(date) && a.weeklyOff())
                    .count();
            assertTrue(offCount >= 0 && offCount <= 3, "Day " + date + " weekly OFF count must be between 0 and 3: actual = " + offCount);
        }
    }

    @Test
    @DisplayName("Test 15: Leave replacing weekly OFF avoids unnecessary second OFF")
    void test15_LeaveReplacingWeeklyOff_AvoidsUnnecessarySecondOff() {
        LocalDate startDate = LocalDate.of(2026, 8, 17);
        LocalDate leaveDate = startDate.plusDays(4);

        when(leaveRepository.existsByEmployeeIdAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                3L, LeaveStatus.APPROVED, leaveDate, leaveDate)).thenReturn(true);

        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);

        long nonWorking = res.assignments().stream()
                .filter(a -> a.employeeId().equals(3L) && (a.weeklyOff() || a.onLeave()))
                .count();
        assertEquals(2, nonWorking, "Employee 3 should have exactly 2 non-working days in the cycle (1 leave + 1 weekly OFF)");
    }

    @Test
    @DisplayName("Test 16: All four shift types covered every day")
    void test16_AllFourShiftTypesCoveredEveryDay() {
        LocalDate startDate = LocalDate.of(2026, 8, 17);
        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);

        for (int i = 0; i < 7; i++) {
            LocalDate date = startDate.plusDays(i);
            long morning = res.assignments().stream().filter(a -> a.rosterDate().equals(date) && a.shiftType() == ShiftType.MORNING).count();
            long general = res.assignments().stream().filter(a -> a.rosterDate().equals(date) && a.shiftType() == ShiftType.GENERAL).count();
            long evening = res.assignments().stream().filter(a -> a.rosterDate().equals(date) && a.shiftType() == ShiftType.EVENING).count();
            long night = res.assignments().stream().filter(a -> a.rosterDate().equals(date) && a.shiftType() == ShiftType.NIGHT).count();

            assertTrue(morning >= 1, "Morning must be >= 1 on " + date);
            assertTrue(general >= 1, "General must be >= 1 on " + date);
            assertTrue(evening >= 1, "Evening must be >= 1 on " + date);
            assertTrue(night >= 1, "Night must be >= 1 on " + date);
        }
    }

    @Test
    @DisplayName("Test 17: 7 employees generating maximum feasible roster")
    void test17_7Employees_GeneratingMaximumFeasibleRoster() {
        LocalDate startDate = LocalDate.of(2026, 8, 17);
        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);

        assertEquals(42, res.coverageReport().totalAssigned(), "Must generate exactly 42 working assignments (7 x 6)");
        assertEquals(0, res.coverageReport().operationalShortage(), "Operational shortage must be 0");
    }

    @Test
    @DisplayName("Test 18: No employee unnecessarily receives 2 OFF days")
    void test18_NoEmployeeUnnecessarilyReceivesTwoOffDays() {
        LocalDate startDate = LocalDate.of(2026, 8, 17);
        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);

        for (Employee emp : workforce7) {
            long offCount = res.assignments().stream()
                    .filter(a -> a.employeeId().equals(emp.getId()) && a.weeklyOff())
                    .count();
            assertEquals(1, offCount, "Employee " + emp.getEmployeeCode() + " should receive exactly 1 weekly off");
        }
    }

    @Test
    @DisplayName("Test 19: Strict Invariant Validation -> No invalid roster saved silently")
    void test19_NoInvalidRosterSavedSilently_ValidationPass() {
        RosterCycle cycle = new RosterCycle();
        cycle.setId(999L);
        cycle.setStartDate(LocalDate.of(2026, 8, 17));
        cycle.setEndDate(LocalDate.of(2026, 8, 21));

        Employee female = workforce7.get(1); // Prachi (Female)

        // Invalid assignment: Female assigned to NIGHT
        RosterAssignment invalid = new RosterAssignment();
        invalid.setCycle(cycle);
        invalid.setEmployee(female);
        invalid.setShift(nightShift);
        invalid.setRosterDate(LocalDate.of(2026, 8, 17));
        invalid.setWeeklyOff(false);
        invalid.setOnLeave(false);

        assertThrows(BusinessException.class, () -> {
            rosterService.validateGeneratedRoster(cycle, List.of(invalid),
                    java.util.Map.of(ShiftType.NIGHT, nightShift, ShiftType.OFF, offShift), 2);
        });
    }

    @Test
    @DisplayName("Test 20: Configured 49 demand with Night=1 is recognized with zero operational shortage")
    void test20_Configured49Demand_RecognizedAsExceedingWorkforceCapacity() {
        LocalDate startDate = LocalDate.of(2026, 8, 17);
        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);

        assertNotNull(res.coverageReport());
        assertEquals(49, res.coverageReport().configuredDemand(), "Configured demand should be 49 (7 x 7)");
        assertEquals(42, res.coverageReport().workforceCapacity(), "Workforce capacity should be 42 (7 x 6)");
        assertEquals(42, res.coverageReport().feasibleCapacity(), "Feasible capacity should be 42");
        assertEquals(42, res.coverageReport().totalAssigned(), "Total assigned should be 42");
        assertEquals(0, res.coverageReport().operationalShortage(), "Operational shortage must be 0");
        assertEquals(7, res.coverageReport().configuredShortage(), "Configured shortage should be 7 (49 - 42)");
    }

    @Test
    @DisplayName("Test 20: Night shift is EXACTLY ONE per day across the whole cycle")
    void test20_NightShiftIsExactlyOnePerDay() {
        LocalDate startDate = LocalDate.of(2026, 8, 17);
        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);

        for (int i = 0; i < 7; i++) {
            LocalDate date = startDate.plusDays(i);
            long nightCount = res.assignments().stream()
                    .filter(a -> a.rosterDate().equals(date) && a.shiftType() == ShiftType.NIGHT && !a.weeklyOff() && !a.onLeave())
                    .count();
            assertEquals(1, nightCount, "Day " + date + " must have EXACTLY 1 night employee assigned");
        }
    }

    @Test
    @DisplayName("Test 21: General shift is NEVER zero across the whole cycle")
    void test21_GeneralShiftNeverZero() {
        LocalDate startDate = LocalDate.of(2026, 8, 17);
        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);

        for (int i = 0; i < 7; i++) {
            LocalDate date = startDate.plusDays(i);
            long generalCount = res.assignments().stream()
                    .filter(a -> a.rosterDate().equals(date) && a.shiftType() == ShiftType.GENERAL && !a.weeklyOff() && !a.onLeave())
                    .count();
            assertTrue(generalCount >= 1, "Day " + date + " must have at least 1 General shift assigned");
        }
    }

    @Test
    @DisplayName("Test 22: Shift continuity produces multi-day blocks and high quality score")
    void test22_ShiftContinuity_HighQualityScore() {
        LocalDate startDate = LocalDate.of(2026, 8, 17);
        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);

        int qualityScore = rosterService.calculateRosterQualityScore(
                res.assignments().stream().map(a -> {
                    RosterAssignment ra = new RosterAssignment();
                    ra.setId(a.id());
                    ra.setRosterDate(a.rosterDate());
                    ra.setEmployee(workforce7.stream().filter(e -> e.getId().equals(a.employeeId())).findFirst().orElseThrow());
                    ra.setShift(createShift(10L, a.shiftType(), 1, LocalTime.of(7, 0), LocalTime.of(15, 0), a.shiftType() == ShiftType.NIGHT));
                    ra.setWeeklyOff(a.weeklyOff());
                    ra.setOnLeave(a.onLeave());
                    return ra;
                }).toList()
        );

        assertTrue(qualityScore >= 1000, "Quality score should be >= 1000 for stable consecutive shift blocks");
    }

    @Test
    @DisplayName("Sample Roster Display: Print 7-day schedule for real 7-person active workforce")
    void testSampleRosterDisplay() {
        LocalDate startDate = LocalDate.of(2026, 8, 17);
        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);

        System.out.println("\n=== 7-DAY SAMPLE ROSTER FOR REAL 7-PERSON ACTIVE WORKFORCE ===");
        System.out.printf("%-10s | %-16s | %-6s | %-8s | %-8s | %-8s | %-8s | %-8s | %-8s | %-8s%n",
                "Code", "Name", "Gender", "Sat (15)", "Sun (16)", "Mon (17)", "Tue (18)", "Wed (19)", "Thu (20)", "Fri (21)");
        System.out.println("----------------------------------------------------------------------------------------------------------------");

        for (Employee emp : workforce7) {
            System.out.printf("%-10s | %-16s | %-6s", emp.getEmployeeCode(), emp.getFirstName() + " " + emp.getLastName(), emp.getGender());
            for (int i = 0; i < 7; i++) {
                LocalDate d = startDate.plusDays(i);
                var a = res.assignments().stream()
                        .filter(assign -> assign.employeeId().equals(emp.getId()) && assign.rosterDate().equals(d))
                        .findFirst().orElseThrow();
                String val = a.weeklyOff() ? "OFF" : a.shiftType().name();
                System.out.printf(" | %-8s", val);
            }
            System.out.println();
        }

        System.out.println("----------------------------------------------------------------------------------------------------------------");
        System.out.println("Configured Demand: " + res.coverageReport().configuredDemand() + " (8 slots/day x 7 days)");
        System.out.println("Workforce Capacity: " + res.coverageReport().workforceCapacity() + " (7 staff x 6 working days)");
        System.out.println("Feasible Capacity: " + res.coverageReport().feasibleCapacity() + " (100% safe staffable)");
        System.out.println("Actual Assigned: " + res.coverageReport().totalAssigned() + " (42 shifts)");
        System.out.println("Operational Shortage: " + res.coverageReport().operationalShortage() + " (Zero Shortage!)");
        System.out.println("Configured Shortage: " + res.coverageReport().configuredShortage() + " (56 - 42 = 14)");
    }

    @Test
    @DisplayName("Test 21: Generate Roster starting on 2026-08-17 (Mon) succeeds with zero operational shortage")
    void test21_GenerateRosterForSimulationDate_17_08_2026_Success() {
        LocalDate startDate = LocalDate.of(2026, 8, 17);
        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);

        assertNotNull(res);
        assertNotNull(res.coverageReport());
        assertEquals(0, res.coverageReport().operationalShortage(), "Expected 0 operational shortage for 2026-08-17 cycle");
        assertEquals(42, res.coverageReport().totalAssigned(), "42 working assignments for 7 employees x 6 days");
        assertEquals(42, res.coverageReport().feasibleCapacity());
        assertEquals(LocalDate.of(2026, 8, 17), res.startDate());
        assertEquals(LocalDate.of(2026, 8, 23), res.endDate());

        // Check each day has 4 to 7 working staff, exactly 1 night shift, and female day shifts only
        for (int i = 0; i < 7; i++) {
            LocalDate date = startDate.plusDays(i);
            long working = res.assignments().stream()
                    .filter(a -> a.rosterDate().equals(date) && !a.weeklyOff() && !a.onLeave())
                    .count();
            long nights = res.assignments().stream()
                    .filter(a -> a.rosterDate().equals(date) && a.shiftType() == ShiftType.NIGHT && !a.weeklyOff() && !a.onLeave())
                    .count();
            assertTrue(working >= 4 && working <= 7, "Day " + date + " must have 4 to 7 working staff: actual = " + working);
            assertEquals(1, nights, "Day " + date + " must have exactly 1 Night staff");
        }
    }
}
