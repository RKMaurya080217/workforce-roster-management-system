package com.weeklyroster.service;

import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
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
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wrms.dev.credential-mirror.enabled=false",
        "spring.datasource.url=jdbc:sqlite:target/test-wrms-b134.db?busy_timeout=30000&journal_mode=WAL"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Batch134RosterEngineAuditAndOptimizationTest {

    private static final String REAL_DB_PATH = "data/wrms.db";
    private static final String REPLICA_DB_PATH = "target/test-wrms-b134.db";

    static {
        try {
            System.setProperty("spring.profiles.active", "test");
            System.setProperty("spring.datasource.url", "jdbc:sqlite:" + REPLICA_DB_PATH + "?busy_timeout=30000&journal_mode=WAL");
            File targetDir = new File("target");
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
            File realDb = new File(REAL_DB_PATH);
            File testDb = new File(REPLICA_DB_PATH);
            if (realDb.exists()) {
                Files.copy(realDb.toPath(), testDb.toPath(), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[BATCH 134 TEST] Cloned " + REAL_DB_PATH + " to isolated test replica " + REPLICA_DB_PATH);
            }
        } catch (Exception e) {
            System.err.println("[BATCH 134 TEST] Database initialization warning: " + e.getMessage());
        }
    }

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterValidatorService rosterValidatorService;

    @Autowired
    private RosterCycleRepository rosterCycleRepository;

    @Autowired
    private RosterAssignmentRepository rosterAssignmentRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private HolidayRepository holidayRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private DateShiftPreferenceRepository dateShiftPreferenceRepository;

    @Autowired
    private RecurringShiftPreferenceRepository recurringShiftPreferenceRepository;

    @Autowired
    private RosterOverrideRepository overrideRepository;

    private List<Employee> baselineEmployees;

    @BeforeEach
    void setUpSecurity() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "admin", "admin", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        baselineEmployees = employeeRepository.findAll();
    }

    private void restoreBaselineEmployees() {
        for (Employee b : baselineEmployees) {
            employeeRepository.save(b);
        }
    }

    // =========================================================================
    // 1. WORKFORCE SCALING: 4, 5, 7, 10, 15, 25 Employees
    // =========================================================================
    @Test
    @Order(1)
    @DisplayName("01. Workforce Scaling: Valid rosters across 4, 5, 7, 10, 15, and 25 employee teams")
    void test01_workforceSizeScaling() {
        int[] targetSizes = {4, 5, 7, 10, 15, 25};
        List<Employee> createdEmployees = new ArrayList<>();

        try {
            for (int size : targetSizes) {
                System.out.println("\n[BATCH 134 SCALING] Testing workforce size: " + size + " employees...");

                // Reset all baseline employees
                restoreBaselineEmployees();

                List<Employee> allEmps = employeeRepository.findAll();
                List<Employee> males = allEmps.stream()
                        .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam())
                        .collect(Collectors.toList());
                List<Employee> females = allEmps.stream()
                        .filter(e -> e.getGender() == Gender.FEMALE)
                        .collect(Collectors.toList());

                // Create extra temporary employees if needed to reach target size
                int currentTotal = males.size() + females.size();
                while (currentTotal < size) {
                    Employee extra = new Employee();
                    String code = "B134_" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
                    extra.setEmployeeCode(code);
                    extra.setFirstName("ScaleUser" + (currentTotal + 1));
                    extra.setLastName("Batch134");
                    extra.setEmail(code.toLowerCase() + "@cris.test");
                    // Keep male to female ratio realistic (at least 4 males for night coverage)
                    boolean isFemale = (males.size() >= 4 && females.size() < (size / 3));
                    extra.setGender(isFemale ? Gender.FEMALE : Gender.MALE);
                    extra.setActive(true);
                    extra.setDepartment("OPERATIONS");
                    extra.setJoiningDate(LocalDate.of(2025, 1, 1));
                    Employee saved = employeeRepository.save(extra);
                    createdEmployees.add(saved);
                    if (isFemale) females.add(saved); else males.add(saved);
                    currentTotal++;
                }

                // Activate exactly `size` employees ensuring at least 4 active males for 7-day night shift coverage
                int reqMales = (size == 4) ? 4 : Math.min(size - 1, Math.max(4, males.size()));
                int reqFemales = size - reqMales;

                for (Employee e : allEmps) {
                    e.setActive(false);
                    employeeRepository.save(e);
                }
                for (Employee ce : createdEmployees) {
                    ce.setActive(false);
                    employeeRepository.save(ce);
                }

                for (int m = 0; m < reqMales; m++) {
                    Employee male = males.get(m);
                    male.setActive(true);
                    if (reqMales <= 4) {
                        male.setMaxNightsPerWeek(2);
                    }
                    employeeRepository.save(male);
                }
                for (int f = 0; f < reqFemales; f++) {
                    Employee female = females.get(f);
                    female.setActive(true);
                    employeeRepository.save(female);
                }

                List<Employee> activeForTest = employeeRepository.findByActiveTrueOrderByIdAsc();
                assertEquals(size, activeForTest.size(), "Active workforce must equal exactly " + size);

                LocalDate testMonday = LocalDate.of(2028, 1, 3).plusWeeks(size);
                long t0 = System.currentTimeMillis();
                RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
                long elapsed = System.currentTimeMillis() - t0;

                assertNotNull(resp, "Roster response must not be null for size " + size);
                assertEquals("VALID", resp.generationStatus(), "Generation status must be VALID for size " + size);
                assertEquals(0, resp.criticalConflicts(), "Zero critical conflicts for size " + size + ": " + resp.conflicts());
                System.out.printf("[BATCH 134 SCALING] Size: %d, Generated in: %d ms, Critical Conflicts: %d, Health Score: %.1f\n",
                        size, elapsed, resp.criticalConflicts(), resp.healthScore());

                // Validate via RosterValidatorService
                RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
                assertNotNull(valResp);
                assertEquals(0, valResp.errorCount(),
                        "Validator must report 0 errors for size " + size + ", findings: " + valResp.findings());

                // Verify Weekly Off counts: every active employee gets exactly 1 off in 6-day week
                List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());
                Map<Long, List<RosterAssignment>> empMap = assigns.stream()
                        .collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

                for (Map.Entry<Long, List<RosterAssignment>> entry : empMap.entrySet()) {
                    long offCount = entry.getValue().stream().filter(RosterAssignment::isWeeklyOff).count();
                    boolean worksSundayNight = entry.getValue().stream().anyMatch(a ->
                            a.getRosterDate().getDayOfWeek() == DayOfWeek.SUNDAY
                            && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT
                            && !a.isWeeklyOff() && !a.isOnLeave());
                    long expectedOffs = worksSundayNight ? 0 : 1;
                    assertEquals(expectedOffs, offCount, "Active employee in 6-day week must receive exactly expected Weekly Off count");
                }

                // Clean up generated cycle
                rosterService.deleteCycle(resp.id());
            }
        } finally {
            for (Employee ce : createdEmployees) {
                try {
                    employeeRepository.delete(ce);
                } catch (Exception ignored) {}
            }
            restoreBaselineEmployees();
        }
    }

    // =========================================================================
    // 2. HARD CONSTRAINTS: Exact 1 Night, Max 2 Nights, Clustered, Post-Night Recovery
    // =========================================================================
    @Test
    @Order(2)
    @DisplayName("02. Night Shift Rules: Exact 1 night/day, Max 2 nights/week, Clustered consecutively, Post-Night Off")
    void test02_nightShiftRulesExact1PerDayMax2ClusteredAndPostNightRecovery() {
        restoreBaselineEmployees();
        LocalDate testMonday = LocalDate.of(2028, 4, 10);
        RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(resp);
        assertEquals(0, resp.criticalConflicts(), "Zero critical conflicts: " + resp.conflicts());

        List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());

        // 1. Exact 1 Night per calendar day
        Map<LocalDate, List<RosterAssignment>> byDate = assigns.stream()
                .collect(Collectors.groupingBy(RosterAssignment::getRosterDate));

        for (LocalDate d = testMonday; !d.isAfter(testMonday.plusDays(6)); d = d.plusDays(1)) {
            List<RosterAssignment> dayAssigns = byDate.getOrDefault(d, Collections.emptyList());
            long nightCount = dayAssigns.stream()
                    .filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT)
                    .count();
            assertEquals(1, nightCount, "Date " + d + " must have strictly EXACTLY 1 employee assigned to NIGHT shift");
        }

        // 2. Max 2 Nights & Clustered & Post-Night Off
        Map<Long, List<RosterAssignment>> byEmp = assigns.stream()
                .collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

        for (Map.Entry<Long, List<RosterAssignment>> entry : byEmp.entrySet()) {
            List<RosterAssignment> empAssigns = entry.getValue().stream()
                    .sorted(Comparator.comparing(RosterAssignment::getRosterDate))
                    .toList();

            List<LocalDate> nightDates = empAssigns.stream()
                    .filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT)
                    .map(RosterAssignment::getRosterDate)
                    .sorted()
                    .toList();

            assertTrue(nightDates.size() <= 2, "Employee assigned " + nightDates.size() + " nights (max allowed is 2)");

            if (nightDates.size() == 2) {
                // Must be clustered consecutively
                assertEquals(nightDates.get(0).plusDays(1), nightDates.get(1),
                        "Night shifts must be clustered consecutively: " + nightDates);

                // Day following 2nd night must be Weekly Off if within cycle
                LocalDate postNight = nightDates.get(1).plusDays(1);
                if (!postNight.isAfter(testMonday.plusDays(6))) {
                    RosterAssignment postAssign = empAssigns.stream()
                            .filter(a -> a.getRosterDate().equals(postNight))
                            .findFirst().orElse(null);
                    assertNotNull(postAssign);
                    assertTrue(postAssign.isWeeklyOff(), "Day after 2 consecutive nights (" + postNight + ") must be Weekly Off");
                }
            } else if (nightDates.size() == 1) {
                // Day following single night must be Weekly Off if within cycle
                LocalDate postNight = nightDates.get(0).plusDays(1);
                if (!postNight.isAfter(testMonday.plusDays(6))) {
                    RosterAssignment postAssign = empAssigns.stream()
                            .filter(a -> a.getRosterDate().equals(postNight))
                            .findFirst().orElse(null);
                    assertNotNull(postAssign);
                    assertTrue(postAssign.isWeeklyOff(), "Day after single night (" + postNight + ") must be Weekly Off");
                }
            }
        }

        rosterService.deleteCycle(resp.id());
    }

    // =========================================================================
    // 3. CROSS-WEEK BOUNDARY: Sunday Night -> Monday Recovery Weekly Off
    // =========================================================================
    @Test
    @Order(3)
    @DisplayName("03. Cross-Week Boundary: Sunday Night -> Monday Weekly Off, Historical cycle immutable")
    void test03_crossWeekSundayNightToMondayOffPreservesHistoricalCycle() {
        restoreBaselineEmployees();
        LocalDate week1Monday = LocalDate.of(2028, 5, 1);
        LocalDate week2Monday = week1Monday.plusWeeks(1);

        // Generate Week 1
        RosterCycleResponse w1Resp = rosterService.generateWeeklyRoster(week1Monday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(w1Resp);

        // Find which employee had Night shift on Sunday of Week 1
        LocalDate sunday = week1Monday.plusDays(6);
        List<RosterAssignment> w1Assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(w1Resp.id());
        RosterAssignment sundayNightAssign = w1Assigns.stream()
                .filter(a -> a.getRosterDate().equals(sunday))
                .filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT)
                .findFirst().orElse(null);

        assertNotNull(sundayNightAssign, "Sunday must have an employee on NIGHT shift");
        Long nightEmpId = sundayNightAssign.getEmployee().getId();
        String sundayNightEmpCode = sundayNightAssign.getEmployee().getEmployeeCode();

        // Finalize Week 1
        RosterCycle w1Cycle = rosterCycleRepository.findById(w1Resp.id()).orElseThrow();
        w1Cycle.setStatus(RosterStatus.FINAL);
        rosterCycleRepository.save(w1Cycle);

        // Snapshot Week 1 assignments for immutability verification
        Map<Long, ShiftType> w1Snapshot = w1Assigns.stream()
                .collect(Collectors.toMap(
                        RosterAssignment::getId,
                        a -> a.isWeeklyOff() ? ShiftType.OFF : (a.getShift() != null ? a.getShift().getShiftType() : null)
                ));

        // Generate Week 2
        RosterCycleResponse w2Resp = rosterService.generateWeeklyRoster(week2Monday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(w2Resp);

        // Check Week 2 Monday assignment for that night employee
        List<RosterAssignment> w2Assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(w2Resp.id());
        RosterAssignment mondayAssign = w2Assigns.stream()
                .filter(a -> a.getEmployee().getId().equals(nightEmpId) && a.getRosterDate().equals(week2Monday))
                .findFirst().orElse(null);

        assertNotNull(mondayAssign, "Week 2 Monday assignment must exist for " + sundayNightEmpCode);
        assertTrue(mondayAssign.isWeeklyOff(),
                "Employee " + sundayNightEmpCode + " worked Sunday Night in Week 1, so Monday of Week 2 MUST be Weekly Off");

        // Verify Week 1 was NOT altered
        List<RosterAssignment> w1After = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(w1Resp.id());
        for (RosterAssignment a : w1After) {
            ShiftType expected = w1Snapshot.get(a.getId());
            ShiftType actual = a.isWeeklyOff() ? ShiftType.OFF : (a.getShift() != null ? a.getShift().getShiftType() : null);
            assertEquals(expected, actual, "Historical Week 1 assignment " + a.getId() + " must remain immutable");
        }

        // Validate Week 2 via RosterValidatorService: must have ZERO errors
        RosterValidationResponse valResp = rosterValidatorService.validateRoster(w2Resp.id());
        assertEquals(0, valResp.errorCount(), "Week 2 must have zero validation errors: " + valResp.findings());

        // Clean up
        rosterService.deleteCycle(w2Resp.id());
        rosterService.deleteCycle(w1Resp.id());
    }

    // =========================================================================
    // 4. REST INTERVAL RULE: Mandatory >= 12 Hours Rest Between Consecutive Shifts
    // =========================================================================
    @Test
    @Order(4)
    @DisplayName("04. 12-Hour Rest Rule: Minimum 12 hours rest guaranteed across all shift transitions")
    void test04_mandatory12HourRestIntervalAcrossAllShifts() {
        restoreBaselineEmployees();
        LocalDate testMonday = LocalDate.of(2028, 6, 5);
        RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(resp);

        List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());
        Map<Long, List<RosterAssignment>> byEmp = assigns.stream()
                .collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

        for (Map.Entry<Long, List<RosterAssignment>> entry : byEmp.entrySet()) {
            List<RosterAssignment> empList = entry.getValue().stream()
                    .sorted(Comparator.comparing(RosterAssignment::getRosterDate))
                    .toList();

            for (int i = 0; i < empList.size() - 1; i++) {
                RosterAssignment cur = empList.get(i);
                RosterAssignment next = empList.get(i + 1);

                if (!cur.isWeeklyOff() && !cur.isOnLeave() && cur.getShift() != null
                        && !next.isWeeklyOff() && !next.isOnLeave() && next.getShift() != null) {

                    Shift s1 = cur.getShift();
                    Shift s2 = next.getShift();

                    LocalDateTime end1 = cur.getRosterDate().atTime(s1.getEndTime());
                    if (s1.isOvernight() || s1.getEndTime().isBefore(s1.getStartTime())) {
                        end1 = end1.plusDays(1);
                    }
                    LocalDateTime start2 = next.getRosterDate().atTime(s2.getStartTime());

                    long restMinutes = Duration.between(end1, start2).toMinutes();
                    assertTrue(restMinutes >= 720,
                            String.format("Rest violation for employee %s between %s (%s end %s) and %s (%s start %s): %d mins rest (min 720 required)",
                                    cur.getEmployee().getEmployeeCode(),
                                    cur.getRosterDate(), s1.getShiftType(), s1.getEndTime(),
                                    next.getRosterDate(), s2.getShiftType(), s2.getStartTime(),
                                    restMinutes));
                }
            }
        }

        // Validator must confirm zero REST_INTERVAL_VIOLATION findings
        RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
        long restViolations = valResp.findings().stream()
                .filter(f -> "REST_INTERVAL_VIOLATION".equals(f.ruleCode()))
                .count();
        assertEquals(0, restViolations, "Zero rest interval violations allowed");

        rosterService.deleteCycle(resp.id());
    }

    // =========================================================================
    // 5. GENDER RESTRICTIONS: Female Employees Day Shifts Only (Morning/General)
    // =========================================================================
    @Test
    @Order(5)
    @DisplayName("05. Female Safety: Females strictly assigned MORNING or GENERAL or OFF; Zero Evening or Night")
    void test05_femaleSafetyRestrictionsDayOnly() {
        restoreBaselineEmployees();
        LocalDate testMonday = LocalDate.of(2028, 7, 3);
        RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(resp);

        List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());

        long femaleCount = assigns.stream()
                .filter(a -> a.getEmployee().getGender() == Gender.FEMALE)
                .map(a -> a.getEmployee().getId())
                .distinct()
                .count();

        assertTrue(femaleCount > 0, "Workforce must include female employees for validation");

        for (RosterAssignment a : assigns) {
            if (a.getEmployee().getGender() == Gender.FEMALE) {
                if (!a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null) {
                    ShiftType st = a.getShift().getShiftType();
                    assertTrue(st == ShiftType.MORNING || st == ShiftType.GENERAL,
                            "Female employee " + a.getEmployee().getEmployeeCode() + " cannot be assigned " + st + " on " + a.getRosterDate());
                    assertNotEquals(ShiftType.NIGHT, st, "Female employee must NEVER be assigned NIGHT shift");
                    assertNotEquals(ShiftType.EVENING, st, "Female employee must NEVER be assigned EVENING shift");
                }
            }
        }

        // Validator check
        RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
        long femaleViolations = valResp.findings().stream()
                .filter(f -> "FEMALE_SAFETY_VIOLATION".equals(f.ruleCode()))
                .count();
        assertEquals(0, femaleViolations, "Zero female safety violations");

        rosterService.deleteCycle(resp.id());
    }

    // =========================================================================
    // 6. MID-WEEK JOIN & RESIGNATION: Partial Week Eligibility
    // =========================================================================
    @Test
    @Order(6)
    @DisplayName("06. Mid-Week Lifecycle: Mid-week join and mid-week resignation partial week handling")
    void test06_midWeekJoinAndResignationPartialWeekEligibility() {
        restoreBaselineEmployees();
        LocalDate testMonday = LocalDate.of(2028, 8, 7);
        LocalDate joinDate = testMonday.plusDays(2); // Wednesday
        LocalDate resignDate = testMonday.plusDays(4); // Friday

        // Create temporary mid-week joiner
        Employee joiner = new Employee();
        joiner.setEmployeeCode("B134_JOIN");
        joiner.setFirstName("MidWeek");
        joiner.setLastName("Joiner");
        joiner.setEmail("midweek.joiner@cris.test");
        joiner.setGender(Gender.MALE);
        joiner.setActive(true);
        joiner.setDepartment("OPERATIONS");
        joiner.setJoiningDate(joinDate);
        Employee savedJoiner = employeeRepository.save(joiner);

        // Create temporary mid-week resigner
        Employee resigner = new Employee();
        resigner.setEmployeeCode("B134_RESIGN");
        resigner.setFirstName("MidWeek");
        resigner.setLastName("Resigner");
        resigner.setEmail("midweek.resigner@cris.test");
        resigner.setGender(Gender.MALE);
        resigner.setActive(true);
        resigner.setDepartment("OPERATIONS");
        resigner.setJoiningDate(LocalDate.of(2025, 1, 1));
        resigner.setResignationDate(resignDate);
        Employee savedResigner = employeeRepository.save(resigner);

        try {
            RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(resp);

            List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());

            // Check Joiner: Must have NO duty shift before joinDate
            List<RosterAssignment> joinerAssigns = assigns.stream()
                    .filter(a -> a.getEmployee().getId().equals(savedJoiner.getId()))
                    .toList();
            for (RosterAssignment a : joinerAssigns) {
                if (a.getRosterDate().isBefore(joinDate)) {
                    assertTrue(a.isWeeklyOff() || a.getShift() == null || a.getShift().getShiftType() == ShiftType.OFF,
                            "Mid-week joiner cannot work duty before joining date " + joinDate);
                }
            }

            // Check Resigner: Must have NO duty shift after resignDate
            List<RosterAssignment> resignerAssigns = assigns.stream()
                    .filter(a -> a.getEmployee().getId().equals(savedResigner.getId()))
                    .toList();
            for (RosterAssignment a : resignerAssigns) {
                if (a.getRosterDate().isAfter(resignDate)) {
                    assertTrue(a.isWeeklyOff() || a.getShift() == null || a.getShift().getShiftType() == ShiftType.OFF,
                            "Mid-week resigner cannot work duty after resignation date " + resignDate);
                }
            }

            // Validator check: partial week must not trigger INVALID_WORKING_DAYS_COUNT false positives
            RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
            long invalidWorkingDaysErrors = valResp.findings().stream()
                    .filter(f -> "INVALID_WORKING_DAYS_COUNT".equals(f.ruleCode()))
                    .count();
            assertEquals(0, invalidWorkingDaysErrors, "Partial week employees must not get false INVALID_WORKING_DAYS_COUNT");

            rosterService.deleteCycle(resp.id());
        } finally {
            try {
                employeeRepository.delete(savedJoiner);
                employeeRepository.delete(savedResigner);
            } catch (Exception ignored) {}
        }
    }

    // =========================================================================
    // 7. APPROVED LEAVE & OFFICIAL HOLIDAYS: Distinct Invariant (Holiday != Weekly Off)
    // =========================================================================
    @Test
    @Order(7)
    @DisplayName("07. Approved Leave & Holiday: Leave gets ON_LEAVE; Holiday is never converted to Weekly Off")
    void test07_approvedLeaveAndOfficialHolidayHandling() {
        restoreBaselineEmployees();
        LocalDate testMonday = LocalDate.of(2028, 9, 4);
        Employee testEmp = employeeRepository.findByActiveTrueOrderByIdAsc().get(0);

        // 1. Create Approved Leave for Tuesday
        LocalDate leaveDate = testMonday.plusDays(1);
        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(testEmp);
        leave.setStartDate(leaveDate);
        leave.setEndDate(leaveDate);
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setRequestedAt(LocalDateTime.now());
        leave.setReason("Batch 134 Leave Test");
        LeaveRequest savedLeave = leaveRequestRepository.save(leave);

        // 2. Create Official Holiday for Thursday
        LocalDate holidayDate = testMonday.plusDays(3);
        Holiday hol = new Holiday();
        hol.setName("Batch 134 Republic Holiday");
        hol.setHolidayDate(holidayDate);
        hol.setActive(true);
        Holiday savedHoliday = holidayRepository.save(hol);

        try {
            RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(resp);

            List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());

            // Verify leave assignment
            RosterAssignment leaveAssign = assigns.stream()
                    .filter(a -> a.getEmployee().getId().equals(testEmp.getId()) && a.getRosterDate().equals(leaveDate))
                    .findFirst().orElse(null);
            assertNotNull(leaveAssign);
            assertTrue(leaveAssign.isOnLeave(), "Employee must be marked isOnLeave on approved leave date");

            // Verify holiday invariant: Holiday is never converted to Weekly Off
            RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
            long holErrors = valResp.findings().stream()
                    .filter(f -> "HOLIDAY_NOT_WEEKLY_OFF".equals(f.ruleCode()))
                    .count();
            assertEquals(0, holErrors, "Official holiday must never be converted to Weekly Off");

            rosterService.deleteCycle(resp.id());
        } finally {
            leaveRequestRepository.delete(savedLeave);
            holidayRepository.delete(savedHoliday);
        }
    }

    // =========================================================================
    // 8. SHIFT PREFERENCES: Positive & Negative (DO_NOT_ASSIGN) Constraints
    // =========================================================================
    @Test
    @Order(8)
    @DisplayName("08. Shift Preferences: Date-specific positive & negative (DO_NOT_ASSIGN) enforced")
    void test08_shiftPreferencesPositiveAndNegativeRestrictions() {
        restoreBaselineEmployees();
        LocalDate testMonday = LocalDate.of(2028, 10, 2);
        List<Employee> activeMales = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.MALE)
                .toList();
        assertTrue(activeMales.size() >= 2);

        Employee empPos = activeMales.get(0);
        Employee empNeg = activeMales.get(1);

        LocalDate wednesday = testMonday.plusDays(2);
        LocalDate thursday = testMonday.plusDays(3);

        // Positive Date-Specific: empPos requests MORNING on Wednesday
        DateShiftPreference dspPos = new DateShiftPreference();
        dspPos.setEmployee(empPos);
        dspPos.setPreferenceDate(wednesday);
        dspPos.setShiftType(ShiftType.MORNING);
        dspPos.setPreferenceType(RecurringPreferenceType.PREFERRED);
        dspPos.setReason("Positive Preference Test");
        DateShiftPreference savedDspPos = dateShiftPreferenceRepository.save(dspPos);

        // Negative Date-Specific: empNeg requests DO_NOT_ASSIGN EVENING on Thursday
        DateShiftPreference dspNeg = new DateShiftPreference();
        dspNeg.setEmployee(empNeg);
        dspNeg.setPreferenceDate(thursday);
        dspNeg.setShiftType(ShiftType.EVENING);
        dspNeg.setPreferenceType(RecurringPreferenceType.DO_NOT_ASSIGN);
        dspNeg.setReason("Negative Restriction Test");
        DateShiftPreference savedDspNeg = dateShiftPreferenceRepository.save(dspNeg);

        try {
            RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(resp);

            List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());

            // Verify empPos received MORNING on Wednesday
            RosterAssignment posAssign = assigns.stream()
                    .filter(a -> a.getEmployee().getId().equals(empPos.getId()) && a.getRosterDate().equals(wednesday))
                    .findFirst().orElse(null);
            assertNotNull(posAssign);
            if (!posAssign.isWeeklyOff() && !posAssign.isOnLeave()) {
                assertEquals(ShiftType.MORNING, posAssign.getShift().getShiftType(),
                        "Positive preference for MORNING must be honored");
            }

            // Verify empNeg is NOT assigned EVENING on Thursday
            RosterAssignment negAssign = assigns.stream()
                    .filter(a -> a.getEmployee().getId().equals(empNeg.getId()) && a.getRosterDate().equals(thursday))
                    .findFirst().orElse(null);
            assertNotNull(negAssign);
            if (!negAssign.isWeeklyOff() && !negAssign.isOnLeave() && negAssign.getShift() != null) {
                assertNotEquals(ShiftType.EVENING, negAssign.getShift().getShiftType(),
                        "Negative restriction 'DO_NOT_ASSIGN EVENING' must be strictly enforced");
            }

            // Validator check
            RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
            long datePrefErrors = valResp.findings().stream()
                    .filter(f -> "DATE_PREFERENCE_VIOLATION".equals(f.ruleCode()) || "DATE_RESTRICTION_VIOLATION".equals(f.ruleCode()))
                    .count();
            assertEquals(0, datePrefErrors, "Zero date-specific preference or restriction violations");

            rosterService.deleteCycle(resp.id());
        } finally {
            dateShiftPreferenceRepository.delete(savedDspPos);
            dateShiftPreferenceRepository.delete(savedDspNeg);
        }
    }

    // =========================================================================
    // 9. MANUAL OVERRIDES: Explicit Assignments Preserved
    // =========================================================================
    @Test
    @Order(9)
    @Transactional
    @DisplayName("09. Manual Assignment Preservation: Admin manual overrides strictly preserved")
    void test09_preservationOfManualAssignmentsAndAdminOverrides() {
        restoreBaselineEmployees();
        LocalDate testMonday = LocalDate.of(2028, 11, 6);
        Employee testEmp = employeeRepository.findByActiveTrueOrderByIdAsc().get(0);
        Shift morningShift = shiftRepository.findByShiftType(ShiftType.MORNING).orElseThrow();

        // Generate draft roster
        RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(resp);

        // Apply manual override on Monday: set to MORNING
        List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());
        RosterAssignment targetAssign = assigns.stream()
                .filter(a -> a.getEmployee().getId().equals(testEmp.getId()) && a.getRosterDate().equals(testMonday))
                .findFirst().orElseThrow();

        targetAssign.setShift(morningShift);
        targetAssign.setWeeklyOff(false);
        targetAssign.setManualAssignment(true);
        targetAssign.setOverridden(true);
        targetAssign.setOverrideReason("Admin mandated Morning shift");
        rosterAssignmentRepository.save(targetAssign);

        RosterOverride override = new RosterOverride();
        override.setAssignment(targetAssign);
        override.setNewShiftType(ShiftType.MORNING);
        override.setWeeklyOff(false);
        override.setReason("Admin mandated Morning shift");
        RosterOverride savedOverride = overrideRepository.save(override);

        try {
            // Validate roster: must recognize and preserve override
            RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
            assertNotNull(valResp);
            long overrideErrors = valResp.findings().stream()
                    .filter(f -> "MANUAL_OVERRIDE_VIOLATION".equals(f.ruleCode()))
                    .count();
            assertEquals(0, overrideErrors, "Manual override must be preserved with zero violations");

            // Read back assignment from database
            RosterAssignment reloaded = rosterAssignmentRepository.findById(targetAssign.getId()).orElseThrow();
            assertEquals(ShiftType.MORNING, reloaded.getShift().getShiftType());
            assertTrue(reloaded.isManualAssignment());
            assertTrue(reloaded.isOverridden());

            rosterService.deleteCycle(resp.id());
        } finally {
            try {
                overrideRepository.delete(savedOverride);
            } catch (Exception ignored) {}
        }
    }

    // =========================================================================
    // 10. INACTIVE EMPLOYEES: Strictly Excluded from Roster Generation
    // =========================================================================
    @Test
    @Order(10)
    @DisplayName("10. Inactive Personnel: Inactive employees never assigned shifts or weekly off")
    void test10_inactiveEmployeesStrictlyExcluded() {
        restoreBaselineEmployees();
        LocalDate testMonday = LocalDate.of(2028, 12, 4);

        // Create an inactive employee
        Employee inactiveEmp = new Employee();
        inactiveEmp.setEmployeeCode("B134_INACTIVE");
        inactiveEmp.setFirstName("Inactive");
        inactiveEmp.setLastName("User");
        inactiveEmp.setEmail("inactive.user@cris.test");
        inactiveEmp.setGender(Gender.MALE);
        inactiveEmp.setActive(false);
        inactiveEmp.setDepartment("OPERATIONS");
        Employee savedInactive = employeeRepository.save(inactiveEmp);

        try {
            RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(resp);

            List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());
            boolean assigned = assigns.stream()
                    .anyMatch(a -> a.getEmployee().getId().equals(savedInactive.getId()));

            assertFalse(assigned, "Inactive employee must NEVER have any assignment in generated roster");

            RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
            long inactiveErrors = valResp.findings().stream()
                    .filter(f -> "INACTIVE_EMPLOYEE".equals(f.ruleCode()))
                    .count();
            assertEquals(0, inactiveErrors, "Zero inactive employee findings");

            rosterService.deleteCycle(resp.id());
        } finally {
            try {
                employeeRepository.delete(savedInactive);
            } catch (Exception ignored) {}
        }
    }

    // =========================================================================
    // 11. INFEASIBILITY DETECTION: Workforce Capacity Shortage
    // =========================================================================
    @Test
    @Order(11)
    @DisplayName("11. Infeasibility Reporting: Clear 'ROSTER INFEASIBLE / WORKFORCE CAPACITY SHORTAGE' error")
    void test11_mathematicalInfeasibilityWorkforceCapacityShortage() {
        restoreBaselineEmployees();
        List<Employee> allActive = employeeRepository.findByActiveTrueOrderByIdAsc();
        List<Employee> temporarilyDeactivated = new ArrayList<>();

        try {
            // Deactivate until only 2 employees remain active (strictly infeasible < 4)
            for (int i = 2; i < allActive.size(); i++) {
                Employee e = allActive.get(i);
                e.setActive(false);
                employeeRepository.save(e);
                temporarilyDeactivated.add(e);
            }

            LocalDate testMonday = LocalDate.of(2029, 1, 1);
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
            }, "Must throw BusinessException when workforce is mathematically insufficient");

            System.out.println("[BATCH 134 INFEASIBILITY] Caught expected message: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("ROSTER INFEASIBLE / WORKFORCE CAPACITY SHORTAGE"),
                    "Exception message must contain exact infeasibility prefix: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("active employees are required"),
                    "Exception message must explain available vs required staff: " + ex.getMessage());
        } finally {
            for (Employee e : temporarilyDeactivated) {
                e.setActive(true);
                employeeRepository.save(e);
            }
            restoreBaselineEmployees();
        }
    }

    // =========================================================================
    // 12. DIVERSITY ON REGENERATION: Distinct Candidates for Deleted Draft
    // =========================================================================
    @Test
    @Order(12)
    @DisplayName("12. Regeneration Diversity: Distinct valid candidate generated when draft is deleted")
    void test12_regenerationDiversityOnDeletedDraft() {
        restoreBaselineEmployees();
        LocalDate testMonday = LocalDate.of(2029, 2, 5);

        // Run 1
        RosterCycleResponse r1 = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(r1);
        List<RosterAssignment> assign1 = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(r1.id());
        String fp1 = RosterService.computeRosterFingerprint(assign1);
        assertNotNull(fp1);

        // Delete Run 1
        rosterService.deleteCycle(r1.id());

        // Run 2
        RosterCycleResponse r2 = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(r2);
        List<RosterAssignment> assign2 = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(r2.id());
        String fp2 = RosterService.computeRosterFingerprint(assign2);
        assertNotNull(fp2);

        // Verify diversity and validity
        assertNotEquals(fp1, fp2, "Regenerated roster must produce a distinct candidate fingerprint from deleted roster");
        assertEquals(0, r2.criticalConflicts(), "Regenerated candidate must have zero critical conflicts: " + r2.conflicts());

        rosterService.deleteCycle(r2.id());
    }

    // =========================================================================
    // 13. DATABASE-DRIVEN SHIFT CAPACITY: Immediate Effect
    // =========================================================================
    @Test
    @Order(13)
    @DisplayName("13. DB-Driven Capacity: Capacity changes in shifts table take immediate effect")
    void test13_databaseDrivenShiftCapacityDynamicAdjustment() {
        restoreBaselineEmployees();
        Shift general = shiftRepository.findByShiftType(ShiftType.GENERAL).orElseThrow();
        int originalCapacity = general.getCapacity();

        try {
            // Update capacity to 5
            general.setCapacity(5);
            shiftRepository.save(general);

            Shift updated = shiftRepository.findByShiftType(ShiftType.GENERAL).orElseThrow();
            assertEquals(5, updated.getCapacity(), "Database capacity must immediately reflect 5");

            LocalDate testMonday = LocalDate.of(2029, 3, 5);
            RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(resp);
            assertEquals(0, resp.criticalConflicts(), "Zero critical conflicts: " + resp.conflicts());

            rosterService.deleteCycle(resp.id());
        } finally {
            general.setCapacity(originalCapacity);
            shiftRepository.save(general);
        }
    }

    // =========================================================================
    // 14. PERFORMANCE & STRESS BENCHMARKS
    // =========================================================================
    @Test
    @Order(14)
    @DisplayName("14. Performance Benchmarks: Generation time < 25s, Validation time < 2s")
    void test14_performanceAndStressBenchmarks() {
        restoreBaselineEmployees();
        LocalDate baseMonday = LocalDate.of(2029, 4, 2);

        for (int w = 0; w < 3; w++) {
            LocalDate monday = baseMonday.plusWeeks(w);

            long t0 = System.currentTimeMillis();
            RosterCycleResponse resp = rosterService.generateWeeklyRoster(monday, GenerationMode.AUTOMATIC, 6);
            long genTime = System.currentTimeMillis() - t0;

            assertNotNull(resp);
            assertTrue(genTime < 25000, "Generation time must be under 25,000 ms (was " + genTime + " ms)");

            long tVal = System.currentTimeMillis();
            RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
            long valTime = System.currentTimeMillis() - tVal;

            assertNotNull(valResp);
            assertTrue(valTime < 2000, "Validation time must be under 2,000 ms (was " + valTime + " ms)");
            assertEquals(0, valResp.errorCount(), "Validation error count must be 0: " + valResp.findings());
            assertTrue(valResp.overallStatus() != ValidationSeverity.ERROR, "Overall status must not be ERROR");

            System.out.printf("[BATCH 134 BENCHMARK] Week %s: Gen = %d ms, Val = %d ms, Checks = %d, Health = %.1f, Status = %s\n",
                    monday, genTime, valTime, valResp.totalChecks(), resp.healthScore(), valResp.overallStatus());

            rosterService.deleteCycle(resp.id());
        }
    }
}
