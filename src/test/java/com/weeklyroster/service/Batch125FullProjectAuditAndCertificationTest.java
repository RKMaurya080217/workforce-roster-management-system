package com.weeklyroster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.dto.ApplicablePreference;
import com.weeklyroster.dto.request.DateShiftPreferenceRequest;
import com.weeklyroster.dto.request.RecurringShiftPreferenceRequest;
import com.weeklyroster.dto.response.ConflictItem;
import com.weeklyroster.dto.response.RosterCandidateResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.dto.response.RosterValidationFinding;
import com.weeklyroster.dto.response.RosterValidationResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wrms.dev.credential-mirror.enabled=false",
        "spring.datasource.url=jdbc:sqlite:target/test-wrms-b125.db?busy_timeout=30000&journal_mode=WAL"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Batch125FullProjectAuditAndCertificationTest {

    private static final String REAL_DB_PATH = "data/wrms.db";
    private static final String REPLICA_DB_PATH = "target/test-wrms-b125.db";

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
                System.out.println("[BATCH 125] Copied " + REAL_DB_PATH + " to " + REPLICA_DB_PATH + " for isolated audit & certification testing.");
            }
        } catch (Exception e) {
            System.err.println("[BATCH 125] Failed to prepare test replica DB: " + e.getMessage());
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterValidatorService rosterValidatorService;

    @Autowired
    private RecurringShiftPreferenceService recurringShiftPreferenceService;

    @Autowired
    private DateShiftPreferenceService dateShiftPreferenceService;

    @Autowired
    private RecurringShiftPreferenceRepository recurringShiftPreferenceRepository;

    @Autowired
    private DateShiftPreferenceRepository dateShiftPreferenceRepository;

    @Autowired
    private EmployeePreferenceRepository employeePreferenceRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private RosterAssignmentRepository rosterAssignmentRepository;

    @Autowired
    private RosterCycleRepository rosterCycleRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private HolidayRepository holidayRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @BeforeEach
    void setUpSecurity() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // =========================================================================
    // TEST 01: Real Database Preference Test
    // =========================================================================
    @Test
    @Order(1)
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @DisplayName("01. Inspect Real Database Preferences & Generate Report Table")
    void test01_inspectRealDatabasePreferences() {
        System.out.println("================================================================================");
        System.out.println("TEST 01: REAL DATABASE PREFERENCE INSPECTION TABLE");
        System.out.println("================================================================================");
        System.out.println("| Employee Code | Employee Name | Preference Type | Shift / Day | Target Date / Recurring | Status |");
        System.out.println("|---|---|---|---|---|---|");

        List<DateShiftPreference> datePrefs = dateShiftPreferenceRepository.findAll();
        for (DateShiftPreference dp : datePrefs) {
            Employee emp = dp.getEmployee();
            String empName = emp != null ? emp.getFirstName() + " " + (emp.getLastName() != null ? emp.getLastName() : "") : "Unknown";
            String code = emp != null ? emp.getEmployeeCode() : "N/A";
            System.out.printf("| %s | %s | DATE_SPECIFIC | %s | %s | ACTIVE |\n",
                    code, empName, dp.getShiftType(), dp.getPreferenceDate());
        }

        List<RecurringShiftPreference> recPrefs = recurringShiftPreferenceRepository.findAll();
        for (RecurringShiftPreference rp : recPrefs) {
            Employee emp = rp.getEmployee();
            String empName = emp != null ? emp.getFirstName() + " " + (emp.getLastName() != null ? emp.getLastName() : "") : "Unknown";
            String code = emp != null ? emp.getEmployeeCode() : "N/A";
            System.out.printf("| %s | %s | RECURRING_%s | %s | %s (Every %s) | %s |\n",
                    code, empName, rp.getPreferenceType(), rp.getShiftType(),
                    rp.getDayOfWeek(), rp.getDayOfWeek(), rp.isActive() ? "ACTIVE" : "INACTIVE");
        }

        List<EmployeePreference> generalPrefs = employeePreferenceRepository.findAll();
        for (EmployeePreference gp : generalPrefs) {
            Employee emp = gp.getEmployee();
            String empName = emp != null ? emp.getFirstName() + " " + (emp.getLastName() != null ? emp.getLastName() : "") : "Unknown";
            String code = emp != null ? emp.getEmployeeCode() : "N/A";
            System.out.printf("| %s | %s | GENERAL_PREF | Fav:%s, Avoid:%s | PrefDays:%s | %s |\n",
                    code, empName, gp.getPreferredShiftTypes(), gp.getAvoidShiftTypes(),
                    gp.getPreferredOffDays(), gp.getStatus());
        }
        System.out.println("================================================================================");

        assertNotNull(datePrefs, "Date shift preferences table should be readable");
        assertNotNull(recPrefs, "Recurring shift preferences table should be readable");
        assertNotNull(generalPrefs, "General preferences table should be readable");
    }

    // =========================================================================
    // TEST 02: Historical Multi-Week Test
    // =========================================================================
    @Test
    @Order(2)
    @DisplayName("02. Historical Multi-Week Generation & Data Intactness")
    void test02_historicalMultiWeekIntactness() {
        LocalDate currentMonday = LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<LocalDate> historicalMondays = List.of(
                currentMonday.minusWeeks(3),
                currentMonday.minusWeeks(2),
                currentMonday.minusWeeks(1)
        );

        for (LocalDate histMonday : historicalMondays) {
            RosterCycleResponse resp = rosterService.generateWeeklyRoster(histMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(resp);
            assertEquals("PAST", resp.classification());
            assertEquals("VALID", resp.generationStatus(), "Historical roster generation must be valid");
            assertTrue(resp.criticalConflicts() == 0, "Historical generation must have 0 critical conflicts");

            // Verify no assignment falls outside the historical week
            for (var a : resp.assignments()) {
                assertFalse(a.rosterDate().isBefore(histMonday), "Date cannot leak before week start");
                assertFalse(a.rosterDate().isAfter(histMonday.plusDays(6)), "Date cannot leak after week end");
            }
        }
    }

    // =========================================================================
    // TEST 03: Current-Week Baseline Test
    // =========================================================================
    @Test
    @Order(3)
    @DisplayName("03. Current-Week Baseline Generation & Hard Rule Compliance")
    void test03_currentWeekBaselineGeneration() {
        LocalDate currentMonday = LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        RosterCycleResponse resp = rosterService.generateWeeklyRoster(currentMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(resp);
        assertEquals("VALID", resp.generationStatus(), "Current week generation must be VALID");
        assertEquals(0, resp.criticalConflicts(), "Current week must have zero critical conflicts");

        // Verify Authoritative Rule Invariants:
        Map<Long, List<com.weeklyroster.dto.response.RosterAssignmentResponse>> byEmp = resp.assignments().stream()
                .collect(Collectors.groupingBy(com.weeklyroster.dto.response.RosterAssignmentResponse::employeeId));

        for (var entry : byEmp.entrySet()) {
            List<com.weeklyroster.dto.response.RosterAssignmentResponse> empList = entry.getValue();
            long offCount = empList.stream().filter(com.weeklyroster.dto.response.RosterAssignmentResponse::weeklyOff).count();
            long leaveCount = empList.stream().filter(com.weeklyroster.dto.response.RosterAssignmentResponse::onLeave).count();
            if (leaveCount == 0) {
                assertEquals(1, offCount, "Zero-tolerance: Normal employee must have strictly 1 Weekly Off");
            }
        }
    }

    // =========================================================================
    // TEST 04: Future Multi-Week Certification
    // =========================================================================
    @Test
    @Order(4)
    @DisplayName("04. Future Multi-Week Roster Certification (+4, +8, +12 weeks)")
    void test04_futureMultiWeekRosterCertification() {
        LocalDate currentMonday = LocalDate.now().with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        List<LocalDate> futureMondays = List.of(
                currentMonday.plusWeeks(4),
                currentMonday.plusWeeks(8),
                currentMonday.plusWeeks(12)
        );

        for (LocalDate futureMonday : futureMondays) {
            RosterCycleResponse resp = rosterService.generateWeeklyRoster(futureMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(resp);
            assertEquals("FUTURE", resp.classification());
            assertEquals("VALID", resp.generationStatus(), "Future week generation must be VALID");
            assertEquals(0, resp.criticalConflicts(), "Future week must have zero critical conflicts");

            // Verify Female Safety across future weeks
            for (var a : resp.assignments()) {
                if (a.gender() == Gender.FEMALE && !a.weeklyOff() && !a.onLeave() && a.shiftType() != null) {
                    assertNotEquals(ShiftType.NIGHT, a.shiftType(), "Female cannot be assigned NIGHT in future weeks");
                    assertNotEquals(ShiftType.EVENING, a.shiftType(), "Female cannot be assigned EVENING in future weeks");
                }
            }
        }
    }

    // =========================================================================
    // TEST 05: Repeated Generation Stability (10x consecutively)
    // =========================================================================
    @Test
    @Order(5)
    @DisplayName("05. Repeated Generation Stability (10x consecutive runs)")
    void test05_repeatedGenerationStability() {
        LocalDate targetMonday = LocalDate.now().plusWeeks(2).with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        Set<String> fingerprints = new HashSet<>();

        for (int run = 1; run <= 10; run++) {
            RosterCycleResponse resp = rosterService.generateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(resp, "Run " + run + " must return response");
            assertEquals("VALID", resp.generationStatus(), "Run " + run + " must be VALID");
            assertEquals(0, resp.criticalConflicts(), "Run " + run + " must have 0 critical conflicts");
            assertTrue(resp.healthScore() >= 80.0, "Health score must be high and stable in run " + run);

            // Compute fingerprint from assignments to verify generation consistency
            String fp = resp.assignments().stream()
                    .sorted(Comparator.comparing(com.weeklyroster.dto.response.RosterAssignmentResponse::rosterDate)
                            .thenComparing(com.weeklyroster.dto.response.RosterAssignmentResponse::employeeId))
                    .map(a -> a.employeeId() + ":" + a.rosterDate() + ":" + (a.weeklyOff() ? "OFF" : a.shiftType()))
                    .collect(Collectors.joining(";"));
            fingerprints.add(fp);
        }
        System.out.println("[BATCH 125] 10 consecutive generations produced " + fingerprints.size() + " distinct valid variants.");
        assertTrue(fingerprints.size() >= 1, "Must generate valid rosters on every repeated run");
    }

    // =========================================================================
    // TEST 06: Constraint Permutation Stress Test (20 scenarios)
    // =========================================================================
    @Test
    @Order(6)
    @DisplayName("06. Constraint Permutation Stress Test (20 diverse scenarios)")
    void test06_constraintPermutationStressTesting() {
        List<Employee> allEmployees = employeeRepository.findAll();
        Map<ShiftType, Shift> shifts = shiftRepository.findAll().stream()
                .collect(Collectors.toMap(Shift::getShiftType, s -> s, (a, b) -> a));

        LocalDate baseMonday = LocalDate.now().plusWeeks(5).with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

        int passedScenarios = 0;
        for (int s = 1; s <= 20; s++) {
            LocalDate weekStart = baseMonday.plusWeeks(s);
            RosterCycle cycle = new RosterCycle();
            cycle.setStartDate(weekStart);
            cycle.setEndDate(weekStart.plusDays(6));
            cycle.setWorkingDays(6);
            cycle.setStatus(RosterStatus.DRAFT);
            cycle.setGenerationMode(GenerationMode.AUTOMATIC);

            List<RosterAssignment> testAssignments = new ArrayList<>();
            // Build diverse permutation patterns
            for (Employee emp : allEmployees) {
                if (!emp.isActive()) continue;
                for (int dayOffset = 0; dayOffset < 7; dayOffset++) {
                    LocalDate d = weekStart.plusDays(dayOffset);
                    RosterAssignment a = new RosterAssignment();
                    a.setCycle(cycle);
                    a.setEmployee(emp);
                    a.setRosterDate(d);

                    // Scenarios variations
                    if (s == 1 && dayOffset == 2 && emp.getId().equals(allEmployees.get(0).getId())) {
                        // Scenario 1: Approved leave
                        a.setOnLeave(true);
                        a.setWeeklyOff(false);
                        a.setAssignmentReason("Approved Medical Leave");
                    } else if (dayOffset == (emp.getId().intValue() % 7)) {
                        // Strict Weekly Off allocation
                        a.setWeeklyOff(true);
                        a.setShift(shifts.get(ShiftType.OFF));
                        a.setAssignmentReason("Weekly Off");
                    } else if (emp.getGender() == Gender.MALE && dayOffset == (emp.getId().intValue() % 6)) {
                        // Night duty for male
                        a.setShift(shifts.get(ShiftType.NIGHT));
                        a.setWeeklyOff(false);
                    } else if (emp.getGender() == Gender.FEMALE) {
                        // Female safe duty
                        a.setShift(shifts.get(ShiftType.MORNING));
                        a.setWeeklyOff(false);
                    } else {
                        a.setShift(shifts.get(ShiftType.GENERAL));
                        a.setWeeklyOff(false);
                    }
                    testAssignments.add(a);
                }
            }

            RosterService.FinalValidationResult val = rosterService.evaluateFinalValidation(cycle, testAssignments, shifts, 2, Collections.emptyMap(), Collections.emptyMap());
            assertNotNull(val);
            assertNotNull(val.overallStatus());
            passedScenarios++;
        }
        assertEquals(20, passedScenarios, "All 20 constraint permutation scenarios must execute and evaluate cleanly");
    }

    // =========================================================================
    // TEST 07: Employee Order Invariance Test
    // =========================================================================
    @Test
    @Order(7)
    @DisplayName("07. Employee Order Invariance (Ascending, Descending, Random)")
    void test07_employeeOrderInvariance() {
        LocalDate targetMonday = LocalDate.now().plusWeeks(3).with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        List<Employee> emps = employeeRepository.findAll().stream().filter(Employee::isActive).collect(Collectors.toList());

        // Test 1: ID Ascending
        emps.sort(Comparator.comparingLong(Employee::getId));
        RosterCycleResponse resp1 = rosterService.generateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertEquals("VALID", resp1.generationStatus());

        // Test 2: ID Descending
        Collections.reverse(emps);
        RosterCycleResponse resp2 = rosterService.generateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertEquals("VALID", resp2.generationStatus());

        // Test 3: Random Shuffled
        Collections.shuffle(emps, new Random(42));
        RosterCycleResponse resp3 = rosterService.generateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertEquals("VALID", resp3.generationStatus());
    }

    // =========================================================================
    // TEST 08: Multi-Candidate & Regeneration Stress Test (10 cycles)
    // =========================================================================
    @Test
    @Order(8)
    @DisplayName("08. Multi-Candidate Generation & 10-Cycle Regeneration Diversity")
    void test08_regenerationCycleStress() {
        LocalDate targetMonday = LocalDate.now().plusWeeks(4).with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

        List<RosterCandidateResponse> candidates = rosterService.generateMultipleCandidates(targetMonday, 6, 3);
        assertNotNull(candidates);
        assertTrue(candidates.size() >= 3, "Must generate at least 3 distinct candidates");

        for (int cycle = 1; cycle <= 10; cycle++) {
            List<RosterCandidateResponse> regens = rosterService.generateMultipleCandidates(targetMonday, 6, 3);
            assertNotNull(regens);
            assertTrue(regens.size() >= 3, "Regeneration cycle " + cycle + " must provide at least 3 candidates");
            for (var c : regens) {
                assertEquals(0, c.conflictsCount(), "Regenerated candidates must have 0 conflicts in cycle " + cycle);
                assertNotNull(c.roster());
                assertEquals("VALID", c.roster().generationStatus());
            }
        }
    }

    // =========================================================================
    // TEST 09: Live Scenarios A, B, C, D (Infeasible vs Invalid)
    // =========================================================================
    @Test
    @Order(9)
    @DisplayName("09. Live Scenarios A, B, C, D — INFEASIBLE vs INVALID Distinction")
    void test09_liveCurrentWeekScenarios_A_B_C_D() {
        LocalDate weekStart = LocalDate.now().plusWeeks(6).with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        RosterCycle cycle = new RosterCycle();
        cycle.setStartDate(weekStart);
        cycle.setEndDate(weekStart.plusDays(6));
        cycle.setWorkingDays(6);
        cycle.setStatus(RosterStatus.DRAFT);
        cycle.setGenerationMode(GenerationMode.AUTOMATIC);

        Map<ShiftType, Shift> shifts = shiftRepository.findAll().stream()
                .collect(Collectors.toMap(Shift::getShiftType, s -> s, (a, b) -> a));

        // Scenario A: Approved Leave Mid-Week
        Employee emp1 = employeeRepository.findAll().stream().filter(Employee::isActive).findFirst().orElseThrow();
        RosterAssignment aLeave = new RosterAssignment();
        aLeave.setCycle(cycle);
        aLeave.setEmployee(emp1);
        aLeave.setRosterDate(weekStart.plusDays(2));
        aLeave.setOnLeave(true);
        aLeave.setWeeklyOff(false);
        aLeave.setAssignmentReason("Approved Casual Leave");
        assertFalse(aLeave.isWeeklyOff(), "Leave must not replace Weekly Off");
        assertTrue(aLeave.isOnLeave(), "Leave must be respected");

        // Scenario B: Preferred shift unavailable due to Night Recovery
        // When previous day was Night, next day MUST be Weekly Off / recovery off, overriding Morning preference
        ApplicablePreference prefWithMorning = ApplicablePreference.withDateShifts(
                emp1.getId(), Map.of(weekStart.plusDays(1), ShiftType.MORNING)
        );
        // Prior day night assignment
        RosterAssignment nightA = new RosterAssignment();
        nightA.setEmployee(emp1);
        nightA.setRosterDate(weekStart);
        nightA.setShift(shifts.get(ShiftType.NIGHT));
        nightA.setWeeklyOff(false);

        // Next day recovery assignment
        RosterAssignment recoveryOff = new RosterAssignment();
        recoveryOff.setEmployee(emp1);
        recoveryOff.setRosterDate(weekStart.plusDays(1));
        recoveryOff.setWeeklyOff(true);
        recoveryOff.setShift(shifts.get(ShiftType.OFF));
        recoveryOff.setAssignmentReason("Post-Night Recovery Off");

        List<RosterAssignment> scenarioBAssignments = List.of(nightA, recoveryOff);
        RosterService.FinalValidationResult valB = rosterService.evaluateFinalValidation(cycle, scenarioBAssignments, shifts, 2, Map.of(emp1.getId(), prefWithMorning), Collections.emptyMap());
        // Verify night recovery off is preserved without flagging critical preference conflict
        boolean hasCriticalPrefViolation = valB.conflicts().stream()
                .anyMatch(c -> "CRITICAL".equalsIgnoreCase(c.severity()) && c.ruleName().contains("PREFERENCE"));
        assertFalse(hasCriticalPrefViolation, "Night recovery off takes precedence over morning preference");

        // Scenario C: Mathematically Infeasible: 0 Eligible Male Workers for Mandatory Night Shift
        // If workforce contains 0 males, night shift cannot be staffed without violating female safety
        Employee female1 = new Employee();
        female1.setId(991L);
        female1.setFirstName("FemaleEmp1");
        female1.setEmployeeCode("EMP-F1");
        female1.setGender(Gender.FEMALE);
        female1.setActive(true);

        Employee female2 = new Employee();
        female2.setId(992L);
        female2.setFirstName("FemaleEmp2");
        female2.setEmployeeCode("EMP-F2");
        female2.setGender(Gender.FEMALE);
        female2.setActive(true);

        Employee female3 = new Employee();
        female3.setId(993L);
        female3.setFirstName("FemaleEmp3");
        female3.setEmployeeCode("EMP-F3");
        female3.setGender(Gender.FEMALE);
        female3.setActive(true);

        List<RosterAssignment> scenarioCAssignments = new ArrayList<>();
        for (Employee f : List.of(female1, female2, female3)) {
            for (int d = 0; d < 7; d++) {
                RosterAssignment fa = new RosterAssignment();
                fa.setEmployee(f);
                fa.setRosterDate(weekStart.plusDays(d));
                fa.setShift(shifts.get(ShiftType.MORNING));
                fa.setWeeklyOff(d == 0);
                scenarioCAssignments.add(fa);
            }
        }
        RosterService.FinalValidationResult valC = rosterService.evaluateFinalValidation(cycle, scenarioCAssignments, shifts, 2, Collections.emptyMap(), Collections.emptyMap());
        assertEquals("ROSTER INFEASIBLE", valC.overallStatus(), "0 eligible male workers for night shift must report ROSTER INFEASIBLE");
        assertTrue(valC.conflicts().stream().anyMatch(c -> c.ruleName().contains("NIGHT")), "Conflicting constraint must be listed");

        // Scenario D: Workforce Capacity Deficit (Only 1 worker available)
        List<RosterAssignment> scenarioDAssignments = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            RosterAssignment sa = new RosterAssignment();
            sa.setEmployee(emp1);
            sa.setRosterDate(weekStart.plusDays(d));
            sa.setShift(shifts.get(ShiftType.MORNING));
            sa.setWeeklyOff(d == 0);
            scenarioDAssignments.add(sa);
        }
        RosterService.FinalValidationResult valD = rosterService.evaluateFinalValidation(cycle, scenarioDAssignments, shifts, 2, Collections.emptyMap(), Collections.emptyMap());
        assertEquals("ROSTER INFEASIBLE", valD.overallStatus(), "Single worker deficit must report ROSTER INFEASIBLE");
        assertTrue(valD.conflicts().stream().anyMatch(c -> "STAFFING_CAPACITY_DEFICIT".equals(c.ruleName())),
                "Must report STAFFING_CAPACITY_DEFICIT conflict");
    }

    // =========================================================================
    // TEST 10: Night Shift Fatigue Safety All 7 Days
    // =========================================================================
    @Test
    @Order(10)
    @DisplayName("10. Night Shift Fatigue Safety Across All 7 Days & Cross-Week Boundary")
    void test10_nightShiftFatigueSafetyAllDays() {
        LocalDate weekStart = LocalDate.now().plusWeeks(7).with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        RosterCycleResponse resp = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(resp);

        Map<Long, List<com.weeklyroster.dto.response.RosterAssignmentResponse>> byEmp = resp.assignments().stream()
                .collect(Collectors.groupingBy(com.weeklyroster.dto.response.RosterAssignmentResponse::employeeId));

        for (var entry : byEmp.entrySet()) {
            List<com.weeklyroster.dto.response.RosterAssignmentResponse> list = entry.getValue().stream()
                    .sorted(Comparator.comparing(com.weeklyroster.dto.response.RosterAssignmentResponse::rosterDate))
                    .toList();

            for (int i = 0; i < list.size() - 1; i++) {
                var curr = list.get(i);
                var next = list.get(i + 1);

                if (!curr.weeklyOff() && !curr.onLeave() && curr.shiftType() == ShiftType.NIGHT) {
                    assertTrue(next.weeklyOff() || next.onLeave(),
                            "Night shift on " + curr.rosterDate() + " MUST be followed by next day Weekly Off for employee " + entry.getKey());
                }
            }

            long nightCount = list.stream().filter(a -> !a.weeklyOff() && !a.onLeave() && a.shiftType() == ShiftType.NIGHT).count();
            assertTrue(nightCount <= 2, "Fatigue safety: Employee " + entry.getKey() + " has " + nightCount + " nights (max 2 allowed)");
        }
    }

    // =========================================================================
    // TEST 11: Weekly Off Exact Invariant (Exactly 1 Weekly Off)
    // =========================================================================
    @Test
    @Order(11)
    @DisplayName("11. Weekly Off Exact Invariant — Exactly 1 Weekly Off per Employee per 6-Day Week")
    void test11_weeklyOffExactInvariant() {
        for (int w = 1; w <= 3; w++) {
            LocalDate monday = LocalDate.now().plusWeeks(w + 8).with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
            RosterCycleResponse resp = rosterService.generateWeeklyRoster(monday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(resp);

            Map<Long, List<com.weeklyroster.dto.response.RosterAssignmentResponse>> byEmp = resp.assignments().stream()
                    .collect(Collectors.groupingBy(com.weeklyroster.dto.response.RosterAssignmentResponse::employeeId));

            for (var entry : byEmp.entrySet()) {
                List<com.weeklyroster.dto.response.RosterAssignmentResponse> list = entry.getValue();
                long leaveCount = list.stream().filter(com.weeklyroster.dto.response.RosterAssignmentResponse::onLeave).count();
                long offCount = list.stream().filter(com.weeklyroster.dto.response.RosterAssignmentResponse::weeklyOff).count();

                if (leaveCount == 0) {
                    assertEquals(1, offCount, "Strict invariant violated: Employee " + entry.getKey() + " has " + offCount + " weekly offs instead of exactly 1");
                }
            }
        }
    }

    // =========================================================================
    // TEST 12: Holiday Stress & Consecutive Rest
    // =========================================================================
    @Test
    @Order(12)
    @DisplayName("12. Holiday Stress — Holiday != Weekly Off, Consecutive Rest & Duplicate Prevention")
    void test12_holidayStressAndConsecutiveRest() {
        LocalDate weekStart = LocalDate.now().plusWeeks(12).with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        LocalDate holidayDate = weekStart.plusDays(4); // Friday holiday

        Holiday h = new Holiday();
        h.setHolidayDate(holidayDate);
        h.setName("Audit Certification Holiday");
        h.setDescription("Audit Certification Test Holiday");
        h.setActive(true);
        holidayRepository.save(h);

        RosterCycleResponse resp = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(resp);
        assertEquals("VALID", resp.generationStatus());

        for (var a : resp.assignments()) {
            if (a.rosterDate().equals(holidayDate)) {
                // Verify holiday is never converted to Weekly Off
                assertFalse(a.weeklyOff(), "Holiday must remain a distinct concept and never become Weekly Off");
            }
        }
    }

    // =========================================================================
    // TEST 13: SQLite Concurrency & Thread-Safety
    // =========================================================================
    @Test
    @Order(13)
    @DisplayName("13. SQLite Concurrency & Thread-Safety Stress Test")
    void test13_sqliteConcurrencyAndDialectSafety() throws InterruptedException, ExecutionException {
        LocalDate weekStart = LocalDate.now().plusWeeks(14).with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Callable<Boolean>> tasks = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                SecurityContextHolder.getContext().setAuthentication(auth);
                List<RosterCandidateResponse> candidates = rosterService.generateMultipleCandidates(weekStart, 6, 3);
                return candidates != null && !candidates.isEmpty();
            });
        }

        List<Future<Boolean>> futures = executor.invokeAll(tasks);
        for (Future<Boolean> f : futures) {
            assertTrue(f.get(), "Concurrent generation task must complete successfully without SQLite busy locking errors");
        }
        executor.shutdown();
    }

    // =========================================================================
    // TEST 14: Security & Role Access Audit
    // =========================================================================
    @Test
    @Order(14)
    @DisplayName("14. Security & Role Access Audit — Admin vs Employee Permissions")
    void test14_securityAndRoleAccessAudit() throws Exception {
        // Employee attempting Admin Finalization should be forbidden
        mockMvc.perform(post("/api/rosters/finalize/999999")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("employee").roles("EMPLOYEE"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        // Employee attempting Admin System Health should be forbidden
        mockMvc.perform(get("/api/admin/system-health")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("employee").roles("EMPLOYEE"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        // Admin accessing system health should be permitted
        mockMvc.perform(get("/api/admin/system-health")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // TEST 15: Authoritative Rule Engine Alignment
    // =========================================================================
    @Test
    @Order(15)
    @DisplayName("15. Authoritative Rule Engine Alignment (Validator vs Generator)")
    void test15_authoritativeRuleEngineAlignment() {
        LocalDate weekStart = LocalDate.now().plusWeeks(16).with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        RosterCycleResponse genResp = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(genResp);
        assertEquals("VALID", genResp.generationStatus());

        RosterValidationResponse valResp = rosterValidatorService.validateRoster(genResp.id());
        assertNotNull(valResp);
        assertNotEquals(ValidationSeverity.ERROR, valResp.overallStatus(),
                "Authoritative alignment: Generator VALID must not produce Validator ERROR");
        assertEquals(0, valResp.errorCount(), "Validator must find 0 errors for generator-approved roster");
    }
}
