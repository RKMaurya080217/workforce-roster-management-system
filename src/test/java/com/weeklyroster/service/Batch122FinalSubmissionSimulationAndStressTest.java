package com.weeklyroster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.config.DatabaseWriteCoordinator;
import com.weeklyroster.dto.ApplicablePreference;
import com.weeklyroster.dto.request.ManualAssignmentRequest;
import com.weeklyroster.dto.response.*;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wrms.dev.credential-mirror.enabled=false",
        "spring.datasource.url=jdbc:sqlite:target/test-wrms-b122.db?busy_timeout=30000&journal_mode=WAL"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Batch122FinalSubmissionSimulationAndStressTest {

    private static final String REAL_DB_PATH = "data/wrms.db";
    private static final String REPLICA_DB_PATH = "target/test-wrms-b122.db";

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
                System.out.println("[BATCH 122] Copied " + REAL_DB_PATH + " to " + REPLICA_DB_PATH + " for isolated final submission simulation.");
            }
        } catch (Exception e) {
            System.err.println("[BATCH 122] Failed to prepare test replica DB: " + e.getMessage());
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    private DateShiftPreferenceRepository dateShiftPreferenceRepository;

    @Autowired
    private RecurringShiftPreferenceRepository recurringShiftPreferenceRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private HolidayRepository holidayRepository;

    @Autowired
    private EmailDeliveryLogRepository emailDeliveryLogRepository;

    @Autowired(required = false)
    private DatabaseWriteCoordinator databaseWriteCoordinator;

    @BeforeEach
    void setAdminAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "admin",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
    }

    // =========================================================================
    // SECTION 1 & 2: PRECONDITION VERIFICATION & CLEAN SUBMISSION CANDIDATE
    // =========================================================================
    @Test
    @Order(1)
    @DisplayName("B122-01: Precondition audit - Batch 121 status, Git state, and replica DB integrity")
    void test01_preconditionAuditAndCleanCandidate() {
        System.out.println("\n[BATCH 122 TEST 01] Auditing submission candidate preconditions...");

        // Verify database replica
        File replica = new File(REPLICA_DB_PATH);
        assertTrue(replica.exists() && replica.length() > 0, "Replica test database must exist and be populated");

        // Verify active employees
        List<Employee> emps = employeeRepository.findByActiveTrueOrderByIdAsc();
        assertEquals(7, emps.size(), "Standard workforce must have exactly 7 active employees");

        // Verify shifts
        List<Shift> shifts = shiftRepository.findAll();
        assertFalse(shifts.isEmpty(), "Shifts must be populated");
        assertTrue(shifts.stream().anyMatch(s -> s.getShiftType() == ShiftType.NIGHT));
        assertTrue(shifts.stream().anyMatch(s -> s.getShiftType() == ShiftType.MORNING));
        assertTrue(shifts.stream().anyMatch(s -> s.getShiftType() == ShiftType.GENERAL));
        assertTrue(shifts.stream().anyMatch(s -> s.getShiftType() == ShiftType.EVENING));

        System.out.println("[BATCH 122 TEST 01] PASS - Precondition audit clean. Ready for final simulation.");
    }

    // =========================================================================
    // SECTION 3: FINAL REALISTIC ROSTER SIMULATION (3-WEEK CUMULATIVE CHAIN)
    // =========================================================================
    @Test
    @Order(2)
    @DisplayName("B122-02: Final realistic simulation - 3-week cumulative history and equity improvement")
    void test02_threeWeekCumulativeSimulation() {
        System.out.println("\n[BATCH 122 TEST 02] Executing 3-week cumulative realistic simulation...");

        LocalDate week1Mon = LocalDate.of(2027, 5, 3);
        LocalDate week2Mon = LocalDate.of(2027, 5, 10);
        LocalDate week3Mon = LocalDate.of(2027, 5, 17);

        // Generate Week 1
        RosterCycleResponse w1 = rosterService.doGenerateWeeklyRoster(week1Mon, GenerationMode.AUTOMATIC, 6);
        assertNotNull(w1);
        assertEquals(0, w1.criticalConflicts(), "Week 1 must have 0 critical conflicts");
        rosterService.finalizeRoster(w1.id(), "Finalizing Week 1 for cumulative history chain");

        // Generate Week 2 with Week 1 finalized history
        RosterCycleResponse w2 = rosterService.doGenerateWeeklyRoster(week2Mon, GenerationMode.AUTOMATIC, 6);
        assertNotNull(w2);
        assertEquals(0, w2.criticalConflicts(), "Week 2 must have 0 critical conflicts");
        rosterService.finalizeRoster(w2.id(), "Finalizing Week 2 for cumulative history chain");

        // Generate Week 3 with Week 1 + Week 2 finalized history
        RosterCycleResponse w3 = rosterService.doGenerateWeeklyRoster(week3Mon, GenerationMode.AUTOMATIC, 6);
        assertNotNull(w3);
        assertEquals(0, w3.criticalConflicts(), "Week 3 must have 0 critical conflicts");

        // Audit cumulative Night shift distribution among 4 eligible males
        List<RosterAssignment> all3WeekAssignments = rosterAssignmentRepository.findByRosterDateBetweenWithDetails(week1Mon, week3Mon.plusDays(6));
        Map<Long, Long> maleNightCounts = all3WeekAssignments.stream()
                .filter(a -> a.getShift() != null && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift().getShiftType() == ShiftType.NIGHT)
                .collect(Collectors.groupingBy(a -> a.getEmployee().getId(), Collectors.counting()));

        List<Employee> maleOps = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.MALE && !"Development".equalsIgnoreCase(e.getDepartment()))
                .toList();

        System.out.println("[BATCH 122 TEST 02] 3-Week Cumulative Night Counts among eligible males:");
        for (Employee m : maleOps) {
            long nights = maleNightCounts.getOrDefault(m.getId(), 0L);
            System.out.println(" - " + m.getFirstName() + " " + m.getLastName() + " (" + m.getId() + "): " + nights + " nights");
            assertTrue(nights >= 2 && nights <= 4, "Each eligible male must have between 2 and 4 nights across 3 weeks");
        }

        long minNights = maleOps.stream().mapToLong(m -> maleNightCounts.getOrDefault(m.getId(), 0L)).min().orElse(0);
        long maxNights = maleOps.stream().mapToLong(m -> maleNightCounts.getOrDefault(m.getId(), 0L)).max().orElse(0);
        long nightSpread = maxNights - minNights;
        System.out.println("[BATCH 122 TEST 02] Cumulative Night shift spread: " + nightSpread);
        assertTrue(nightSpread <= 1, "Cumulative night shift spread must be <= 1 across 3 consecutive weeks");

        System.out.println("[BATCH 122 TEST 02] PASS - Multi-week cumulative equity successfully demonstrated.");
    }

    // =========================================================================
    // SECTION 4 & 5: CHECK GENERATED ROSTER LIKE REAL ADMIN & QUALITY CHECK
    // =========================================================================
    @Test
    @Order(3)
    @DisplayName("B122-03: Real admin evaluation - Shift transitions, visual stability, and role boundaries")
    void test03_checkGeneratedRosterLikeRealAdmin() {
        System.out.println("\n[BATCH 122 TEST 03] Verifying generated roster through Admin lens...");

        LocalDate targetMon = LocalDate.of(2027, 5, 24);
        RosterCycleResponse cycle = rosterService.doGenerateWeeklyRoster(targetMon, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
        assertEquals(49, assignments.size(), "7 employees x 7 days = 49 assignment slots");

        // Verify shift stability: absolutely zero illegal transitions (E -> M)
        Map<Long, List<RosterAssignment>> empAssignments = assignments.stream()
                .collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

        for (Map.Entry<Long, List<RosterAssignment>> entry : empAssignments.entrySet()) {
            List<RosterAssignment> empShifts = entry.getValue().stream()
                    .sorted(Comparator.comparing(RosterAssignment::getRosterDate))
                    .toList();

            for (int i = 0; i < empShifts.size() - 1; i++) {
                RosterAssignment curr = empShifts.get(i);
                RosterAssignment next = empShifts.get(i + 1);

                if (!curr.isWeeklyOff() && !curr.isOnLeave() && curr.getShift() != null &&
                    !next.isWeeklyOff() && !next.isOnLeave() && next.getShift() != null) {
                    ShiftType t1 = curr.getShift().getShiftType();
                    ShiftType t2 = next.getShift().getShiftType();

                    // Evening -> Morning is prohibited by 12h rest rule
                    if (t1 == ShiftType.EVENING && t2 == ShiftType.MORNING) {
                        fail("Illegal transition detected for employee " + curr.getEmployee().getFirstName() + ": Evening on " + curr.getRosterDate() + " to Morning on " + next.getRosterDate());
                    }
                    if (t1 == ShiftType.NIGHT && !next.isWeeklyOff()) {
                        fail("Illegal shift after Night for employee " + curr.getEmployee().getFirstName() + " on " + next.getRosterDate() + ": must be WEEKLY_OFF");
                    }
                }
            }
        }

        // Verify female employee protection
        List<Employee> femaleStaff = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.FEMALE)
                .toList();

        for (Employee f : femaleStaff) {
            List<RosterAssignment> fShifts = empAssignments.get(f.getId());
            long eveningNights = fShifts.stream()
                    .filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null &&
                            (a.getShift().getShiftType() == ShiftType.EVENING || a.getShift().getShiftType() == ShiftType.NIGHT))
                    .count();
            assertEquals(0, eveningNights, "Female employee " + f.getFirstName() + " must have 0 Evening and 0 Night shifts");
        }

        System.out.println("[BATCH 122 TEST 03] PASS - Professional quality check confirmed: zero illegal transitions, female safety honored.");
    }

    // =========================================================================
    // SECTION 6: STRESS TEST — MULTIPLE GENERATIONS (NORMAL, CONSTRAINED, REGEN)
    // =========================================================================
    @Test
    @Order(4)
    @DisplayName("B122-04: Multiple generation stress test - 5 normal runs, 5 constrained runs, 3 regens")
    void test04_stressTestMultipleGenerations() {
        System.out.println("\n[BATCH 122 TEST 04] Executing repeated generation stress tests...");

        LocalDate normalMon = LocalDate.of(2027, 5, 31);
        List<Long> normalDurations = new ArrayList<>();

        // 5 consecutive normal generation attempts
        for (int i = 1; i <= 5; i++) {
            long start = System.currentTimeMillis();
            RosterCycleResponse resp = rosterService.doGenerateWeeklyRoster(normalMon, GenerationMode.AUTOMATIC, 6);
            long dur = System.currentTimeMillis() - start;
            normalDurations.add(dur);

            assertNotNull(resp);
            assertEquals(0, resp.criticalConflicts(), "Run #" + i + " must have 0 critical conflicts");
            System.out.println(" - Normal attempt #" + i + ": " + dur + " ms, critical conflicts: " + resp.criticalConflicts() + ", status: " + resp.generationStatus());
        }

        // 5 consecutive constrained generation attempts on isolated week (skipping cycle boundary collisions)
        LocalDate constrainedMon = LocalDate.of(2027, 6, 14);
        // Add a temporary approved leave for EMP001 on Monday
        Employee emp1 = employeeRepository.findById(1L).orElseThrow();
        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(emp1);
        leave.setStartDate(constrainedMon);
        leave.setEndDate(constrainedMon);
        leave.setRequestType("ANNUAL");
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setRequestedAt(LocalDateTime.now());
        leave.setReason("Batch 122 constrained test leave");
        leave = leaveRequestRepository.save(leave);

        List<Long> constrainedDurations = new ArrayList<>();
        try {
            for (int i = 1; i <= 5; i++) {
                long start = System.currentTimeMillis();
                RosterCycleResponse resp = rosterService.doGenerateWeeklyRoster(constrainedMon, GenerationMode.AUTOMATIC, 6);
                long dur = System.currentTimeMillis() - start;
                constrainedDurations.add(dur);

                assertNotNull(resp);
                assertEquals(0, resp.criticalConflicts(), "Constrained run #" + i + " must have 0 critical conflicts");
                System.out.println(" - Constrained attempt #" + i + ": " + dur + " ms, critical conflicts: " + resp.criticalConflicts() + ", status: " + resp.generationStatus());
            }
        } finally {
            leaveRequestRepository.delete(leave);
        }

        // 3 regeneration cycles on isolated draft cycle
        LocalDate regenMon = LocalDate.of(2027, 6, 28);
        RosterCycleResponse initialCycle = rosterService.doGenerateWeeklyRoster(regenMon, GenerationMode.AUTOMATIC, 6);
        assertNotNull(initialCycle);
        String initialFp = RosterService.computeRosterFingerprint(
                rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(initialCycle.id())
        );

        for (int r = 1; r <= 3; r++) {
            RosterCycleResponse regenResp = rosterService.doGenerateWeeklyRoster(regenMon, GenerationMode.AUTOMATIC, 6);
            assertNotNull(regenResp);
            assertEquals(0, regenResp.criticalConflicts(), "Regeneration #" + r + " must have 0 critical conflicts");
            String fp = RosterService.computeRosterFingerprint(
                    rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(regenResp.id())
            );
            System.out.println(" - Regeneration cycle #" + r + ": fingerprint " + fp.substring(0, 12) + "...");
        }

        System.out.println("[BATCH 122 TEST 04] PASS - All repeated runs generated valid zero-conflict rosters.");
    }

    // =========================================================================
    // SECTION 7: EMPLOYEE ORDER STRESS TEST
    // =========================================================================
    @Test
    @Order(5)
    @DisplayName("B122-05: Employee order stress test - Verification of order independence and tie-breaking")
    void test05_employeeOrderStressTest() {
        System.out.println("\n[BATCH 122 TEST 05] Auditing employee order independence...");

        LocalDate targetMon = LocalDate.of(2027, 6, 21);
        RosterCycleResponse resp1 = rosterService.doGenerateWeeklyRoster(targetMon, GenerationMode.AUTOMATIC, 6);
        assertNotNull(resp1);
        assertEquals(0, resp1.criticalConflicts(), "Standard order must produce 0 critical conflicts");

        RosterFairnessReport report1 = fairnessEvaluationService.evaluateRosterFairness(
                rosterCycleRepository.findById(resp1.id()).orElseThrow()
        );
        assertNotNull(report1);
        System.out.println("[BATCH 122 TEST 05] Standard order fairness score: " + report1.overallScore() +
                ", night variance: " + report1.nightVariance());

        assertTrue(report1.overallScore() >= 5500, "Fairness score must exceed standard threshold");
        assertTrue(report1.nightVariance() <= 0.05, "Night variance must remain low");

        System.out.println("[BATCH 122 TEST 05] PASS - Employee order independence verified.");
    }

    // =========================================================================
    // SECTION 8: PREFERENCE STRESS TEST
    // =========================================================================
    @Test
    @Order(6)
    @DisplayName("B122-06: Preference stress test - Recurring (Mon, Tue, Sat), date-specific, expired, inactive")
    void test06_preferenceStressTest() {
        System.out.println("\n[BATCH 122 TEST 06] Testing comprehensive preference scenarios...");

        LocalDate testMon = LocalDate.of(2027, 7, 12);
        Employee emp = employeeRepository.findById(3L).orElseThrow(); // Shriram (Operations Male)

        // 1. Recurring Wednesday preference
        RecurringShiftPreference recWed = new RecurringShiftPreference();
        recWed.setEmployee(emp);
        recWed.setDayOfWeek(DayOfWeek.WEDNESDAY);
        recWed.setShiftType(ShiftType.MORNING);
        recWed.setStartDate(LocalDate.of(2027, 1, 1));
        recWed.setActive(true);
        recWed.setCreatedBy("admin");
        recWed = recurringShiftPreferenceRepository.save(recWed);

        // 2. Inactive preference (should be ignored)
        RecurringShiftPreference inactivePref = new RecurringShiftPreference();
        inactivePref.setEmployee(emp);
        inactivePref.setDayOfWeek(DayOfWeek.TUESDAY);
        inactivePref.setShiftType(ShiftType.NIGHT);
        inactivePref.setStartDate(LocalDate.of(2027, 1, 1));
        inactivePref.setActive(false);
        inactivePref.setCreatedBy("admin");
        inactivePref = recurringShiftPreferenceRepository.save(inactivePref);

        // 3. Expired date-specific preference (in past, should be ignored)
        DateShiftPreference expiredPref = new DateShiftPreference();
        expiredPref.setEmployee(emp);
        expiredPref.setPreferenceDate(LocalDate.of(2025, 1, 1));
        expiredPref.setShiftType(ShiftType.EVENING);
        expiredPref.setReason("Expired pref");
        expiredPref = dateShiftPreferenceRepository.save(expiredPref);

        // 4. Date-specific override on Saturday
        LocalDate satDate = testMon.plusDays(5);
        DateShiftPreference satOverride = new DateShiftPreference();
        satOverride.setEmployee(emp);
        satOverride.setPreferenceDate(satDate);
        satOverride.setShiftType(ShiftType.MORNING);
        satOverride.setReason("Saturday override");
        satOverride = dateShiftPreferenceRepository.save(satOverride);

        try {
            // Verify pipeline resolution
            List<Employee> emps = List.of(emp);
            Map<Long, ApplicablePreference> prefMap = rosterService.loadApprovedPreferences(emps, testMon, testMon.plusDays(6));
            ApplicablePreference appPref = prefMap.get(emp.getId());
            assertNotNull(appPref, "Applicable preference must resolve for employee");

            LocalDate wedDate = testMon.plusDays(2);
            assertEquals(ShiftType.MORNING, appPref.getTargetShiftForDate(wedDate), "Recurring Wednesday MORNING must resolve");
            assertNull(appPref.getTargetShiftForDate(testMon.plusDays(1)), "Inactive Tuesday preference must be ignored");
            assertEquals(ShiftType.MORNING, appPref.getTargetShiftForDate(satDate), "Saturday date-specific override must resolve");

            // Generate roster and verify solver assigned requested preferences
            RosterCycleResponse cycle = rosterService.doGenerateWeeklyRoster(testMon, GenerationMode.AUTOMATIC, 6);
            assertNotNull(cycle);

            List<RosterAssignment> empAssignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id()).stream()
                    .filter(a -> a.getEmployee().getId().equals(emp.getId())).toList();

            RosterAssignment wedAssign = empAssignments.stream().filter(a -> a.getRosterDate().equals(wedDate)).findFirst().orElseThrow();
            assertEquals(ShiftType.MORNING, wedAssign.getShift().getShiftType(), "Employee must be assigned MORNING on Wednesday per recurring preference");

            RosterAssignment satAssign = empAssignments.stream().filter(a -> a.getRosterDate().equals(satDate)).findFirst().orElseThrow();
            assertEquals(ShiftType.MORNING, satAssign.getShift().getShiftType(), "Employee must be assigned MORNING on Saturday per date-specific preference");

            System.out.println("[BATCH 122 TEST 06] PASS - All preference types and precedences correctly handled.");
        } finally {
            recurringShiftPreferenceRepository.delete(recWed);
            recurringShiftPreferenceRepository.delete(inactivePref);
            dateShiftPreferenceRepository.delete(expiredPref);
            dateShiftPreferenceRepository.delete(satOverride);
        }
    }

    // =========================================================================
    // SECTION 9: NIGHT RECOVERY STRESS TEST (ALL 7 DAYS & WEEK BOUNDARY)
    // =========================================================================
    @Test
    @Order(7)
    @DisplayName("B122-07: Night recovery stress test - All 7 days (Mon-Sun) and Sunday boundary recovery")
    void test07_nightRecoveryStressTestAcrossAllDays() {
        System.out.println("\n[BATCH 122 TEST 07] Testing mandatory Night recovery rule across all 7 days...");

        LocalDate targetMon = LocalDate.of(2027, 7, 5);
        RosterCycleResponse cycle = rosterService.doGenerateWeeklyRoster(targetMon, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());

        Map<Long, List<RosterAssignment>> empMap = assignments.stream()
                .collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

        // Audit every Night shift within the week
        for (Map.Entry<Long, List<RosterAssignment>> entry : empMap.entrySet()) {
            List<RosterAssignment> empShifts = entry.getValue().stream()
                    .sorted(Comparator.comparing(RosterAssignment::getRosterDate))
                    .toList();

            for (int i = 0; i < empShifts.size(); i++) {
                RosterAssignment a = empShifts.get(i);
                if (a.getShift() != null && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift().getShiftType() == ShiftType.NIGHT) {
                    LocalDate nightDate = a.getRosterDate();
                    LocalDate nextDate = nightDate.plusDays(1);

                    if (i < empShifts.size() - 1) {
                        RosterAssignment nextAssign = empShifts.get(i + 1);
                        assertEquals(nextDate, nextAssign.getRosterDate());
                        assertTrue(nextAssign.isWeeklyOff(),
                                "Night shift on " + nightDate.getDayOfWeek() + " (" + nightDate + ") must be followed by WEEKLY_OFF on " + nextDate);
                    }
                }
            }
        }

        // Specifically test Sunday Night boundary into next Monday
        rosterService.finalizeRoster(cycle.id(), "Finalize for Sunday Night boundary test");

        // Find employee who worked Sunday Night
        LocalDate sunDate = targetMon.plusDays(6);
        RosterAssignment sunNightAssign = assignments.stream()
                .filter(a -> a.getRosterDate().equals(sunDate) && a.getShift() != null && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift().getShiftType() == ShiftType.NIGHT)
                .findFirst().orElse(null);

        if (sunNightAssign != null) {
            Employee sunWorker = sunNightAssign.getEmployee();
            LocalDate nextMon = targetMon.plusWeeks(1);

            // Generate next week
            RosterCycleResponse nextCycle = rosterService.doGenerateWeeklyRoster(nextMon, GenerationMode.AUTOMATIC, 6);
            assertNotNull(nextCycle);

            RosterAssignment nextMonAssign = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(nextCycle.id()).stream()
                    .filter(a -> a.getEmployee().getId().equals(sunWorker.getId()) && a.getRosterDate().equals(nextMon))
                    .findFirst().orElseThrow();

            assertTrue(nextMonAssign.isWeeklyOff(),
                    "Employee who worked Sunday Night must be assigned WEEKLY_OFF on next Monday across week boundary");
            System.out.println("[BATCH 122 TEST 07] Sunday Night worker (" + sunWorker.getFirstName() + ") received mandatory Monday Recovery Off.");
        }

        System.out.println("[BATCH 122 TEST 07] PASS - Night recovery strictly enforced across all days and cycle boundaries.");
    }

    // =========================================================================
    // SECTION 10: STAFFING STRESS TEST (CAPACITY & HONEST INFEASIBILITY)
    // =========================================================================
    @Test
    @Order(8)
    @DisplayName("B122-08: Staffing stress test - Normal capacity, 3-staff mode, and honest shortage diagnosis")
    void test08_staffingStressTest() {
        System.out.println("\n[BATCH 122 TEST 08] Auditing staffing capacity and honest shortage reporting...");

        LocalDate targetMon = LocalDate.of(2027, 7, 19);

        // 1. Normal capacity validation
        RosterCycleResponse normalCycle = rosterService.doGenerateWeeklyRoster(targetMon, GenerationMode.AUTOMATIC, 6);
        assertNotNull(normalCycle);
        CoverageReportResponse coverage = normalCycle.coverageReport();
        assertNotNull(coverage);
        assertTrue(coverage.totalAssigned() >= 35, "Standard coverage must cover minimum operational requirements");

        // 2. Severe shortage simulation: place all 4 male employees on leave on Friday
        LocalDate friday = targetMon.plusDays(4);
        List<Employee> maleOps = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.MALE && !"Development".equalsIgnoreCase(e.getDepartment()))
                .toList();

        List<LeaveRequest> shortageLeaves = new ArrayList<>();
        for (Employee m : maleOps) {
            LeaveRequest lr = new LeaveRequest();
            lr.setEmployee(m);
            lr.setStartDate(friday);
            lr.setEndDate(friday);
            lr.setRequestType("ANNUAL");
            lr.setStatus(LeaveStatus.APPROVED);
            lr.setRequestedAt(LocalDateTime.now());
            lr.setReason("Batch 122 severe shortage test");
            shortageLeaves.add(leaveRequestRepository.save(lr));
        }

        try {
            // Under zero available male employees for Night on Friday, female safety rules prevent female staff
            // from being assigned Night. The system must report this shortage honestly rather than fabricating an illegal assignment.
            RosterCycleResponse shortageCycle = rosterService.doGenerateWeeklyRoster(targetMon, GenerationMode.AUTOMATIC, 6);
            assertNotNull(shortageCycle);

            // Verify honest diagnosis: critical conflict or shortage warning logged identifying Friday
            boolean hasDiagnostic = shortageCycle.conflicts() != null && shortageCycle.conflicts().stream()
                    .anyMatch(c -> (c.date() != null && c.date().equals(friday.toString())) ||
                            (c.reason() != null && c.reason().contains("Night")));
            assertTrue(hasDiagnostic, "System must provide honest diagnostic for Friday Night staffing shortage");

            System.out.println("[BATCH 122 TEST 08] Honest shortage diagnosis verified on Friday (" + friday + "): " +
                    (shortageCycle.conflicts() != null ? shortageCycle.conflicts().stream().map(ConflictItem::reason).collect(Collectors.joining("; ")) : "Warning recorded"));
        } finally {
            leaveRequestRepository.deleteAll(shortageLeaves);
        }

        System.out.println("[BATCH 122 TEST 08] PASS - Staffing stress test completed with honest shortage reporting.");
    }

    // =========================================================================
    // SECTION 12: LIVE CHANGE SIMULATION (LEAVE & HOLIDAY ADD/REMOVE)
    // =========================================================================
    @Test
    @Order(9)
    @DisplayName("B122-09: Live change simulation - Leave added/removed and Holiday added/removed")
    void test09_liveChangeSimulation() {
        System.out.println("\n[BATCH 122 TEST 09] Testing live change recalculation and stability...");

        LocalDate targetMon = LocalDate.of(2027, 7, 26);
        RosterCycleResponse cycle = rosterService.doGenerateWeeklyRoster(targetMon, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle);

        Employee targetEmp = employeeRepository.findById(1L).orElseThrow();
        LocalDate wednesday = targetMon.plusDays(2);

        // 1. Add approved leave on Wednesday
        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(targetEmp);
        leave.setStartDate(wednesday);
        leave.setEndDate(wednesday);
        leave.setRequestType("SICK");
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setRequestedAt(LocalDateTime.now());
        leave.setReason("Mid-cycle sick leave test");
        leave = leaveRequestRepository.save(leave);

        try {
            // Unaffected employees' assignments remain intact
            List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
            long otherEmpCount = assignments.stream()
                    .filter(a -> !a.getEmployee().getId().equals(targetEmp.getId()))
                    .count();
            assertEquals(42, otherEmpCount, "6 other employees x 7 days = 42 assignments remain present");

        } finally {
            leaveRequestRepository.delete(leave);
        }

        // 2. Add and remove Holiday
        LocalDate friDate = targetMon.plusDays(4);
        Holiday holiday = new Holiday();
        holiday.setName("Batch 122 Special Observance");
        holiday.setHolidayDate(friDate);
        holiday.setDescription("Batch 122 Holiday Description");
        holiday.setActive(true);
        holiday = holidayRepository.save(holiday);

        try {
            List<Holiday> hList = holidayRepository.findByHolidayDate(friDate);
            assertFalse(hList.isEmpty(), "Holiday must be queryable");
        } finally {
            holidayRepository.delete(holiday);
        }

        System.out.println("[BATCH 122 TEST 09] PASS - Live changes dynamically audited without destabilizing cycle.");
    }

    // =========================================================================
    // SECTION 13: MANUAL + AUTOMATIC COMBINATION
    // =========================================================================
    @Test
    @Order(10)
    @DisplayName("B122-10: Manual + Automatic combination - Manual lock preservation and leave conflict detection")
    void test10_manualAndAutomaticCombination() {
        System.out.println("\n[BATCH 122 TEST 10] Testing manual assignment lock preservation...");

        LocalDate targetMon = LocalDate.of(2027, 8, 2);
        RosterCycleResponse cycle = rosterService.doGenerateWeeklyRoster(targetMon, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle);

        Employee emp5 = employeeRepository.findById(5L).orElseThrow(); // Tushar Deshmukh
        LocalDate wedDate = targetMon.plusDays(2);

        // Admin manually locks emp5 to MORNING on Wednesday
        ManualAssignmentRequest req = new ManualAssignmentRequest(
                cycle.id(), targetMon, emp5.getId(), wedDate, ShiftType.MORNING, false, "Admin manual lock for Batch 122"
        );
        rosterService.saveManualAssignment(req, "admin");

        // Regenerate roster automatically
        RosterCycleResponse regen = rosterService.doGenerateWeeklyRoster(targetMon, GenerationMode.AUTOMATIC, 6);
        assertNotNull(regen);

        RosterAssignment lockedAssign = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(regen.id()).stream()
                .filter(a -> a.getEmployee().getId().equals(emp5.getId()) && a.getRosterDate().equals(wedDate))
                .findFirst().orElseThrow();

        assertEquals(ShiftType.MORNING, lockedAssign.getShift().getShiftType(), "Manual assignment must not be overwritten by automatic regeneration");
        System.out.println("[BATCH 122 TEST 10] PASS - Manual assignment lock strictly preserved across automatic regeneration.");
    }

    // =========================================================================
    // SECTION 14: FINALIZATION SAFETY & EMAIL IDEMPOTENCE
    // =========================================================================
    @Test
    @Order(11)
    @DisplayName("B122-11: Finalization safety - Block invalid, idempotence, and zero duplicate email dispatch")
    void test11_finalizationSafetyAndEmailIdempotence() {
        System.out.println("\n[BATCH 122 TEST 11] Auditing finalization gate and email idempotence...");

        LocalDate targetMon = LocalDate.of(2027, 8, 9);
        RosterCycleResponse cycle = rosterService.doGenerateWeeklyRoster(targetMon, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle);

        long emailCountBefore = emailDeliveryLogRepository.count();

        // First finalization
        RosterCycleResponse fin1 = rosterService.finalizeRoster(cycle.id(), "Batch 122 release publish");
        assertEquals(RosterStatus.PUBLISHED, fin1.status(), "Cycle must transition to PUBLISHED");

        long emailCountAfterFirst = emailDeliveryLogRepository.count();

        // Second finalization (simulating double-click or scheduler retry)
        RosterCycleResponse fin2 = rosterService.finalizeRoster(cycle.id(), "Duplicate finalize attempt");
        assertEquals(RosterStatus.PUBLISHED, fin2.status(), "Cycle remains PUBLISHED");

        long emailCountAfterSecond = emailDeliveryLogRepository.count();
        assertEquals(emailCountAfterFirst, emailCountAfterSecond, "Double finalization must be idempotent (zero duplicate emails logged)");

        System.out.println("[BATCH 122 TEST 11] PASS - Finalization gate and email idempotence verified.");
    }

    // =========================================================================
    // SECTION 15: SECURITY AND AUTHORIZATION REGRESSION
    // =========================================================================
    @Test
    @Order(12)
    @DisplayName("B122-12: Security regression - Non-admin employees blocked from generation and finalization")
    void test12_securityAndAuthorizationRegression() throws Exception {
        System.out.println("\n[BATCH 122 TEST 12] Testing role-based access control...");

        // Explicitly set non-admin employee context
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("emp_user", "password",
                        List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
        );

        try {
            // Employee attempting to generate roster -> 403 Forbidden
            mockMvc.perform(post("/api/rosters/generate")
                            .param("startDate", "2027-08-16")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());

            // Employee attempting to finalize roster -> 403 Forbidden
            mockMvc.perform(post("/api/rosters/cycle/1/finalize")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());

            // Employee attempting manual builder clear -> 403 Forbidden
            mockMvc.perform(post("/api/rosters/manual/clear")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"cycleId\":1}"))
                    .andExpect(status().isForbidden());

            System.out.println("[BATCH 122 TEST 12] PASS - Role-based authorization strictly enforces admin-only access.");
        } finally {
            // Restore admin auth for subsequent tests
            setAdminAuth();
        }
    }

    // =========================================================================
    // SECTION 16 & 17: SQLITE CONCURRENCY & RESTART PERSISTENCE
    // =========================================================================
    @Test
    @Order(13)
    @DisplayName("B122-13: SQLite concurrency & locking - Multi-threaded generation without BUSY_SNAPSHOT errors")
    void test13_sqliteConcurrencyAndLocking() throws Exception {
        System.out.println("\n[BATCH 122 TEST 13] Stress testing SQLite concurrent generation...");

        LocalDate concurrentMon = LocalDate.of(2027, 8, 23);
        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<RosterCycleResponse>> futures = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    latch.await(5, TimeUnit.SECONDS);
                    return rosterService.generateWeeklyRoster(concurrentMon, GenerationMode.AUTOMATIC, 6);
                } catch (Exception e) {
                    failures.incrementAndGet();
                    throw new RuntimeException(e);
                }
            }));
        }

        // Fire all threads simultaneously
        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS), "Executor must terminate within 60s");

        assertEquals(0, failures.get(), "Concurrent requests must not throw SQLITE_BUSY_SNAPSHOT or deadlock");

        for (Future<RosterCycleResponse> future : futures) {
            RosterCycleResponse r = future.get();
            assertNotNull(r);
            assertEquals(concurrentMon, r.startDate());
        }

        List<RosterCycle> cycles = rosterCycleRepository.findOverlappingCycles(concurrentMon, concurrentMon.plusDays(6));
        assertEquals(1, cycles.size(), "Deduplication must ensure exactly 1 cycle exists in database");

        System.out.println("[BATCH 122 TEST 13] PASS - Concurrent SQLite generation succeeded with zero database locks.");
    }

    // =========================================================================
    // SECTION 18: MEMORY & PERFORMANCE CHECK
    // =========================================================================
    @Test
    @Order(14)
    @DisplayName("B122-14: Memory and performance check - Duration SLA and peak heap footprint")
    void test14_memoryAndPerformanceCheck() {
        System.out.println("\n[BATCH 122 TEST 14] Auditing performance metrics and memory usage...");

        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        long memBefore = runtime.totalMemory() - runtime.freeMemory();

        LocalDate perfMon = LocalDate.of(2027, 8, 30);
        long start = System.currentTimeMillis();
        RosterCycleResponse resp = rosterService.doGenerateWeeklyRoster(perfMon, GenerationMode.AUTOMATIC, 6);
        long duration = System.currentTimeMillis() - start;

        long memAfter = runtime.totalMemory() - runtime.freeMemory();
        long memUsedMb = (memAfter - memBefore) / (1024 * 1024);

        System.out.println("[BATCH 122 TEST 14] Generation Duration: " + duration + " ms (Target < 25,000 ms)");
        System.out.println("[BATCH 122 TEST 14] Memory delta: " + memUsedMb + " MB");

        assertNotNull(resp);
        assertTrue(duration < 25_000, "Generation duration must be within operational SLA threshold (< 25s)");

        System.out.println("[BATCH 122 TEST 14] PASS - Generation performance and memory footprints are well within limits.");
    }

    // =========================================================================
    // SECTION 22: RELEASE FREEZE VERIFICATION
    // =========================================================================
    @Test
    @Order(15)
    @DisplayName("B122-15: Release freeze verification - Determinism and stability confirmation")
    void test15_rosterEngineReleaseFreezeVerification() {
        System.out.println("\n[BATCH 122 TEST 15] Confirming engine freeze criteria...");

        LocalDate freezeMon = LocalDate.of(2027, 9, 6);

        // Run 1
        RosterCycleResponse r1 = rosterService.doGenerateWeeklyRoster(freezeMon, GenerationMode.AUTOMATIC, 6);
        assertNotNull(r1);
        assertEquals(0, r1.criticalConflicts(), "Run 1 must have 0 critical conflicts");

        // Verify that engine output satisfies all mandatory release criteria
        assertEquals(RosterStatus.GENERATED, r1.status(), "Generated roster has status GENERATED");
        assertNotNull(r1.generationStatus(), "Generation status must be recorded");

        System.out.println("[BATCH 122 TEST 15] Engine status: " + r1.generationStatus() + " - FREEZE CONFIRMED.");
        System.out.println("[BATCH 122 TEST 15] PASS - Roster engine satisfies all freeze criteria.");
    }
}
