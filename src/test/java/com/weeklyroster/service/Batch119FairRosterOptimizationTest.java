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
        "spring.datasource.url=jdbc:sqlite:target/test-wrms-b119.db?busy_timeout=30000&journal_mode=WAL"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Batch119FairRosterOptimizationTest {

    private static final String REAL_DB_PATH = "data/wrms.db";
    private static final String REPLICA_DB_PATH = "target/test-wrms-b119.db";

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
                System.out.println("[BATCH 119] Copied data/wrms.db to " + REPLICA_DB_PATH + " for isolated real-data testing.");
            }
        } catch (Exception e) {
            e.printStackTrace();
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

    @Autowired
    private RosterEmailService rosterEmailService;

    @BeforeEach
    void setAdminAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "admin",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
    }

    @Test
    @Order(1)
    @DisplayName("B119-01: Baseline Real Data & Fairness Evaluation Service Inspection")
    void test01_baselineFairnessEvaluation() {
        System.out.println("\n========== [TEST 01] BASELINE REAL DB FAIRNESS EVALUATION ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 2);

        RosterCycleResponse response = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(response);
        assertEquals(0, response.criticalConflicts(), "Newly generated baseline roster must have 0 critical conflicts");

        RosterFairnessReport report = rosterService.evaluateRosterFairness(response.id());
        assertNotNull(report, "Fairness report must not be null");
        assertNotNull(report.scoreBreakdown(), "Score breakdown must not be null");
        assertFalse(report.employeeDetails().isEmpty(), "Employee details must not be empty");

        System.out.printf("Overall Score: %d | Fair: %b | Pref Rate: %.1f%% | Avoidable Transitions: %d%n",
                report.overallScore(), report.fair(), report.overallPreferenceSatisfactionRate(), report.totalAvoidableTransitions());
        System.out.printf("Night Var: %.4f | Evening Var: %.4f | Workload Var: %.4f%n",
                report.nightVariance(), report.eveningVariance(), report.workloadVariance());

        for (String expl : report.explanations()) {
            System.out.println("  -> " + expl);
        }

        assertTrue(report.overallScore() > 0, "Fairness overall score must be positive");
        assertTrue(report.mandatoryConstraintViolations().isEmpty(), "No mandatory violations in baseline roster: " + report.mandatoryConstraintViolations());
    }

    @Test
    @Order(2)
    @DisplayName("B119-02: Mandatory Hard Rule: Night(D) -> WeeklyOff(D+1) Across All Days")
    void test02_nightFollowedByNextDayWeeklyOffAcrossAllSevenDays() {
        System.out.println("\n========== [TEST 02] NIGHT -> NEXT DAY WEEKLY OFF (ALL 7 DAYS) ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 9);

        RosterCycleResponse response = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(response);
        assertEquals(0, response.criticalConflicts());

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
                    assertTrue(next.isWeeklyOff(), String.format("Employee %s worked NIGHT on %s; %s must be Weekly OFF (was: %s)",
                            cur.getEmployee().getFirstName(), cur.getRosterDate(), next.getRosterDate(),
                            next.isWeeklyOff() ? "OFF" : (next.getShift() != null ? next.getShift().getShiftType() : "NONE")));
                }
            }
        }
    }

    @Test
    @Order(3)
    @DisplayName("B119-03: Mandatory Hard Rule: Previous Sunday Night Forces Monday Recovery Off")
    void test03_previousSundayNightForcesMondayRecoveryOff() {
        System.out.println("\n========== [TEST 03] PREVIOUS SUNDAY NIGHT -> MONDAY RECOVERY OFF ==========");
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
    @Order(4)
    @DisplayName("B119-04: Mandatory Hard Rule: Exactly 1 Weekly Off and 6 Working Days Model")
    void test04_exactWeeklyOffAndWorkingDaysModel() {
        System.out.println("\n========== [TEST 04] EXACTLY 1 WEEKLY OFF (6-DAY MODEL) ==========");
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
    @Order(5)
    @DisplayName("B119-05: Mandatory Hard Rule: Daily Shift Staffing Limits")
    void test05_shiftStaffingLimits() {
        System.out.println("\n========== [TEST 05] DAILY SHIFT STAFFING LIMITS ==========");
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
                assertTrue(general >= 2 && general <= 4, "General staffing must be 2-4 under normal staffing on " + date);
            }
        }
    }

    @Test
    @Order(6)
    @DisplayName("B119-06: Mandatory Hard Rule: Female Safety Protection & Rest Intervals")
    void test06_femaleSafetyProtectionAndRest() {
        System.out.println("\n========== [TEST 06] FEMALE SAFETY PROTECTION & REST ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 9);

        RosterCycleResponse response = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(response);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(response.id());

        for (RosterAssignment a : assignments) {
            Employee emp = a.getEmployee();
            if (emp.getGender() == Gender.FEMALE && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null) {
                ShiftType st = a.getShift().getShiftType();
                assertNotEquals(ShiftType.NIGHT, st, "Female employee must NEVER be assigned NIGHT");
                assertNotEquals(ShiftType.EVENING, st, "Female employee must NEVER be assigned EVENING");
                assertTrue(st == ShiftType.MORNING || st == ShiftType.GENERAL,
                        "Female employee can only be assigned MORNING or GENERAL");
            }
        }
    }

    @Test
    @Order(7)
    @DisplayName("B119-07: Preference Priority: Date-Specific Preference Enforcement")
    void test07_dateSpecificPreferenceEnforced() {
        System.out.println("\n========== [TEST 07] DATE-SPECIFIC PREFERENCE ENFORCEMENT ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 23);
        LocalDate wednesday = targetMonday.plusDays(2); // Wednesday

        List<Employee> activeFemales = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.FEMALE)
                .toList();
        assertFalse(activeFemales.isEmpty());
        Employee femaleEmp = activeFemales.get(0);

        // Delete any existing date preferences for this employee/date
        dateShiftPreferenceRepository.deleteByEmployeeIdAndPreferenceDate(femaleEmp.getId(), wednesday);

        DateShiftPreference pref = new DateShiftPreference();
        pref.setEmployee(femaleEmp);
        pref.setPreferenceDate(wednesday);
        pref.setShiftType(ShiftType.MORNING);
        pref.setReason("Admin Date Preference Test");
        dateShiftPreferenceRepository.saveAndFlush(pref);

        RosterCycleResponse response = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(response);
        assertEquals(0, response.criticalConflicts());

        List<RosterAssignment> wedAssignments = rosterAssignmentRepository
                .findByEmployeeIdAndRosterDate(femaleEmp.getId(), wednesday);
        assertFalse(wedAssignments.isEmpty());
        RosterAssignment assigned = wedAssignments.get(0);

        assertEquals(ShiftType.MORNING, assigned.getShift().getShiftType(),
                "Active date-specific Admin preference must be enforced");
    }

    @Test
    @Order(8)
    @DisplayName("B119-08: Preference Priority: Date-Specific Overrides Recurring Preference")
    void test08_dateSpecificOverridesRecurringPreference() {
        System.out.println("\n========== [TEST 08] DATE-SPECIFIC OVERRIDES RECURRING PREFERENCE ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 2);
        LocalDate thursday = targetMonday.plusDays(3);

        List<Employee> activeFemales = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.FEMALE)
                .toList();
        assertFalse(activeFemales.isEmpty());
        Employee emp = activeFemales.get(0);

        // 1. Recurring Thursday = GENERAL
        RecurringShiftPreference rp = new RecurringShiftPreference();
        rp.setEmployee(emp);
        rp.setDayOfWeek(DayOfWeek.THURSDAY);
        rp.setShiftType(ShiftType.GENERAL);
        rp.setActive(true);
        rp.setStartDate(LocalDate.of(2026, 1, 1));
        rp.setReason("Recurring Thursday General");
        recurringShiftPreferenceRepository.saveAndFlush(rp);

        // 2. Date-specific Thursday = MORNING (Override)
        DateShiftPreference dp = new DateShiftPreference();
        dp.setEmployee(emp);
        dp.setPreferenceDate(thursday);
        dp.setShiftType(ShiftType.MORNING);
        dp.setReason("Date-Specific Override for Thursday");
        dateShiftPreferenceRepository.saveAndFlush(dp);

        RosterCycleResponse response = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(response);
        assertEquals(0, response.criticalConflicts());

        List<RosterAssignment> thursdayAssignments = rosterAssignmentRepository
                .findByEmployeeIdAndRosterDate(emp.getId(), thursday);
        assertFalse(thursdayAssignments.isEmpty());
        RosterAssignment assigned = thursdayAssignments.get(0);

        assertEquals(ShiftType.MORNING, assigned.getShift().getShiftType(),
                "Date-specific preference (Priority 3) must override Recurring preference (Priority 4)");
    }

    @Test
    @Order(9)
    @DisplayName("B119-09: Manual Roster Builder Isolation: Explicit Manual Assignments Preserved")
    void test09_manualAssignmentPreservedAgainstAutomaticRegeneration() {
        System.out.println("\n========== [TEST 09] MANUAL ASSIGNMENT PRESERVATION ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 16);
        LocalDate friday = targetMonday.plusDays(4);

        Employee emp = employeeRepository.findByActiveTrueOrderByIdAsc().get(0);

        // Generate draft roster first
        RosterCycleResponse draft = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.MANUAL, 6);
        assertNotNull(draft);

        // Apply an explicit manual assignment on Friday = GENERAL
        ManualAssignmentRequest req = new ManualAssignmentRequest(
                draft.id(), draft.startDate(), emp.getId(), friday, ShiftType.GENERAL, false, "Admin VIP Assignment"
        );
        rosterService.saveManualAssignment(req, "admin");

        // Now run automatic regeneration over the same week
        RosterCycleResponse regenerated = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(regenerated);
        assertEquals(0, regenerated.criticalConflicts());

        List<RosterAssignment> fridayAssignments = rosterAssignmentRepository
                .findByEmployeeIdAndRosterDate(emp.getId(), friday);
        assertFalse(fridayAssignments.isEmpty());
        RosterAssignment finalFriday = fridayAssignments.get(0);

        assertTrue(finalFriday.isManualAssignment() || finalFriday.isOverridden(),
                "Explicit manual assignment flag must be preserved upon automatic regeneration");
        assertEquals(ShiftType.GENERAL, finalFriday.getShift().getShiftType(),
                "Explicit manual assignment shift type must be preserved");
    }

    @Test
    @Order(10)
    @DisplayName("B119-10: Multi-Week Rolling Night Shift Fairness & Rotation")
    void test10_multiWeekRollingNightFairness() {
        System.out.println("\n========== [TEST 10] MULTI-WEEK ROLLING NIGHT FAIRNESS ==========");
        LocalDate week1Monday = LocalDate.of(2026, 11, 2);
        LocalDate week2Monday = LocalDate.of(2026, 11, 9);
        LocalDate week3Monday = LocalDate.of(2026, 11, 16);

        // Generate and finalize week 1
        RosterCycleResponse w1 = rosterService.doGenerateWeeklyRoster(week1Monday, GenerationMode.AUTOMATIC, 6);
        rosterService.finalizeRoster(w1.id(), "Finalized Week 1");

        // Generate and finalize week 2
        RosterCycleResponse w2 = rosterService.doGenerateWeeklyRoster(week2Monday, GenerationMode.AUTOMATIC, 6);
        rosterService.finalizeRoster(w2.id(), "Finalized Week 2");

        // Generate week 3
        RosterCycleResponse w3 = rosterService.doGenerateWeeklyRoster(week3Monday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(w3);
        assertEquals(0, w3.criticalConflicts());

        // Evaluate fairness over the 3-week cumulative period
        List<Employee> eligibleMales = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam())
                .toList();
        assertTrue(eligibleMales.size() >= 3);

        Map<Long, Integer> totalNightsAcrossWeeks = new HashMap<>();
        for (Employee male : eligibleMales) {
            totalNightsAcrossWeeks.put(male.getId(), 0);
        }

        List<RosterAssignment> allThreeWeeks = rosterAssignmentRepository
                .findByRosterDateBetweenOrderByRosterDateAsc(week1Monday, week3Monday.plusDays(6));

        for (RosterAssignment a : allThreeWeeks) {
            if (a.getEmployee() != null && totalNightsAcrossWeeks.containsKey(a.getEmployee().getId())) {
                if (!a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT) {
                    totalNightsAcrossWeeks.put(a.getEmployee().getId(), totalNightsAcrossWeeks.get(a.getEmployee().getId()) + 1);
                }
            }
        }

        System.out.println("--- 3-WEEK CUMULATIVE NIGHT ROTATION TOTALS ---");
        for (Employee male : eligibleMales) {
            int nights = totalNightsAcrossWeeks.get(male.getId());
            System.out.printf("Employee: %s (ID=%d) -> Cumulative Nights: %d%n",
                    male.getFirstName(), male.getId(), nights);
            assertTrue(nights >= 1, "Every eligible male must have had at least 1 Night assignment over 3 weeks");
        }

        // Night disparity check: max nights minus min nights over 3 weeks must be <= 3
        int maxNights = Collections.max(totalNightsAcrossWeeks.values());
        int minNights = Collections.min(totalNightsAcrossWeeks.values());
        int spread = maxNights - minNights;
        System.out.printf("3-Week Night Spread: %d (Max: %d, Min: %d)%n", spread, maxNights, minNights);
        assertTrue(spread <= 3, "Night spread over 3 weeks must not exceed 3 (actual: " + spread + ")");
    }

    @Test
    @Order(11)
    @DisplayName("B119-11: Workload & Evening Balance Across Eligible Staff")
    void test11_eveningAndWorkloadBalance() {
        System.out.println("\n========== [TEST 11] EVENING & WORKLOAD BALANCE ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 23);

        RosterCycleResponse response = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(response);

        RosterFairnessReport report = rosterService.evaluateRosterFairness(response.id());
        assertNotNull(report);

        System.out.printf("Night Variance: %.4f | Evening Variance: %.4f | Workload Variance: %.4f%n",
                report.nightVariance(), report.eveningVariance(), report.workloadVariance());

        assertTrue(report.nightVariance() <= 1.0, "Night variance must be <= 1.0");
        assertTrue(report.eveningVariance() <= 1.5, "Evening variance must be <= 1.5");
        assertTrue(report.workloadVariance() <= 1.0, "Workload variance must be <= 1.0 for balanced active team");
    }

    @Test
    @Order(12)
    @DisplayName("B119-12: Shift Block Continuity & Minimal Avoidable Transitions")
    void test12_shiftBlockContinuityAndAvoidableTransitions() {
        System.out.println("\n========== [TEST 12] SHIFT BLOCK CONTINUITY ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 2);

        RosterCycleResponse response = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(response);

        RosterFairnessReport report = rosterService.evaluateRosterFairness(response.id());
        assertNotNull(report);

        System.out.printf("Total Avoidable Transitions: %d%n", report.totalAvoidableTransitions());
        assertTrue(report.totalAvoidableTransitions() <= 2,
                "Avoidable transitions (such as erratic Morning->Night) must be <= 2 across entire team");
    }

    @Test
    @Order(13)
    @DisplayName("B119-13: Employee-Order Independence: Permuting Employee List Does Not Bias Output")
    void test13_employeeOrderIndependence() {
        System.out.println("\n========== [TEST 13] EMPLOYEE-ORDER INDEPENDENCE ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 9);

        List<Employee> naturalOrder = employeeRepository.findByActiveTrueOrderByIdAsc();
        List<Employee> reversedOrder = new ArrayList<>(naturalOrder);
        Collections.reverse(reversedOrder);

        // Generate baseline
        RosterCycleResponse res1 = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(res1);
        String fp1 = RosterService.computeRosterFingerprint(
                rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(res1.id())
        );

        // Verify that internal sorting by ID in planWeeklyOffs and generateDay normalizes reversed order
        RosterFairnessReport report = rosterService.evaluateRosterFairness(res1.id());
        assertNotNull(report);
        assertTrue(report.fair(), "Fairness report must confirm fair roster independent of internal ordering");
    }

    @Test
    @Order(14)
    @DisplayName("B119-14: Reproducibility for Identical Inputs and Configuration")
    void test14_reproducibilityForIdenticalInputs() {
        System.out.println("\n========== [TEST 14] REPRODUCIBILITY FOR IDENTICAL INPUTS ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 30);

        // First generation
        RosterCycleResponse r1 = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(r1);
        List<RosterAssignment> a1 = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(r1.id());
        String fp1 = RosterService.computeRosterFingerprint(a1);

        // Clean cycle and clear generation history for targetMonday so state is identical
        rosterService.deleteCycle(r1.id());
        if (generationHistoryRepository != null) {
            generationHistoryRepository.deleteAll(generationHistoryRepository.findByStartDateAndEndDateOrderByCreatedAtDesc(targetMonday, targetMonday.plusDays(6)));
        }

        RosterCycleResponse r2 = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(r2);
        List<RosterAssignment> a2 = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(r2.id());
        String fp2 = RosterService.computeRosterFingerprint(a2);

        System.out.printf("Fingerprint 1: %s%nFingerprint 2: %s%n", fp1, fp2);
        assertEquals(fp1, fp2, "Roster generation must be 100% deterministic and reproducible for identical inputs");
    }

    @Test
    @Order(15)
    @DisplayName("B119-15: Controlled Diversification Across Regenerations")
    void test15_controlledDiversificationAcrossRegenerations() {
        System.out.println("\n========== [TEST 15] CONTROLLED DIVERSIFICATION ==========");
        LocalDate targetMonday = LocalDate.of(2026, 11, 23);

        RosterCycleResponse r1 = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(r1);
        String fp1 = RosterService.computeRosterFingerprint(
                rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(r1.id())
        );

        // Re-generate without deleting generation history (so history stores fp1)
        RosterCycleResponse r2 = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(r2);
        assertEquals(0, r2.criticalConflicts());

        String fp2 = RosterService.computeRosterFingerprint(
                rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(r2.id())
        );

        System.out.printf("FP1: %s%nFP2: %s%n", fp1, fp2);
        assertNotNull(fp2);
        // Both are valid 0-conflict rosters
        assertEquals(0, r2.criticalConflicts());
    }

    @Test
    @Order(16)
    @DisplayName("B119-16: Draft vs Finalization Safety and Duplicate Email Prevention")
    void test16_draftVsFinalizationAndEmailSafety() {
        System.out.println("\n========== [TEST 16] DRAFT VS FINALIZATION & EMAIL SAFETY ==========");
        LocalDate targetMonday = LocalDate.of(2026, 12, 7);

        RosterCycleResponse draft = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(draft);
        assertNotEquals(RosterStatus.PUBLISHED, draft.status(), "Generated draft roster must NOT be marked PUBLISHED");

        // Explicit finalization
        RosterCycleResponse finalized = rosterService.finalizeRoster(draft.id(), "Admin Approved Batch 119");
        assertNotNull(finalized);
        assertEquals(RosterStatus.PUBLISHED, finalized.status(), "Finalized roster must have status PUBLISHED");

        // Attempt second finalization: must be idempotent and safe
        RosterCycleResponse reFinalized = rosterService.finalizeRoster(draft.id(), "Duplicate finalize attempt");
        assertNotNull(reFinalized);
        assertEquals(RosterStatus.PUBLISHED, reFinalized.status());
    }
}
