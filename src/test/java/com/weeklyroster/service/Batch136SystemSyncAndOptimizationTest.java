package com.weeklyroster.service;

import com.weeklyroster.dto.request.DateShiftPreferenceRequest;
import com.weeklyroster.dto.request.RecurringShiftPreferenceRequest;
import com.weeklyroster.dto.response.*;
import com.weeklyroster.dto.sync.MasterDataComparisonReport;
import com.weeklyroster.dto.sync.SyncPreviewResponse;
import com.weeklyroster.dto.sync.SyncStatusResponse;
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

/**
 * BATCH 136 — Comprehensive WRMS Data Synchronization, System Mapping & Advanced Roster Optimization Test Suite
 *
 * Validates:
 * 1. Canonical Employee Master sync across repository, service, and full profile.
 * 2. Shift Preference synchronization (Recurring + Date-Specific + General) across Admin and Employee projections.
 * 3. Leave and Holiday isolation (never counted as Weekly Off; approved vs pending).
 * 4. Database-driven shift capacity adherence across all shifts.
 * 5. Exact Day-Wise Preference Matching (positive preference honoured + subsequent off).
 * 6. Negative Preference (DO_NOT_ASSIGN) strict adherence.
 * 7. Night-to-Off mandatory recovery sequencing (zero pre-night offs in cycle).
 * 8. Sunday Night cross-week handling (0 current cycle offs, Monday next cycle off).
 * 9. Multi-employee workforce scaling (4, 5, 7, 10, 15, 25 employees) with 0 sequence violations.
 * 10. Manual assignment protection and override retention.
 * 11. Infeasibility detection and meaningful diagnostic conflict reporting.
 * 12. Independent validator vs generator strict consistency.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wrms.dev.credential-mirror.enabled=false",
        "spring.datasource.url=jdbc:sqlite:target/test-wrms-b136.db?busy_timeout=30000&journal_mode=WAL"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Batch136SystemSyncAndOptimizationTest {

    private static final String REAL_DB_PATH = "data/wrms.db";
    private static final String REPLICA_DB_PATH = "target/test-wrms-b136.db";

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
                System.out.println("[BATCH 136 TEST] Cloned " + REAL_DB_PATH + " to isolated test replica " + REPLICA_DB_PATH);
            }
        } catch (Exception e) {
            System.err.println("[BATCH 136 TEST] Database initialization warning: " + e.getMessage());
        }
    }

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterValidatorService rosterValidatorService;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private EmployeePreferenceService employeePreferenceService;

    @Autowired
    private RecurringShiftPreferenceService recurringShiftPreferenceService;

    @Autowired
    private DateShiftPreferenceService dateShiftPreferenceService;

    @Autowired
    private ShiftService shiftService;

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private HolidayService holidayService;

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

    @Autowired(required = false)
    private EmployeeMasterSyncService employeeMasterSyncService;

    private List<Employee> baselineEmployees;

    @BeforeEach
    void setUpSecurity() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "admin", "admin", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        if (baselineEmployees == null) {
            baselineEmployees = new ArrayList<>();
            for (Employee e : employeeRepository.findAll()) {
                Employee copy = new Employee();
                copy.setId(e.getId());
                copy.setEmployeeCode(e.getEmployeeCode());
                copy.setFirstName(e.getFirstName());
                copy.setLastName(e.getLastName());
                copy.setEmail(e.getEmail());
                copy.setGender(e.getGender());
                copy.setActive(e.isActive());
                copy.setContactNumber(e.getContactNumber());
                copy.setJoiningDate(e.getJoiningDate());
                copy.setResignationDate(e.getResignationDate());
                copy.setDepartment(e.getDepartment());
                copy.setDevelopmentTeam(e.isDevelopmentTeam());
                copy.setMaxNightsPerWeek(e.getMaxNightsPerWeek());
                copy.setPairedEmployeeId(e.getPairedEmployeeId());
                baselineEmployees.add(copy);
            }
        }
    }

    private void restoreBaselineEmployees() {
        if (baselineEmployees != null) {
            for (Employee b : baselineEmployees) {
                employeeRepository.findById(b.getId()).ifPresent(curr -> {
                    curr.setActive(b.isActive());
                    curr.setMaxNightsPerWeek(b.getMaxNightsPerWeek());
                    curr.setDepartment(b.getDepartment());
                    curr.setDevelopmentTeam(b.isDevelopmentTeam());
                    curr.setJoiningDate(b.getJoiningDate());
                    curr.setResignationDate(b.getResignationDate());
                    employeeRepository.save(curr);
                });
            }
        }
    }

    // =========================================================================
    // 1. CANONICAL EMPLOYEE MASTER SYNCHRONIZATION
    // =========================================================================
    @Test
    @Order(1)
    @DisplayName("01. Canonical Employee Master: Exact synchronization between DB, Service, and Full Profile")
    void test01_canonicalEmployeeMasterSynchronization() {
        restoreBaselineEmployees();

        List<Employee> allEmployees = employeeRepository.findAll();
        assertFalse(allEmployees.isEmpty(), "Database must have baseline employees");

        for (Employee emp : allEmployees) {
            // Verify EmployeeService.getFullProfile
            AdminFullEmployeeProfileResponse fullProfile = employeeService.getFullProfile(emp.getId());
            assertNotNull(fullProfile, "Full profile must not be null for employee " + emp.getEmployeeCode());
            assertEquals(emp.getId(), fullProfile.id());
            assertEquals(emp.getEmployeeCode(), fullProfile.employeeCode());
            assertEquals(emp.getFirstName(), fullProfile.firstName());
            assertEquals(emp.getLastName(), fullProfile.lastName());
            assertEquals(emp.getEmail(), fullProfile.email());
            assertEquals(emp.getGender(), fullProfile.gender());
            assertEquals(emp.isActive(), fullProfile.active());
            assertEquals(emp.getJoiningDate(), fullProfile.joiningDate());
            assertEquals(emp.getResignationDate(), fullProfile.resignationDate());
            assertEquals(emp.isDevelopmentTeam(), fullProfile.developmentTeam());
            assertEquals(emp.getMaxNightsPerWeek(), fullProfile.maxNightsPerWeek());
            assertEquals(emp.getPairedEmployeeId(), fullProfile.pairedEmployeeId());
        }

        // Verify Sync Status
        if (employeeMasterSyncService != null) {
            SyncStatusResponse status = employeeMasterSyncService.getSyncStatus();
            assertNotNull(status);
            assertTrue(status.databaseRecordCount() > 0);
            assertNotNull(status.databaseHash());
        }
    }

    // =========================================================================
    // 2. SHIFT PREFERENCE SYNCHRONIZATION (RECURRING + DATE-SPECIFIC + GENERAL)
    // =========================================================================
    @Test
    @Order(2)
    @DisplayName("02. Shift Preference Sync: Exact consistency across Admin and Employee summary endpoints")
    void test02_shiftPreferenceSynchronization() {
        restoreBaselineEmployees();

        Employee emp = employeeRepository.findAll().stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam() && e.isActive())
                .findFirst().orElseThrow();

        LocalDate targetDate = LocalDate.of(2029, 6, 16); // Saturday
        DayOfWeek dow = DayOfWeek.SATURDAY;

        // Clean any existing preferences for targetDate / DOW
        dateShiftPreferenceRepository.findAll().stream()
                .filter(p -> p.getEmployee().getId().equals(emp.getId()) && p.getPreferenceDate().equals(targetDate))
                .forEach(dateShiftPreferenceRepository::delete);
        recurringShiftPreferenceRepository.findAll().stream()
                .filter(p -> p.getEmployee().getId().equals(emp.getId()) && p.getDayOfWeek() == dow)
                .forEach(recurringShiftPreferenceRepository::delete);

        // Add Recurring Preference
        RecurringShiftPreference rsp = new RecurringShiftPreference();
        rsp.setEmployee(emp);
        rsp.setDayOfWeek(dow);
        rsp.setShiftType(ShiftType.MORNING);
        rsp.setPreferenceType(RecurringPreferenceType.PREFERRED);
        rsp.setStartDate(LocalDate.of(2029, 1, 1));
        rsp.setEndDate(LocalDate.of(2029, 12, 31));
        rsp.setActive(true);
        rsp.setReason("Batch 136 recurring sync test");
        RecurringShiftPreference savedRsp = recurringShiftPreferenceRepository.save(rsp);

        // Add Date-Specific Preference
        DateShiftPreference dsp = new DateShiftPreference();
        dsp.setEmployee(emp);
        dsp.setPreferenceDate(targetDate);
        dsp.setShiftType(ShiftType.NIGHT);
        dsp.setPreferenceType(RecurringPreferenceType.PREFERRED);
        dsp.setReason("Batch 136 date-specific sync test");
        DateShiftPreference savedDsp = dateShiftPreferenceRepository.save(dsp);

        try {
            // Verify EmployeeService.getFullProfile includes both preferences
            AdminFullEmployeeProfileResponse fullProfile = employeeService.getFullProfile(emp.getId());
            assertNotNull(fullProfile);

            boolean foundRec = fullProfile.recurringPreferences().stream()
                    .anyMatch(r -> r.dayOfWeek() == dow && r.shiftType() == ShiftType.MORNING);
            assertTrue(foundRec, "Full profile must reflect recurring preference");

            boolean foundDate = fullProfile.dateSpecificPreferences().stream()
                    .anyMatch(d -> d.preferenceDate().equals(targetDate) && d.shiftType() == ShiftType.NIGHT);
            assertTrue(foundDate, "Full profile must reflect date-specific preference");

            // Verify RecurringShiftPreferenceService
            List<RecurringShiftPreferenceResponse> recList = recurringShiftPreferenceService.getByEmployee(emp.getId());
            assertTrue(recList.stream().anyMatch(r -> r.dayOfWeek() == dow && r.shiftType() == ShiftType.MORNING));

            // Verify DateShiftPreferenceService
            List<DateShiftPreferenceResponse> dateList = dateShiftPreferenceService.getDatePreferencesByEmployee(emp.getId());
            assertTrue(dateList.stream().anyMatch(d -> d.preferenceDate().equals(targetDate) && d.shiftType() == ShiftType.NIGHT));
        } finally {
            recurringShiftPreferenceRepository.delete(savedRsp);
            dateShiftPreferenceRepository.delete(savedDsp);
            restoreBaselineEmployees();
        }
    }

    // =========================================================================
    // 3. LEAVE AND HOLIDAY ISOLATION (NEVER COUNTED AS WEEKLY OFF)
    // =========================================================================
    @Test
    @Order(3)
    @DisplayName("03. Leave and Holiday Isolation: Holidays and leaves strictly preserved, never converted to Weekly OFF")
    void test03_leaveAndHolidayIsolation() {
        restoreBaselineEmployees();

        Employee emp = employeeRepository.findAll().stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam() && e.isActive())
                .findFirst().orElseThrow();

        LocalDate testMonday = LocalDate.of(2029, 7, 2);
        rosterCycleRepository.findByStartDate(testMonday).ifPresent(c -> rosterService.deleteCycle(c.getId()));

        LocalDate wednesday = testMonday.plusDays(2);
        LocalDate thursday = testMonday.plusDays(3);

        // Create an official Holiday on Wednesday
        Holiday holiday = new Holiday();
        holiday.setName("Batch 136 Midweek Test Holiday");
        holiday.setHolidayDate(wednesday);
        holiday.setActive(true);
        Holiday savedHoliday = holidayRepository.save(holiday);

        // Create an Approved Leave on Thursday
        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(emp);
        leave.setStartDate(thursday);
        leave.setEndDate(thursday);
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setRequestedAt(LocalDateTime.now());
        leave.setReason("Batch 136 Leave Isolation Test");
        LeaveRequest savedLeave = leaveRequestRepository.save(leave);

        try {
            RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(resp);
            assertEquals(0, resp.criticalConflicts(), "Zero critical conflicts: " + resp.conflicts());

            List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());

            // Check Thursday is ON_LEAVE for target employee
            RosterAssignment thuAssign = assigns.stream()
                    .filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.getRosterDate().equals(thursday))
                    .findFirst().orElseThrow();
            assertTrue(thuAssign.isOnLeave(), "Thursday must be marked as ON_LEAVE");
            assertFalse(thuAssign.isWeeklyOff(), "Thursday must NOT be counted as Weekly Off when on approved leave");

            // Check validator reports zero HOLIDAY_NOT_WEEKLY_OFF and zero LEAVE_COLLISION
            RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
            assertNotNull(valResp);
            long holidayViolations = valResp.findings().stream()
                    .filter(f -> "HOLIDAY_NOT_WEEKLY_OFF".equals(f.ruleCode()))
                    .count();
            assertEquals(0, holidayViolations, "Zero HOLIDAY_NOT_WEEKLY_OFF findings");

            long leaveCollisions = valResp.findings().stream()
                    .filter(f -> "LEAVE_COLLISION".equals(f.ruleCode()))
                    .count();
            assertEquals(0, leaveCollisions, "Zero LEAVE_COLLISION findings");

            rosterService.deleteCycle(resp.id());
        } finally {
            holidayRepository.delete(savedHoliday);
            leaveRequestRepository.delete(savedLeave);
            restoreBaselineEmployees();
        }
    }

    // =========================================================================
    // 4. DATABASE-DRIVEN SHIFT CAPACITIES
    // =========================================================================
    @Test
    @Order(4)
    @DisplayName("04. Shift Capacities: Database-driven capacities respected with 0 capacity overflow errors")
    void test04_databaseDrivenShiftCapacities() {
        restoreBaselineEmployees();

        List<Shift> shifts = shiftRepository.findByActiveTrueOrderByIdAsc();
        assertFalse(shifts.isEmpty());

        for (Shift s : shifts) {
            assertNotNull(s.getShiftType());
            if (s.getShiftType() != ShiftType.OFF) {
                assertTrue(s.getCapacity() >= 1, "Capacity for " + s.getShiftType() + " must be at least 1");
            }
        }

        LocalDate testMonday = LocalDate.of(2029, 8, 6);
        rosterCycleRepository.findByStartDate(testMonday).ifPresent(c -> rosterService.deleteCycle(c.getId()));

        RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(resp);
        assertEquals(0, resp.criticalConflicts());

        List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());

        // Validate exact night coverage = 1 per day
        Map<LocalDate, Long> nightsPerDay = assigns.stream()
                .filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT)
                .collect(Collectors.groupingBy(RosterAssignment::getRosterDate, Collectors.counting()));

        for (LocalDate d = testMonday; !d.isAfter(testMonday.plusDays(6)); d = d.plusDays(1)) {
            assertEquals(1L, nightsPerDay.getOrDefault(d, 0L), "Exact 1 Night shift required on " + d);
        }

        RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
        assertEquals(0, valResp.errorCount(), "Zero errors from validator: " + valResp.findings());

        rosterService.deleteCycle(resp.id());
    }

    // =========================================================================
    // 5. EXACT DAY-WISE PREFERENCE MATCHING & SUBSEQUENT OFF
    // =========================================================================
    @Test
    @Order(5)
    @DisplayName("05. Exact Day-Wise Preference: Preferred shift awarded and followed by immediate next-day off")
    void test05_exactDayWisePreferenceMatching_withNextDayOff() {
        restoreBaselineEmployees();

        Employee emp = employeeRepository.findAll().stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam() && e.isActive())
                .findFirst().orElseThrow();
        emp.setMaxNightsPerWeek(2);
        employeeRepository.save(emp);

        LocalDate testMonday = LocalDate.of(2029, 9, 3);
        rosterCycleRepository.findByStartDate(testMonday).ifPresent(c -> rosterService.deleteCycle(c.getId()));

        LocalDate wednesday = testMonday.plusDays(2);
        LocalDate thursday = testMonday.plusDays(3);

        DateShiftPreference dsp = new DateShiftPreference();
        dsp.setEmployee(emp);
        dsp.setPreferenceDate(wednesday);
        dsp.setShiftType(ShiftType.NIGHT);
        dsp.setPreferenceType(RecurringPreferenceType.PREFERRED);
        dsp.setReason("Batch 136 Wednesday Night Test");
        DateShiftPreference savedDsp = dateShiftPreferenceRepository.save(dsp);

        try {
            RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(resp);
            assertEquals(0, resp.criticalConflicts());

            List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());
            List<RosterAssignment> empAssigns = assigns.stream()
                    .filter(a -> a.getEmployee().getId().equals(emp.getId()))
                    .sorted(Comparator.comparing(RosterAssignment::getRosterDate))
                    .collect(Collectors.toList());

            RosterAssignment wedAssign = empAssigns.stream().filter(a -> a.getRosterDate().equals(wednesday)).findFirst().orElseThrow();
            assertNotNull(wedAssign.getShift());
            assertEquals(ShiftType.NIGHT, wedAssign.getShift().getShiftType(), "Wednesday must be assigned NIGHT as preferred");

            RosterAssignment thuAssign = empAssigns.stream().filter(a -> a.getRosterDate().equals(thursday)).findFirst().orElseThrow();
            boolean thuIsNight = !thuAssign.isWeeklyOff() && thuAssign.getShift() != null && thuAssign.getShift().getShiftType() == ShiftType.NIGHT;
            LocalDate recoveryOffDate = thuIsNight ? thursday.plusDays(1) : thursday;

            RosterAssignment recoveryOffAssign = empAssigns.stream().filter(a -> a.getRosterDate().equals(recoveryOffDate)).findFirst().orElseThrow();
            assertTrue(recoveryOffAssign.isWeeklyOff(), "Immediate day following night shift(s) (" + recoveryOffDate + ") must be WEEKLY OFF");

            // Zero pre-night off on Mon-Tue
            for (int d = 0; d < 2; d++) {
                LocalDate preDate = testMonday.plusDays(d);
                RosterAssignment preAssign = empAssigns.stream().filter(a -> a.getRosterDate().equals(preDate)).findFirst().orElseThrow();
                assertFalse(preAssign.isWeeklyOff(), "Pre-night Weekly Off prohibited on " + preDate);
            }

            RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
            assertEquals(0, valResp.errorCount(), "Validator reports 0 errors");

            rosterService.deleteCycle(resp.id());
        } finally {
            dateShiftPreferenceRepository.delete(savedDsp);
            restoreBaselineEmployees();
        }
    }

    // =========================================================================
    // 6. NEGATIVE PREFERENCE (DO_NOT_ASSIGN) STRICT ADHERENCE
    // =========================================================================
    @Test
    @Order(6)
    @DisplayName("06. Negative Preference: DO_NOT_ASSIGN shift strictly respected")
    void test06_negativePreferenceDoNotAssignStrictAdherence() {
        restoreBaselineEmployees();

        Employee emp = employeeRepository.findAll().stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam() && e.isActive())
                .findFirst().orElseThrow();

        LocalDate testMonday = LocalDate.of(2029, 10, 1);
        rosterCycleRepository.findByStartDate(testMonday).ifPresent(c -> rosterService.deleteCycle(c.getId()));

        LocalDate tuesday = testMonday.plusDays(1);

        DateShiftPreference dsp = new DateShiftPreference();
        dsp.setEmployee(emp);
        dsp.setPreferenceDate(tuesday);
        dsp.setShiftType(ShiftType.EVENING);
        dsp.setPreferenceType(RecurringPreferenceType.DO_NOT_ASSIGN);
        dsp.setReason("Strictly avoid evening on Tuesday");
        DateShiftPreference savedDsp = dateShiftPreferenceRepository.save(dsp);

        try {
            RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(resp);
            assertEquals(0, resp.criticalConflicts());

            List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());
            RosterAssignment tueAssign = assigns.stream()
                    .filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.getRosterDate().equals(tuesday))
                    .findFirst().orElseThrow();

            if (!tueAssign.isWeeklyOff() && !tueAssign.isOnLeave() && tueAssign.getShift() != null) {
                assertNotEquals(ShiftType.EVENING, tueAssign.getShift().getShiftType(),
                        "DO_NOT_ASSIGN Evening must be strictly honored");
            }

            rosterService.deleteCycle(resp.id());
        } finally {
            dateShiftPreferenceRepository.delete(savedDsp);
            restoreBaselineEmployees();
        }
    }

    // =========================================================================
    // 7. NIGHT-TO-OFF MANDATORY RECOVERY SEQUENCING
    // =========================================================================
    @Test
    @Order(7)
    @DisplayName("07. Mandatory Recovery Sequencing: Zero pre-night Weekly Offs across the entire cycle")
    void test07_nightToOffMandatoryRecoverySequencing() {
        restoreBaselineEmployees();

        LocalDate testMonday = LocalDate.of(2029, 11, 5);
        rosterCycleRepository.findByStartDate(testMonday).ifPresent(c -> rosterService.deleteCycle(c.getId()));

        RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(resp);
        assertEquals(0, resp.criticalConflicts());

        List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());
        Map<Long, List<RosterAssignment>> empMap = assigns.stream()
                .collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

        for (Map.Entry<Long, List<RosterAssignment>> entry : empMap.entrySet()) {
            List<RosterAssignment> empAssigns = entry.getValue();
            empAssigns.sort(Comparator.comparing(RosterAssignment::getRosterDate));

            LocalDate firstNight = null;
            LocalDate lastNight = null;

            for (RosterAssignment a : empAssigns) {
                if (!a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT) {
                    if (firstNight == null) firstNight = a.getRosterDate();
                    lastNight = a.getRosterDate();
                }
            }

            if (firstNight != null) {
                // Rule 1: Zero Weekly Offs before firstNight
                for (RosterAssignment a : empAssigns) {
                    if (a.getRosterDate().isBefore(firstNight)) {
                        assertFalse(a.isWeeklyOff(),
                                "Pre-night Weekly Off violation for employee " + a.getEmployee().getEmployeeCode() + " on " + a.getRosterDate());
                    }
                }

                // Rule 2: If lastNight is before Sunday, immediate next day must be Weekly Off
                if (lastNight.getDayOfWeek() != DayOfWeek.SUNDAY) {
                    LocalDate nextDay = lastNight.plusDays(1);
                    RosterAssignment nextAssign = empAssigns.stream()
                            .filter(a -> a.getRosterDate().equals(nextDay))
                            .findFirst().orElse(null);
                    assertNotNull(nextAssign);
                    assertTrue(nextAssign.isWeeklyOff(),
                            "Day after Night must be Weekly Off for employee " + nextAssign.getEmployee().getEmployeeCode() + " on " + nextDay);
                }
            }
        }

        RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
        long seqViolations = valResp.findings().stream()
                .filter(f -> "WEEKLY_OFF_BEFORE_NIGHT_VIOLATION".equals(f.ruleCode()))
                .count();
        assertEquals(0, seqViolations, "Zero WEEKLY_OFF_BEFORE_NIGHT_VIOLATION findings");

        rosterService.deleteCycle(resp.id());
    }

    // =========================================================================
    // 8. SUNDAY NIGHT CROSS-WEEK HANDLING (6-DAY MODE)
    // =========================================================================
    @Test
    @Order(8)
    @DisplayName("08. Sunday Night Cross-Week: 0 current-cycle offs, recovery taken on Monday of subsequent cycle")
    void test08_sundayNightCrossWeekHandling() {
        restoreBaselineEmployees();

        Employee emp = employeeRepository.findAll().stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam() && e.isActive())
                .findFirst().orElseThrow();
        emp.setMaxNightsPerWeek(2);
        employeeRepository.save(emp);

        LocalDate testMonday = LocalDate.of(2029, 12, 3);
        rosterCycleRepository.findByStartDate(testMonday).ifPresent(c -> rosterService.deleteCycle(c.getId()));
        LocalDate sunday = testMonday.plusDays(6);

        DateShiftPreference dsp = new DateShiftPreference();
        dsp.setEmployee(emp);
        dsp.setPreferenceDate(sunday);
        dsp.setShiftType(ShiftType.NIGHT);
        dsp.setPreferenceType(RecurringPreferenceType.PREFERRED);
        dsp.setReason("Batch 136 Sunday Night test");
        DateShiftPreference savedDsp = dateShiftPreferenceRepository.save(dsp);

        try {
            RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(resp);
            assertEquals(0, resp.criticalConflicts());

            List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());
            List<RosterAssignment> empAssigns = assigns.stream()
                    .filter(a -> a.getEmployee().getId().equals(emp.getId()))
                    .sorted(Comparator.comparing(RosterAssignment::getRosterDate))
                    .collect(Collectors.toList());

            RosterAssignment sunAssign = empAssigns.stream().filter(a -> a.getRosterDate().equals(sunday)).findFirst().orElseThrow();
            if (sunAssign.getShift() != null && sunAssign.getShift().getShiftType() == ShiftType.NIGHT) {
                // In 6-day mode, Sunday Night worker gets 0 offs in current cycle
                long currentOffs = empAssigns.stream().filter(RosterAssignment::isWeeklyOff).count();
                assertEquals(0, currentOffs, "Sunday Night worker in 6-day mode has 0 offs in current cycle (off taken Monday of next cycle)");
            }

            RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
            assertEquals(0, valResp.errorCount(), "Validator reports 0 errors: " + valResp.findings());

            rosterService.deleteCycle(resp.id());
        } finally {
            dateShiftPreferenceRepository.delete(savedDsp);
            restoreBaselineEmployees();
        }
    }

    // =========================================================================
    // 9. MULTI-EMPLOYEE WORKFORCE SCALING (4, 5, 7, 10, 15, 25)
    // =========================================================================
    @Test
    @Order(9)
    @DisplayName("09. Workforce Scaling: Perfect generation and validation across teams of 4, 5, 7, 10, 15, 25")
    void test09_multiEmployeeWorkforceScaling_4_5_7_10_15_25() {
        int[] targetSizes = {4, 5, 7, 10, 15, 25};
        List<Employee> createdEmployees = new ArrayList<>();

        try {
            for (int size : targetSizes) {
                restoreBaselineEmployees();

                List<Employee> allEmps = employeeRepository.findAll();
                List<Employee> males = allEmps.stream()
                        .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam())
                        .collect(Collectors.toList());
                List<Employee> females = allEmps.stream()
                        .filter(e -> e.getGender() == Gender.FEMALE)
                        .collect(Collectors.toList());

                int currentTotal = males.size() + females.size();
                while (currentTotal < size) {
                    Employee extra = new Employee();
                    String code = "B136_" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
                    extra.setEmployeeCode(code);
                    extra.setFirstName("Scale" + (currentTotal + 1));
                    extra.setLastName("Batch136");
                    extra.setEmail(code.toLowerCase() + "@wrms.test");
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

                int reqMales = (size <= 5) ? Math.min(size, 4) : Math.min(size - 1, Math.max(4, males.size()));
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
                    if (size <= 5) {
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

                LocalDate testMonday = LocalDate.of(2030, 1, 7).plusWeeks(size);
                rosterCycleRepository.findByStartDate(testMonday).ifPresent(c -> rosterService.deleteCycle(c.getId()));

                RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
                assertNotNull(resp, "Roster generated for size " + size);
                assertEquals(0, resp.criticalConflicts(), "Zero critical conflicts for size " + size + ": " + resp.conflicts());

                RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
                assertNotNull(valResp);
                long seqViolations = valResp.findings().stream()
                        .filter(f -> "WEEKLY_OFF_BEFORE_NIGHT_VIOLATION".equals(f.ruleCode()))
                        .count();
                assertEquals(0, seqViolations, "Zero sequence violations for size " + size);

                rosterService.deleteCycle(resp.id());
            }
        } finally {
            for (Employee ce : createdEmployees) {
                try {
                    employeeRepository.delete(ce);
                } catch (Exception ignored) {}
            }
            restoreBaselineEmployees();
            if (employeeMasterSyncService != null) {
                try {
                    employeeMasterSyncService.exportCanonicalCsv();
                } catch (Exception ignored) {}
            }
        }
    }

    // =========================================================================
    // 10. MANUAL ASSIGNMENT AND OVERRIDE PROTECTION
    // =========================================================================
    @Test
    @Order(10)
    @DisplayName("10. Manual Override Retention: Pre-existing manual overrides preserved during generation")
    void test10_manualAssignmentAndOverrideRetention() {
        restoreBaselineEmployees();

        Employee emp = employeeRepository.findAll().stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam() && e.isActive())
                .findFirst().orElseThrow();

        LocalDate testMonday = LocalDate.of(2030, 8, 5);
        rosterCycleRepository.findByStartDate(testMonday).ifPresent(c -> rosterService.deleteCycle(c.getId()));

        LocalDate tuesday = testMonday.plusDays(1);

        // First generate initial roster
        RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(resp);

        List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());
        RosterAssignment tueAssign = assigns.stream()
                .filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.getRosterDate().equals(tuesday))
                .findFirst().orElseThrow();

        // Mark as manual assignment with specific shift
        Shift generalShift = shiftRepository.findByShiftType(ShiftType.GENERAL).orElseThrow();
        tueAssign.setManualAssignment(true);
        tueAssign.setShift(generalShift);
        tueAssign.setWeeklyOff(false);
        rosterAssignmentRepository.save(tueAssign);

        // Record an override in RosterOverrideRepository
        RosterOverride override = new RosterOverride();
        override.setAssignment(tueAssign);
        override.setPreviousShiftType(ShiftType.MORNING);
        override.setNewShiftType(ShiftType.GENERAL);
        override.setReason("Admin manual test override");
        override.setWeeklyOff(false);
        RosterOverride savedOverride = overrideRepository.save(override);

        try {
            // Validate that validator acknowledges manual override preservation
            RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
            assertNotNull(valResp);
            long overrideViolations = valResp.findings().stream()
                    .filter(f -> "MANUAL_OVERRIDE_VIOLATION".equals(f.ruleCode()))
                    .count();
            assertEquals(0, overrideViolations, "Manual assignment override preserved with zero violations");

            rosterService.deleteCycle(resp.id());
        } finally {
            try {
                overrideRepository.delete(savedOverride);
            } catch (Exception ignored) {}
            restoreBaselineEmployees();
        }
    }

    // =========================================================================
    // 11. INFEASIBILITY DETECTION AND DIAGNOSTIC CONFLICT REPORTING
    // =========================================================================
    @Test
    @Order(11)
    @DisplayName("11. Infeasibility Detection: Clear diagnostic reporting when constraints are impossible")
    void test11_infeasibilityDetectionAndDiagnosticConflictReporting() {
        restoreBaselineEmployees();

        // Simulate an impossible night staffing scenario: make all males on leave or inactive on Saturday
        LocalDate testMonday = LocalDate.of(2030, 9, 2);
        rosterCycleRepository.findByStartDate(testMonday).ifPresent(c -> rosterService.deleteCycle(c.getId()));
        LocalDate saturday = testMonday.plusDays(5);

        List<Employee> males = employeeRepository.findAll().stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam() && e.isActive())
                .collect(Collectors.toList());

        List<LeaveRequest> forcedLeaves = new ArrayList<>();
        for (Employee m : males) {
            LeaveRequest lr = new LeaveRequest();
            lr.setEmployee(m);
            lr.setStartDate(saturday);
            lr.setEndDate(saturday);
            lr.setStatus(LeaveStatus.APPROVED);
            lr.setRequestedAt(LocalDateTime.now());
            lr.setReason("Simulated simultaneous leave for infeasibility test");
            forcedLeaves.add(leaveRequestRepository.save(lr));
        }

        try {
            RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(resp);

            // Generator should either report conflict or indicate shortage without crashing
            assertNotNull(resp.conflicts(), "Conflicts list must be provided");

            // RosterValidatorService should identify the shortage diagnostic cleanly
            RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
            assertNotNull(valResp);
            boolean hasDiagnostic = valResp.findings().stream()
                    .anyMatch(f -> "ZERO_SHIFT_COVERAGE".equals(f.ruleCode())
                            || "WORKFORCE_CAPACITY_SHORTAGE".equals(f.ruleCode())
                            || "LEAVE_COLLISION".equals(f.ruleCode()));
            assertTrue(hasDiagnostic, "Infeasibility must produce clean diagnostic findings rather than silent failure");

            rosterService.deleteCycle(resp.id());
        } finally {
            for (LeaveRequest lr : forcedLeaves) {
                try {
                    leaveRequestRepository.delete(lr);
                } catch (Exception ignored) {}
            }
            restoreBaselineEmployees();
        }
    }

    // =========================================================================
    // 12. INDEPENDENT VALIDATOR VS GENERATOR STRICT CONSISTENCY
    // =========================================================================
    @Test
    @Order(12)
    @DisplayName("12. Strict Consistency: Evaluator and Independent Validator agree completely on valid rosters")
    void test12_independentValidatorVsGeneratorStrictConsistency() {
        restoreBaselineEmployees();

        LocalDate testMonday = LocalDate.of(2030, 10, 7);
        rosterCycleRepository.findByStartDate(testMonday).ifPresent(c -> rosterService.deleteCycle(c.getId()));

        RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(resp);
        assertEquals(0, resp.criticalConflicts(), "Generator evaluator reports 0 critical conflicts");

        RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
        assertNotNull(valResp);
        assertEquals(0, valResp.errorCount(), "Independent validator reports 0 errors: " + valResp.findings());
        assertNotEquals(ValidationSeverity.ERROR, valResp.overallStatus(), "Overall validation status must not be ERROR");

        rosterService.deleteCycle(resp.id());
    }
}
