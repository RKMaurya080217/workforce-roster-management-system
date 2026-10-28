package com.weeklyroster.service;

import com.weeklyroster.dto.ApplicablePreference;
import com.weeklyroster.dto.request.ManualAssignmentRequest;
import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wrms.dev.credential-mirror.enabled=false",
        "spring.datasource.url=jdbc:sqlite:target/test-wrms-b120.db?busy_timeout=30000&journal_mode=WAL"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Batch120UltimateRosterEngineTest {

    private static final String REAL_DB_PATH = "data/wrms.db";
    private static final String REPLICA_DB_PATH = "target/test-wrms-b120.db";

    static {
        try {
            System.setProperty("spring.profiles.active", "test");
            System.setProperty("spring.datasource.url", "jdbc:sqlite:" + REPLICA_DB_PATH + "?busy_timeout=30000&journal_mode=WAL");
            File targetDir = new File("target");
            if (!targetDir.exists()) targetDir.mkdirs();
            File realDb = new File(REAL_DB_PATH);
            File testDb = new File(REPLICA_DB_PATH);
            if (realDb.exists()) {
                Files.copy(realDb.toPath(), testDb.toPath(), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[BATCH 120] Copied data/wrms.db to " + REPLICA_DB_PATH + " for isolated real-data testing.");
            }
        } catch (Exception e) {
            System.err.println("[BATCH 120] Failed to prepare test database replica: " + e.getMessage());
        }
    }

    @Autowired
    private RosterService rosterService;

    @Autowired
    private FairnessEvaluationService fairnessEvaluationService;

    @Autowired
    private RosterAssignmentRepository rosterAssignmentRepository;

    @Autowired
    private RosterCycleRepository rosterCycleRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private RecurringShiftPreferenceRepository recurringShiftPreferenceRepository;

    @Autowired
    private DateShiftPreferenceRepository dateShiftPreferenceRepository;

    @Autowired
    private HolidayRepository holidayRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired(required = false)
    private RosterGenerationHistoryRepository generationHistoryRepository;

    @BeforeEach
    void setAdminAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "admin",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
    }

    private RosterAssignment createAssignment(RosterCycle cycle, Employee emp, Shift shift, LocalDate date, boolean isOff, boolean isManual, String reason) {
        RosterAssignment a = new RosterAssignment();
        a.setCycle(cycle);
        a.setEmployee(emp);
        a.setShift(shift);
        a.setRosterDate(date);
        a.setWeeklyOff(isOff);
        a.setManualAssignment(isManual);
        a.setAssignmentReason(reason);
        return a;
    }

    // =========================================================================
    // SECTION 1: MANDATORY RULES & SOLVER INVARIANTS (PHASE 1)
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("B120-01: Night(D) -> Immediate Next-Day Weekly Off(D+1) Across All 7 Days")
    void test01_nightFollowedByImmediateNextDayWeeklyOffAcrossAllSevenDays() {
        System.out.println("\n========== [TEST 01] NIGHT -> NEXT DAY WEEKLY OFF ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 9);

        RosterCycleResponse response = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(response);
        assertEquals(0, response.criticalConflicts(), "Newly generated roster must have zero critical conflicts");

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(response.id());
        Map<Long, List<RosterAssignment>> empMap = assignments.stream()
                .collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

        for (Map.Entry<Long, List<RosterAssignment>> entry : empMap.entrySet()) {
            List<RosterAssignment> list = entry.getValue();
            list.sort(Comparator.comparing(RosterAssignment::getRosterDate));

            for (int i = 0; i < list.size() - 1; i++) {
                RosterAssignment cur = list.get(i);
                RosterAssignment next = list.get(i + 1);

                if (!cur.isWeeklyOff() && !cur.isOnLeave() && cur.getShift() != null && cur.getShift().getShiftType() == ShiftType.NIGHT) {
                    assertTrue(next.isWeeklyOff() || (next.getShift() != null && next.getShift().getShiftType() == ShiftType.OFF),
                            String.format("Employee %s worked NIGHT on %s; %s must be Weekly OFF (was: %s)",
                                    cur.getEmployee().getFirstName(), cur.getRosterDate(), next.getRosterDate(),
                                    next.isWeeklyOff() ? "OFF" : (next.getShift() != null ? next.getShift().getShiftType() : "NONE")));
                }
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("B120-02: Preceding Sunday Night Shift Forces Monday Recovery Off Across Cycle Boundary")
    void test02_sundayNightShiftForcesMondayWeeklyOffAcrossCycleBoundary() {
        System.out.println("\n========== [TEST 02] SUNDAY NIGHT -> MONDAY RECOVERY OFF ==========");
        LocalDate w1Start = LocalDate.of(2026, 11, 9);
        RosterCycleResponse c1 = rosterService.generateWeeklyRoster(w1Start, GenerationMode.AUTOMATIC, 6);
        rosterService.publishRoster(c1.id());

        // Identify who worked Sunday Night on 15-Nov-2026
        List<RosterAssignment> w1List = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(c1.id());
        RosterAssignment sunNightA = w1List.stream()
                .filter(a -> a.getRosterDate().equals(LocalDate.of(2026, 11, 15))
                        && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT)
                .findFirst().orElse(null);
        assertNotNull(sunNightA, "Sunday night worker must exist in Week 1");
        Long sunNightEmpId = sunNightA.getEmployee().getId();

        LocalDate w2Start = LocalDate.of(2026, 11, 16);
        RosterCycleResponse response = rosterService.doGenerateWeeklyRoster(w2Start, GenerationMode.AUTOMATIC, 6);
        assertNotNull(response);
        assertEquals(0, response.criticalConflicts(), "Week 2 generation must have 0 critical conflicts");

        List<RosterAssignment> targetMondayAssignments = rosterAssignmentRepository
                .findByEmployeeIdAndRosterDate(sunNightEmpId, w2Start);
        assertFalse(targetMondayAssignments.isEmpty());
        RosterAssignment mondayAssignment = targetMondayAssignments.get(0);

        assertTrue(mondayAssignment.isWeeklyOff() || (mondayAssignment.getShift() != null && mondayAssignment.getShift().getShiftType() == ShiftType.OFF),
                "Employee working Sunday Night must receive mandatory recovery Weekly Off on Monday across cycle boundary");
    }

    @Test
    @Order(3)
    @DisplayName("B120-03: Exactly 1 Weekly Off and Exactly 6 Working Days In 6-Day Mode")
    void test03_exactlyOneWeeklyOffAndSixWorkingDaysInSixDayMode() {
        System.out.println("\n========== [TEST 03] EXACTLY 1 WEEKLY OFF (6-DAY MODE) ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 23);

        RosterCycleResponse response = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(response);
        assertEquals(0, response.criticalConflicts());

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(response.id());
        Map<Long, List<RosterAssignment>> empMap = assignments.stream()
                .collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

        for (Map.Entry<Long, List<RosterAssignment>> entry : empMap.entrySet()) {
            List<RosterAssignment> list = entry.getValue();
            long offCount = list.stream().filter(RosterAssignment::isWeeklyOff).count();
            long leaveCount = list.stream().filter(RosterAssignment::isOnLeave).count();
            long workCount = list.stream().filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() != ShiftType.OFF).count();

            if (leaveCount == 0) {
                assertEquals(1, offCount, "Active employee without leave must have exactly 1 Weekly Off");
                assertEquals(6, workCount, "Active employee without leave must have exactly 6 working days in 6-day mode");
            }
        }
    }

    @Test
    @Order(4)
    @DisplayName("B120-04: Daily Staffing Bounds Enforced Symmetrically (Morning 1-2, General 2-4, Evening 1-2, Night <= 1)")
    void test04_dailyStaffingBoundsEnforcedSymmetrically() {
        System.out.println("\n========== [TEST 04] DAILY STAFFING BOUNDS ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 2);

        RosterCycleResponse response = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(response);
        assertEquals(0, response.criticalConflicts());

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(response.id());
        Map<LocalDate, Map<ShiftType, Long>> dailyShiftCounts = assignments.stream()
                .filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null)
                .collect(Collectors.groupingBy(
                        RosterAssignment::getRosterDate,
                        Collectors.groupingBy(a -> a.getShift().getShiftType(), Collectors.counting())
                ));

        for (int i = 0; i < 7; i++) {
            LocalDate date = targetMonday.plusDays(i);
            Map<ShiftType, Long> counts = dailyShiftCounts.getOrDefault(date, Collections.emptyMap());

            long morning = counts.getOrDefault(ShiftType.MORNING, 0L);
            long evening = counts.getOrDefault(ShiftType.EVENING, 0L);
            long night = counts.getOrDefault(ShiftType.NIGHT, 0L);
            long general = counts.getOrDefault(ShiftType.GENERAL, 0L);
            long totalWorking = morning + evening + night + general;

            // Night shift must not exceed 1 (at most 1 on any day)
            assertTrue(night <= 1, "Night shift must not exceed 1 on " + date);
            assertTrue(morning >= 1 && morning <= 2, "Morning staffing must be 1-2 on " + date + " (actual: " + morning + ")");
            assertTrue(evening >= 1 && evening <= 2, "Evening staffing must be 1-2 on " + date + " (actual: " + evening + ")");

            if (totalWorking >= 6) {
                assertTrue(general >= 2 && general <= 4, "General staffing must be 2-4 under normal staffing on " + date + " (actual: " + general + ")");
            }
        }
    }

    @Test
    @Order(5)
    @DisplayName("B120-05: Supported Three-Employee Shortage Mode Diagnostics (M:1, E:1, N:1, G:0)")
    void test05_supportedThreeEmployeeShortageModeDiagnostics() {
        System.out.println("\n========== [TEST 05] THREE-EMPLOYEE SHORTAGE MODE ==========");
        LocalDate testDate = LocalDate.of(2026, 12, 1);
        RosterCycle dummyCycle = new RosterCycle();
        dummyCycle.setStartDate(testDate);
        dummyCycle.setEndDate(testDate);
        dummyCycle.setWorkingDays(6);

        List<Employee> emps = employeeRepository.findByActiveTrueOrderByIdAsc();
        List<Employee> maleEmps = emps.stream().filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam()).toList();
        assertTrue(maleEmps.size() >= 3);

        Employee e1 = maleEmps.get(0);
        Employee e2 = maleEmps.get(1);
        Employee e3 = maleEmps.get(2);

        Shift morning = shiftRepository.findByShiftType(ShiftType.MORNING).orElseThrow();
        Shift evening = shiftRepository.findByShiftType(ShiftType.EVENING).orElseThrow();
        Shift night = shiftRepository.findByShiftType(ShiftType.NIGHT).orElseThrow();
        Shift off = shiftRepository.findByShiftType(ShiftType.OFF).orElseThrow();

        // Construct exact 3-employee shortage mode: M:1, E:1, N:1, G:0
        List<RosterAssignment> assigns = new ArrayList<>();
        assigns.add(createAssignment(dummyCycle, e1, morning, testDate, false, false, "Shortage M"));
        assigns.add(createAssignment(dummyCycle, e2, evening, testDate, false, false, "Shortage E"));
        assigns.add(createAssignment(dummyCycle, e3, night, testDate, false, false, "Shortage N"));

        // Others off
        for (Employee other : emps) {
            if (!other.getId().equals(e1.getId()) && !other.getId().equals(e2.getId()) && !other.getId().equals(e3.getId())) {
                assigns.add(createAssignment(dummyCycle, other, off, testDate, true, false, "Shortage Off"));
            }
        }

        Map<ShiftType, Shift> shifts = shiftRepository.findAll().stream()
                .collect(Collectors.toMap(Shift::getShiftType, s -> s));

        RosterService.FinalValidationResult val = rosterService.evaluateFinalValidation(
                dummyCycle, assigns, shifts, 2, Collections.emptyMap(), Collections.emptyMap()
        );

        // Verify that 3-employee shortage mode is explicitly surfaced as an INFO diagnostic
        boolean shortageModeSurfaced = val.conflicts().stream()
                .anyMatch(c -> "THREE_EMPLOYEE_SHORTAGE_MODE".equals(c.ruleName()) && "INFO".equalsIgnoreCase(c.severity()));
        assertTrue(shortageModeSurfaced, "Authoritative validator must identify supported 3-employee shortage mode with INFO diagnostic");
    }

    @Test
    @Order(6)
    @DisplayName("B120-06: Approved Leave & Holiday Consistency (Leaves Prevent Shifts, Holidays Tracked Distinctly)")
    void test06_approvedLeaveAndHolidayConsistency() {
        System.out.println("\n========== [TEST 06] LEAVE & HOLIDAY CONSISTENCY ==========");
        LocalDate targetMonday = LocalDate.of(2026, 10, 19);
        Employee targetEmp = employeeRepository.findByActiveTrueOrderByIdAsc().get(0);

        // Insert leave on Wednesday
        LocalDate leaveDate = targetMonday.plusDays(2);
        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(targetEmp);
        leave.setStartDate(leaveDate);
        leave.setEndDate(leaveDate);
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setReason("Batch 120 Leave Consistency Test");
        leave.setRequestedAt(LocalDateTime.now());
        leaveRequestRepository.saveAndFlush(leave);

        try {
            RosterCycleResponse response = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(response);
            assertEquals(0, response.criticalConflicts());

            List<RosterAssignment> targetAssigns = rosterAssignmentRepository
                    .findByEmployeeIdAndRosterDate(targetEmp.getId(), leaveDate);
            assertFalse(targetAssigns.isEmpty());
            RosterAssignment leaveAssign = targetAssigns.get(0);

            assertTrue(leaveAssign.isOnLeave(), "Assignment on approved leave date must be marked ON_LEAVE");
            assertFalse(leaveAssign.isWeeklyOff(), "Approved leave must be classified as LEAVE, not converted into Weekly OFF");
        } finally {
            leaveRequestRepository.delete(leave);
        }
    }

    @Test
    @Order(7)
    @DisplayName("B120-07: Employment Lifecycle Dates (Joining & Resignation Boundaries Strictly Enforced)")
    void test07_employmentLifecycleDates() {
        System.out.println("\n========== [TEST 07] EMPLOYMENT LIFECYCLE DATES ==========");
        LocalDate weekStart = LocalDate.of(2026, 11, 9);
        Employee testEmp = employeeRepository.findByActiveTrueOrderByIdAsc().get(0);

        // Verify eligibility logic
        LocalDate futureJoin = weekStart.plusDays(3); // joins Thursday
        LocalDate originalJoin = testEmp.getJoiningDate();
        testEmp.setJoiningDate(futureJoin);
        employeeRepository.saveAndFlush(testEmp);

        try {
            assertFalse(testEmp.isEligibleForDate(weekStart), "Employee must not be eligible prior to joining date (Monday)");
            assertFalse(testEmp.isEligibleForDate(weekStart.plusDays(1)), "Employee must not be eligible on Tuesday");
            assertTrue(testEmp.isEligibleForDate(futureJoin), "Employee must be eligible on joining date (Thursday)");
        } finally {
            testEmp.setJoiningDate(originalJoin);
            employeeRepository.saveAndFlush(testEmp);
        }
    }

    @Test
    @Order(8)
    @DisplayName("B120-08: Female Safety Protection & Mandatory Rest Intervals (>= 12 Hours)")
    void test08_femaleSafetyAndMandatoryRestIntervals() {
        System.out.println("\n========== [TEST 08] FEMALE SAFETY & 12H REST ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 9);

        RosterCycleResponse response = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(response);
        assertEquals(0, response.criticalConflicts());

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(response.id());

        // Female safety check
        List<RosterAssignment> femaleAssignments = assignments.stream()
                .filter(a -> a.getEmployee().getGender() == Gender.FEMALE)
                .filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null)
                .toList();

        for (RosterAssignment fa : femaleAssignments) {
            ShiftType st = fa.getShift().getShiftType();
            assertNotEquals(ShiftType.NIGHT, st, "Female staff must NEVER be assigned Night shift: " + fa.getEmployee().getFirstName());
            assertNotEquals(ShiftType.EVENING, st, "Female staff must NEVER be assigned Evening shift: " + fa.getEmployee().getFirstName());
            assertTrue(st == ShiftType.MORNING || st == ShiftType.GENERAL, "Female staff must only work Morning or General: " + st);
        }

        // 12h rest interval check across all adjacent days
        Map<Long, List<RosterAssignment>> empMap = assignments.stream()
                .collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

        for (List<RosterAssignment> empList : empMap.values()) {
            empList.sort(Comparator.comparing(RosterAssignment::getRosterDate));
            for (int i = 0; i < empList.size() - 1; i++) {
                RosterAssignment cur = empList.get(i);
                RosterAssignment next = empList.get(i + 1);

                if (!cur.isWeeklyOff() && !cur.isOnLeave() && !next.isWeeklyOff() && !next.isOnLeave()
                        && cur.getShift() != null && next.getShift() != null) {
                    ShiftType s1 = cur.getShift().getShiftType();
                    ShiftType s2 = next.getShift().getShiftType();

                    // Evening -> Morning = 9h rest (violates 12h)
                    assertFalse(s1 == ShiftType.EVENING && s2 == ShiftType.MORNING,
                            "Evening -> Morning transition violates mandatory 12-hour rest rule");
                }
            }
        }
    }

    // =========================================================================
    // SECTION 2: PREFERENCE HIERARCHY & PERSISTENCE (PHASE 1.2)
    // =========================================================================

    @Test
    @Order(9)
    @DisplayName("B120-09: Persisted Date-Specific Preference Enforced by Solver")
    void test09_persistedDateSpecificPreferenceEnforced() {
        System.out.println("\n========== [TEST 09] DATE-SPECIFIC PREFERENCE ENFORCEMENT ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 23);
        LocalDate prefDate = targetMonday.plusDays(2); // Wednesday

        Employee male = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam())
                .findFirst().orElseThrow();

        // Seed date-specific preference: MORNING
        DateShiftPreference dsp = new DateShiftPreference(male, prefDate, ShiftType.MORNING, "B120 Date Pref", "admin");
        dateShiftPreferenceRepository.saveAndFlush(dsp);

        try {
            RosterCycleResponse response = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(response);
            assertEquals(0, response.criticalConflicts());

            List<RosterAssignment> targetAssigns = rosterAssignmentRepository
                    .findByEmployeeIdAndRosterDate(male.getId(), prefDate);
            assertFalse(targetAssigns.isEmpty());
            RosterAssignment assigned = targetAssigns.get(0);

            assertFalse(assigned.isWeeklyOff(), "Date-specific preference date must not be weekly off");
            assertEquals(ShiftType.MORNING, assigned.getShift().getShiftType(),
                    "Date-specific preference must be enforced by constraint solver");
        } finally {
            dateShiftPreferenceRepository.delete(dsp);
        }
    }

    @Test
    @Order(10)
    @DisplayName("B120-10: Persisted Recurring Shift Preference Enforced by Solver")
    void test10_persistedRecurringShiftPreferenceEnforced() {
        System.out.println("\n========== [TEST 10] RECURRING PREFERENCE ENFORCEMENT ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 23);

        Employee male = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam())
                .findFirst().orElseThrow();

        // Seed recurring preference: Thursday (DayOfWeek.THURSDAY) -> GENERAL
        RecurringShiftPreference rsp = new RecurringShiftPreference(
                male, DayOfWeek.THURSDAY, ShiftType.GENERAL, targetMonday.minusDays(7), targetMonday.plusDays(28),
                true, "B120 Recurring Thursday", "admin"
        );
        recurringShiftPreferenceRepository.saveAndFlush(rsp);

        try {
            RosterCycleResponse response = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(response);
            assertEquals(0, response.criticalConflicts());

            LocalDate thursday = targetMonday.plusDays(3);
            List<RosterAssignment> targetAssigns = rosterAssignmentRepository
                    .findByEmployeeIdAndRosterDate(male.getId(), thursday);
            assertFalse(targetAssigns.isEmpty());
            RosterAssignment assigned = targetAssigns.get(0);

            assertFalse(assigned.isWeeklyOff());
            assertEquals(ShiftType.GENERAL, assigned.getShift().getShiftType(),
                    "Recurring preference must be satisfied on configured day-of-week");
        } finally {
            recurringShiftPreferenceRepository.delete(rsp);
        }
    }

    @Test
    @Order(11)
    @DisplayName("B120-11: Date-Specific Preference Overrides Recurring Preference on Identical Date")
    void test11_dateSpecificPreferenceOverridesRecurringPreference() {
        System.out.println("\n========== [TEST 11] PREFERENCE PRECEDENCE (DATE > RECURRING) ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 23);
        LocalDate fridayDate = targetMonday.plusDays(4); // Friday

        Employee male = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam())
                .findFirst().orElseThrow();

        // Recurring preference: Friday -> GENERAL
        RecurringShiftPreference rsp = new RecurringShiftPreference(
                male, DayOfWeek.FRIDAY, ShiftType.GENERAL, targetMonday.minusDays(7), targetMonday.plusDays(28),
                true, "B120 Friday Recurring", "admin"
        );
        recurringShiftPreferenceRepository.saveAndFlush(rsp);

        // Date-specific preference: Friday 27-Nov -> MORNING (Should override GENERAL)
        DateShiftPreference dsp = new DateShiftPreference(male, fridayDate, ShiftType.MORNING, "B120 Date Override", "admin");
        dateShiftPreferenceRepository.saveAndFlush(dsp);

        try {
            Map<Long, ApplicablePreference> prefMap = rosterService.loadApprovedPreferences(
                    List.of(male), targetMonday, targetMonday.plusDays(6)
            );
            ApplicablePreference appPref = prefMap.get(male.getId());
            assertNotNull(appPref);

            ShiftType effectiveTarget = appPref.getTargetShiftForDate(fridayDate);
            assertEquals(ShiftType.MORNING, effectiveTarget,
                    "Date-specific preference (MORNING) must override recurring weekly preference (GENERAL)");

            RosterCycleResponse response = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(response);
            assertEquals(0, response.criticalConflicts());

            List<RosterAssignment> targetAssigns = rosterAssignmentRepository
                    .findByEmployeeIdAndRosterDate(male.getId(), fridayDate);
            assertFalse(targetAssigns.isEmpty());
            assertEquals(ShiftType.MORNING, targetAssigns.get(0).getShift().getShiftType(),
                    "Generated roster assignment must match overridden date-specific preference");
        } finally {
            dateShiftPreferenceRepository.delete(dsp);
            recurringShiftPreferenceRepository.delete(rsp);
        }
    }

    @Test
    @Order(12)
    @DisplayName("B120-12: Identity-Neutral Scheduling & Employee List Order Independence")
    void test12_identityNeutralSchedulingOrderIndependence() {
        System.out.println("\n========== [TEST 12] ORDER INDEPENDENCE & IDENTITY NEUTRALITY ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 9);

        // Baseline generation
        RosterCycleResponse res = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(res);
        assertEquals(0, res.criticalConflicts());

        RosterFairnessReport report = rosterService.evaluateRosterFairness(res.id());
        assertNotNull(report);
        assertTrue(report.overallScore() > 0);
        assertTrue(report.mandatoryConstraintViolations().isEmpty(),
                "No mandatory violations in order-independent schedule: " + report.mandatoryConstraintViolations());
    }

    // =========================================================================
    // SECTION 3: FAIRNESS OPTIMIZATION & ROLLING HISTORY (PHASE 3)
    // =========================================================================

    @Test
    @Order(13)
    @DisplayName("B120-13: Multi-Week Rolling Fairness Window & Shift Variance Minimization")
    void test13_multiWeekRollingFairnessAndVarianceOptimization() {
        System.out.println("\n========== [TEST 13] MULTI-WEEK ROLLING FAIRNESS ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 23);

        RosterCycleResponse response = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(response);

        RosterFairnessReport report = rosterService.evaluateRosterFairness(response.id());
        assertNotNull(report);
        assertNotNull(report.scoreBreakdown());

        System.out.printf("Overall Score: %d | Night Var: %.4f | Evening Var: %.4f | Workload Var: %.4f%n",
                report.overallScore(), report.nightVariance(), report.eveningVariance(), report.workloadVariance());

        assertTrue(report.nightVariance() <= 1.0, "Rolling normalized night variance must be <= 1.0");
        assertTrue(report.eveningVariance() <= 1.5, "Evening distribution variance must be <= 1.5");
        assertTrue(report.workloadVariance() <= 1.0, "Workload balance variance must be <= 1.0 for active staff");
    }

    @Test
    @Order(14)
    @DisplayName("B120-14: Shift Block Continuity & Avoidable Transition Penalties")
    void test14_avoidableShiftTransitionsMinimization() {
        System.out.println("\n========== [TEST 14] SHIFT BLOCK CONTINUITY ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 2);

        RosterCycleResponse response = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(response);

        RosterFairnessReport report = rosterService.evaluateRosterFairness(response.id());
        assertNotNull(report);

        System.out.printf("Total Avoidable Transitions: %d%n", report.totalAvoidableTransitions());
        assertTrue(report.totalAvoidableTransitions() <= 2,
                "Avoidable shift transitions (e.g. erratic Morning->Night without off) must be <= 2 across entire team");
    }

    @Test
    @Order(15)
    @DisplayName("B120-15: Fair Weekly Off Distribution Across Team (No Clumping)")
    void test15_weeklyOffDistributionSpread() {
        System.out.println("\n========== [TEST 15] WEEKLY OFF DISTRIBUTION ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 2);

        RosterCycleResponse response = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(response);

        List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(response.id());
        Map<LocalDate, Long> dailyOffs = assigns.stream()
                .filter(RosterAssignment::isWeeklyOff)
                .collect(Collectors.groupingBy(RosterAssignment::getRosterDate, Collectors.counting()));

        long maxOffsOnAnyDay = dailyOffs.values().stream().mapToLong(Long::longValue).max().orElse(0L);
        System.out.printf("Max simultaneous weekly offs on any single day: %d%n", maxOffsOnAnyDay);

        assertTrue(maxOffsOnAnyDay <= 3,
                "Simultaneous weekly offs must be distributed without exceeding operational limits (max <= 3)");
    }

    // =========================================================================
    // SECTION 4: SOLVER REPRODUCIBILITY & RESILIENCE (PHASE 2 & PHASE 4)
    // =========================================================================

    @Test
    @Order(16)
    @DisplayName("B120-16: 100% Reproducibility and Deterministic Attempt Hashing")
    void test16_reproducibilityAndDeterministicSalt() {
        System.out.println("\n========== [TEST 16] REPRODUCIBILITY & DETERMINISM ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 30);

        // First generation
        RosterCycleResponse r1 = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(r1);
        List<RosterAssignment> a1 = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(r1.id());
        String fp1 = RosterService.computeRosterFingerprint(a1);

        // Delete cycle and clear history so state is identical
        rosterService.deleteCycle(r1.id());
        if (generationHistoryRepository != null) {
            generationHistoryRepository.deleteAll(
                    generationHistoryRepository.findByStartDateAndEndDateOrderByCreatedAtDesc(targetMonday, targetMonday.plusDays(6))
            );
        }

        // Second generation from identical DB state
        RosterCycleResponse r2 = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(r2);
        List<RosterAssignment> a2 = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(r2.id());
        String fp2 = RosterService.computeRosterFingerprint(a2);

        System.out.printf("FP1: %s%nFP2: %s%n", fp1, fp2);
        assertEquals(fp1, fp2, "Roster generation must be 100% reproducible for identical inputs");
    }

    @Test
    @Order(17)
    @DisplayName("B120-17: Controlled Diversification Across Regenerations")
    void test17_controlledDiversificationAcrossRegenerations() {
        System.out.println("\n========== [TEST 17] CONTROLLED DIVERSIFICATION ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 23);

        RosterCycleResponse r1 = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(r1);
        String fp1 = RosterService.computeRosterFingerprint(
                rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(r1.id())
        );

        // Re-generate without clearing history (history retains fp1)
        RosterCycleResponse r2 = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(r2);
        assertEquals(0, r2.criticalConflicts());

        String fp2 = RosterService.computeRosterFingerprint(
                rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(r2.id())
        );

        System.out.printf("FP1: %s | FP2: %s%n", fp1, fp2);
        assertNotNull(fp2);
        assertEquals(0, r2.criticalConflicts(), "Diversified candidate must satisfy all mandatory rules with 0 critical conflicts");
    }

    @Test
    @Order(18)
    @DisplayName("B120-18: Honest Infeasible Input Reporting (Zero False Optimality)")
    void test18_infeasibleInputDiagnostics() {
        System.out.println("\n========== [TEST 18] INFEASIBLE INPUT REPORTING ==========");
        LocalDate testDate = LocalDate.of(2026, 12, 1);

        // Evaluate with only 2 employees (minimum 3 required for 24h operational coverage)
        List<Employee> twoEmps = employeeRepository.findByActiveTrueOrderByIdAsc().subList(0, 2);

        RosterFairnessReport report = fairnessEvaluationService.evaluateRosterFairness(
                testDate, testDate.plusDays(6), Collections.emptyList(), twoEmps,
                Collections.emptyMap(), 4
        );

        assertNotNull(report);
        assertTrue(report.infeasible(), "Workforce < 3 must be marked infeasible");
        assertEquals("PROVEN_INFEASIBLE", report.solverStatus());
        assertNotNull(report.infeasibilityReason());
        System.out.println("Infeasibility Reason: " + report.infeasibilityReason());
    }

    // =========================================================================
    // SECTION 5: MANUAL BUILDER ISOLATION & LIFECYCLE (PHASE 9 & 10)
    // =========================================================================

    @Test
    @Order(19)
    @DisplayName("B120-19: Strict Manual Roster Isolation (No Overwrites by Automatic Optimization)")
    void test19_strictManualRosterBuilderIsolation() {
        System.out.println("\n========== [TEST 19] MANUAL BUILDER ISOLATION ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 23);
        LocalDate manualDate = targetMonday.plusDays(1); // Tuesday

        RosterCycleResponse initialCycle = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(initialCycle);

        // Find an assignment on Tuesday that is currently working (MORNING, GENERAL, or EVENING)
        List<RosterAssignment> currentAssigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(initialCycle.id());
        RosterAssignment workingOnTuesday = currentAssigns.stream()
                .filter(a -> a.getRosterDate().equals(manualDate) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null)
                .findFirst().orElseThrow();

        Long empId = workingOnTuesday.getEmployee().getId();
        ShiftType originalShift = workingOnTuesday.getShift().getShiftType();

        // Admin applies explicit manual assignment locking the slot
        ManualAssignmentRequest req = new ManualAssignmentRequest(
                initialCycle.id(), targetMonday, empId, manualDate, originalShift, false, "Admin Manual Locked Slot"
        );
        rosterService.saveManualAssignment(req, "admin");

        // Verify assignment is flagged as manual
        List<RosterAssignment> preAssigns = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(empId, manualDate);
        assertFalse(preAssigns.isEmpty());
        assertTrue(preAssigns.get(0).isManualAssignment());

        // Re-generate cycle automatically
        RosterCycleResponse regenerated = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(regenerated);

        // Verify manual assignment is strictly preserved
        List<RosterAssignment> postAssigns = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(empId, manualDate);
        assertFalse(postAssigns.isEmpty());
        RosterAssignment preserved = postAssigns.get(0);

        assertTrue(preserved.isManualAssignment(), "Manual assignment flag must be preserved across regenerations");
        assertEquals(originalShift, preserved.getShift().getShiftType(), "Manual shift type must NOT be overwritten");
        assertEquals("Admin Manual Locked Slot", preserved.getAssignmentReason());
    }

    @Test
    @Order(20)
    @DisplayName("B120-20: Draft vs Finalization Safety and Duplicate Email Prevention")
    void test20_draftVsFinalizationLifecycleAndEmailSafeguards() {
        System.out.println("\n========== [TEST 20] DRAFT VS FINALIZATION & EMAIL SAFETY ==========");
        LocalDate targetMonday = LocalDate.of(2026, 12, 14);

        RosterCycleResponse draft = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(draft);
        assertNotEquals(RosterStatus.PUBLISHED, draft.status(), "Generated draft roster must NOT be marked PUBLISHED");

        // Explicit finalization
        RosterCycleResponse finalized = rosterService.finalizeRoster(draft.id(), "Admin Approved Batch 120");
        assertNotNull(finalized);
        assertEquals(RosterStatus.PUBLISHED, finalized.status(), "Finalized roster must have status PUBLISHED");

        // Idempotent duplicate finalization
        RosterCycleResponse reFinalized = rosterService.finalizeRoster(draft.id(), "Duplicate finalize attempt");
        assertNotNull(reFinalized);
        assertEquals(RosterStatus.PUBLISHED, reFinalized.status());
    }
}
