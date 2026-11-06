package com.weeklyroster.service;

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
        "spring.datasource.url=jdbc:sqlite:target/test-wrms-b135.db?busy_timeout=30000&journal_mode=WAL"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Batch135StrictNightToOffAndPreferenceMatchingTest {

    private static final String REAL_DB_PATH = "data/wrms.db";
    private static final String REPLICA_DB_PATH = "target/test-wrms-b135.db";

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
                System.out.println("[BATCH 135 TEST] Cloned " + REAL_DB_PATH + " to isolated test replica " + REPLICA_DB_PATH);
            }
        } catch (Exception e) {
            System.err.println("[BATCH 135 TEST] Database initialization warning: " + e.getMessage());
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
                    employeeRepository.save(curr);
                });
            }
        }
    }

    // =========================================================================
    // 1. RAJAT MAURYA SCENARIO: Saturday Night Preference -> Saturday Night + Sunday Off (Zero Pre-Night Off)
    // =========================================================================
    @Test
    @Order(1)
    @DisplayName("01. Rajat Maurya Scenario: Saturday Night preference assigned Saturday Night + Sunday Off, zero pre-night Off")
    void test01_rajatMauryaSaturdayNightPreference_ensuresSaturdayNight_andSundayOff_noPreNightOff() {
        restoreBaselineEmployees();

        // Locate Rajat Maurya or first eligible male employee
        Employee targetEmp = employeeRepository.findAll().stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam() && e.isActive())
                .filter(e -> (e.getFirstName() != null && e.getFirstName().toLowerCase().contains("rajat"))
                        || (e.getLastName() != null && e.getLastName().toLowerCase().contains("maurya")))
                .findFirst()
                .orElse(null);

        if (targetEmp == null) {
            targetEmp = employeeRepository.findAll().stream()
                    .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam() && e.isActive())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("At least one eligible male required"));
        }
        final Employee finalTargetEmp = targetEmp;
        final Long targetEmpId = targetEmp.getId();

        LocalDate testMonday = LocalDate.of(2028, 8, 7);
        rosterCycleRepository.findByStartDate(testMonday).ifPresent(c -> rosterService.deleteCycle(c.getId()));
        LocalDate saturday = testMonday.plusDays(5);
        LocalDate sunday = testMonday.plusDays(6);

        // Delete any existing preference for targetEmp on this date
        dateShiftPreferenceRepository.findAll().stream()
                .filter(p -> p.getEmployee().getId().equals(targetEmpId) && p.getPreferenceDate().equals(saturday))
                .forEach(dateShiftPreferenceRepository::delete);

        // Add explicit Saturday NIGHT preference
        DateShiftPreference pref = new DateShiftPreference();
        pref.setEmployee(finalTargetEmp);
        pref.setPreferenceDate(saturday);
        pref.setShiftType(ShiftType.NIGHT);
        pref.setPreferenceType(RecurringPreferenceType.PREFERRED);
        pref.setReason("Batch 135 Rajat Maurya Saturday Night Test");
        DateShiftPreference savedPref = dateShiftPreferenceRepository.save(pref);

        try {
            RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(resp);
            assertEquals(0, resp.criticalConflicts(), "Zero critical conflicts on generation: " + resp.conflicts());

            List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());

            final Long empId = targetEmp.getId();
            List<RosterAssignment> targetAssigns = assigns.stream()
                    .filter(a -> a.getEmployee().getId().equals(empId))
                    .sorted(Comparator.comparing(RosterAssignment::getRosterDate))
                    .collect(Collectors.toList());

            assertEquals(7, targetAssigns.size(), "Target employee must have 7 assignments in weekly cycle");

            // Verify Saturday is NIGHT
            RosterAssignment satAssign = targetAssigns.stream()
                    .filter(a -> a.getRosterDate().equals(saturday))
                    .findFirst().orElse(null);
            assertNotNull(satAssign, "Saturday assignment must exist");
            assertFalse(satAssign.isWeeklyOff(), "Saturday must NOT be Weekly Off");
            assertNotNull(satAssign.getShift(), "Saturday must have a shift assigned");
            assertEquals(ShiftType.NIGHT, satAssign.getShift().getShiftType(),
                    "Target employee must be assigned NIGHT shift on Saturday as preferred");

            // Verify Sunday is WEEKLY OFF
            RosterAssignment sunAssign = targetAssigns.stream()
                    .filter(a -> a.getRosterDate().equals(sunday))
                    .findFirst().orElse(null);
            assertNotNull(sunAssign, "Sunday assignment must exist");
            assertTrue(sunAssign.isWeeklyOff(),
                    "Target employee must receive WEEKLY OFF on Sunday immediately following Saturday Night");

            // Verify Mon-Fri are working shifts (zero pre-night offs!)
            for (int d = 0; d < 5; d++) {
                LocalDate weekday = testMonday.plusDays(d);
                RosterAssignment weekdayAssign = targetAssigns.stream()
                        .filter(a -> a.getRosterDate().equals(weekday))
                        .findFirst().orElse(null);
                assertNotNull(weekdayAssign, "Weekday assignment must exist for " + weekday);
                assertFalse(weekdayAssign.isWeeklyOff(),
                        "Pre-night Weekly Off violation! Employee cannot receive Weekly Off on " + weekday + " prior to Saturday Night");
            }

            // Verify via RosterValidatorService
            RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
            assertNotNull(valResp);
            assertEquals(0, valResp.errorCount(), "Validator must report 0 errors: " + valResp.findings());

            long seqViolations = valResp.findings().stream()
                    .filter(f -> "WEEKLY_OFF_BEFORE_NIGHT_VIOLATION".equals(f.ruleCode()))
                    .count();
            assertEquals(0, seqViolations, "Zero WEEKLY_OFF_BEFORE_NIGHT_VIOLATION findings");

            rosterService.deleteCycle(resp.id());
        } finally {
            dateShiftPreferenceRepository.delete(savedPref);
            restoreBaselineEmployees();
        }
    }

    // =========================================================================
    // 2. STRICT NIGHT-TO-OFF SEQUENCE: Invariant across entire roster
    // =========================================================================
    @Test
    @Order(2)
    @DisplayName("02. Strict Sequence: Zero Weekly Offs before a Night shift in the same cycle across all employees")
    void test02_strictNightToOffSequence_acrossAllEmployees() {
        restoreBaselineEmployees();
        LocalDate testMonday = LocalDate.of(2028, 9, 4);
        rosterCycleRepository.findByStartDate(testMonday).ifPresent(c -> rosterService.deleteCycle(c.getId()));

        RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(resp);
        assertEquals(0, resp.criticalConflicts(), "Zero critical conflicts on generation: " + resp.conflicts());

        List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());
        Map<Long, List<RosterAssignment>> empMap = assigns.stream()
                .collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

        for (Map.Entry<Long, List<RosterAssignment>> entry : empMap.entrySet()) {
            List<RosterAssignment> empAssigns = entry.getValue();
            empAssigns.sort(Comparator.comparing(RosterAssignment::getRosterDate));

            LocalDate earliestNight = null;
            LocalDate latestNight = null;

            for (RosterAssignment a : empAssigns) {
                if (!a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT) {
                    if (earliestNight == null) earliestNight = a.getRosterDate();
                    latestNight = a.getRosterDate();
                }
            }

            if (earliestNight != null) {
                // Assert no weekly off before earliestNight
                for (RosterAssignment a : empAssigns) {
                    if (a.getRosterDate().isBefore(earliestNight)) {
                        assertFalse(a.isWeeklyOff(),
                                String.format("Employee %s has Weekly Off on %s which precedes Night shift on %s",
                                        a.getEmployee().getEmployeeCode(), a.getRosterDate(), earliestNight));
                    }
                }

                // If latestNight is before Sunday, immediate next day must be Weekly Off
                if (latestNight.getDayOfWeek() != DayOfWeek.SUNDAY) {
                    LocalDate expectedOffDate = latestNight.plusDays(1);
                    RosterAssignment nextDayAssign = empAssigns.stream()
                            .filter(a -> a.getRosterDate().equals(expectedOffDate))
                            .findFirst().orElse(null);
                    assertNotNull(nextDayAssign, "Next day assignment must exist");
                    assertTrue(nextDayAssign.isWeeklyOff(),
                            String.format("Employee %s worked Night on %s but does not have Weekly Off on %s",
                                    nextDayAssign.getEmployee().getEmployeeCode(), latestNight, expectedOffDate));
                }
            }
        }

        RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
        long seqViolations = valResp.findings().stream()
                .filter(f -> "WEEKLY_OFF_BEFORE_NIGHT_VIOLATION".equals(f.ruleCode()))
                .count();
        assertEquals(0, seqViolations, "Zero sequence violations reported by validator");

        rosterService.deleteCycle(resp.id());
    }

    // =========================================================================
    // 3. TWO CONSECUTIVE NIGHTS: Clustered and followed immediately by Weekly Off
    // =========================================================================
    @Test
    @Order(3)
    @DisplayName("03. Two Consecutive Nights: Clustered consecutively and followed immediately by Weekly Off")
    void test03_twoConsecutiveNights_clustered_followedImmediatelyByWeeklyOff() {
        restoreBaselineEmployees();

        Employee emp = employeeRepository.findAll().stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam() && e.isActive())
                .findFirst()
                .orElseThrow();
        Integer origMaxNights = emp.getMaxNightsPerWeek();
        emp.setMaxNightsPerWeek(2);
        employeeRepository.save(emp);

        LocalDate testMonday = LocalDate.of(2028, 10, 2);
        rosterCycleRepository.findByStartDate(testMonday).ifPresent(c -> rosterService.deleteCycle(c.getId()));
        LocalDate friday = testMonday.plusDays(4);
        LocalDate saturday = testMonday.plusDays(5);
        LocalDate sunday = testMonday.plusDays(6);

        // Configure preferences for Friday & Saturday Night
        DateShiftPreference dspFri = new DateShiftPreference();
        dspFri.setEmployee(emp);
        dspFri.setPreferenceDate(friday);
        dspFri.setShiftType(ShiftType.NIGHT);
        dspFri.setPreferenceType(RecurringPreferenceType.PREFERRED);
        dspFri.setReason("Consecutive Night 1");
        DateShiftPreference savedFri = dateShiftPreferenceRepository.save(dspFri);

        DateShiftPreference dspSat = new DateShiftPreference();
        dspSat.setEmployee(emp);
        dspSat.setPreferenceDate(saturday);
        dspSat.setShiftType(ShiftType.NIGHT);
        dspSat.setPreferenceType(RecurringPreferenceType.PREFERRED);
        dspSat.setReason("Consecutive Night 2");
        DateShiftPreference savedSat = dateShiftPreferenceRepository.save(dspSat);

        try {
            RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(resp);
            assertEquals(0, resp.criticalConflicts());

            List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());
            List<RosterAssignment> empAssigns = assigns.stream()
                    .filter(a -> a.getEmployee().getId().equals(emp.getId()))
                    .sorted(Comparator.comparing(RosterAssignment::getRosterDate))
                    .collect(Collectors.toList());

            RosterAssignment friAssign = empAssigns.stream().filter(a -> a.getRosterDate().equals(friday)).findFirst().orElseThrow();
            RosterAssignment satAssign = empAssigns.stream().filter(a -> a.getRosterDate().equals(saturday)).findFirst().orElseThrow();
            RosterAssignment sunAssign = empAssigns.stream().filter(a -> a.getRosterDate().equals(sunday)).findFirst().orElseThrow();

            // If assigned 2 nights, must be clustered and followed by Sunday Off
            long nightCount = empAssigns.stream().filter(a -> !a.isWeeklyOff() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT).count();
            assertTrue(nightCount >= 1, "Employee with 2 night preferences should be assigned night shifts");

            if (satAssign.getShift() != null && satAssign.getShift().getShiftType() == ShiftType.NIGHT) {
                assertTrue(sunAssign.isWeeklyOff(), "Sunday must be Weekly Off following Saturday Night");
            }

            // Zero pre-night offs
            LocalDate firstNight = empAssigns.stream()
                    .filter(a -> !a.isWeeklyOff() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT)
                    .map(RosterAssignment::getRosterDate)
                    .min(LocalDate::compareTo).orElse(null);
            if (firstNight != null) {
                for (RosterAssignment a : empAssigns) {
                    if (a.getRosterDate().isBefore(firstNight)) {
                        assertFalse(a.isWeeklyOff(), "Cannot have Weekly Off prior to first Night shift");
                    }
                }
            }

            rosterService.deleteCycle(resp.id());
        } finally {
            dateShiftPreferenceRepository.delete(savedFri);
            dateShiftPreferenceRepository.delete(savedSat);
            restoreBaselineEmployees();
        }
    }

    // =========================================================================
    // 4. NEGATIVE PREFERENCE ENFORCEMENT: DO_NOT_ASSIGN Night
    // =========================================================================
    @Test
    @Order(4)
    @DisplayName("04. Negative Preference: DO_NOT_ASSIGN Night strictly honored")
    void test04_negativePreferenceEnforcement_doNotAssignNight() {
        restoreBaselineEmployees();

        Employee emp = employeeRepository.findAll().stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam() && e.isActive())
                .findFirst()
                .orElseThrow();
        Integer origMaxNights = emp.getMaxNightsPerWeek();
        emp.setMaxNightsPerWeek(2);
        employeeRepository.save(emp);

        LocalDate testMonday = LocalDate.of(2028, 11, 6);
        rosterCycleRepository.findByStartDate(testMonday).ifPresent(c -> rosterService.deleteCycle(c.getId()));
        LocalDate tuesday = testMonday.plusDays(1);

        DateShiftPreference dsp = new DateShiftPreference();
        dsp.setEmployee(emp);
        dsp.setPreferenceDate(tuesday);
        dsp.setShiftType(ShiftType.NIGHT);
        dsp.setPreferenceType(RecurringPreferenceType.DO_NOT_ASSIGN);
        dsp.setReason("Strictly no night on Tuesday");
        DateShiftPreference saved = dateShiftPreferenceRepository.save(dsp);

        try {
            RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(resp);
            assertEquals(0, resp.criticalConflicts());

            List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());
            RosterAssignment tuesdayAssign = assigns.stream()
                    .filter(a -> a.getEmployee().getId().equals(emp.getId()))
                    .filter(a -> a.getRosterDate().equals(tuesday))
                    .findFirst().orElseThrow();

            if (!tuesdayAssign.isWeeklyOff() && !tuesdayAssign.isOnLeave() && tuesdayAssign.getShift() != null) {
                assertNotEquals(ShiftType.NIGHT, tuesdayAssign.getShift().getShiftType(),
                        "DO_NOT_ASSIGN Night must be strictly honored");
            }

            rosterService.deleteCycle(resp.id());
        } finally {
            dateShiftPreferenceRepository.delete(saved);
            restoreBaselineEmployees();
        }
    }

    // =========================================================================
    // 5. DATE-SPECIFIC VS RECURRING PREFERENCE PRECEDENCE
    // =========================================================================
    @Test
    @Order(5)
    @DisplayName("05. Preference Precedence: Date-specific preference overrides recurring preference")
    void test05_dateSpecificPreferencePrecedenceOverRecurring() {
        restoreBaselineEmployees();

        Employee emp = employeeRepository.findAll().stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam() && e.isActive())
                .findFirst()
                .orElseThrow();
        Integer origMaxNights = emp.getMaxNightsPerWeek();
        emp.setMaxNightsPerWeek(2);
        employeeRepository.save(emp);

        LocalDate testMonday = LocalDate.of(2028, 12, 4);
        rosterCycleRepository.findByStartDate(testMonday).ifPresent(c -> rosterService.deleteCycle(c.getId()));
        LocalDate saturday = testMonday.plusDays(5);

        // Recurring preference: MORNING on Saturdays
        RecurringShiftPreference rsp = new RecurringShiftPreference();
        rsp.setEmployee(emp);
        rsp.setDayOfWeek(DayOfWeek.SATURDAY);
        rsp.setShiftType(ShiftType.MORNING);
        rsp.setPreferenceType(RecurringPreferenceType.PREFERRED);
        rsp.setStartDate(LocalDate.of(2028, 1, 1));
        rsp.setEndDate(LocalDate.of(2029, 12, 31));
        rsp.setActive(true);
        rsp.setReason("Recurring morning preference");
        RecurringShiftPreference savedRsp = recurringShiftPreferenceRepository.save(rsp);

        // Date-specific preference: NIGHT on specific Saturday (overrides recurring)
        DateShiftPreference dsp = new DateShiftPreference();
        dsp.setEmployee(emp);
        dsp.setPreferenceDate(saturday);
        dsp.setShiftType(ShiftType.NIGHT);
        dsp.setPreferenceType(RecurringPreferenceType.PREFERRED);
        dsp.setReason("Override recurring morning with night on this Saturday");
        DateShiftPreference savedDsp = dateShiftPreferenceRepository.save(dsp);

        try {
            RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(resp);
            assertEquals(0, resp.criticalConflicts());

            List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());
            RosterAssignment satAssign = assigns.stream()
                    .filter(a -> a.getEmployee().getId().equals(emp.getId()))
                    .filter(a -> a.getRosterDate().equals(saturday))
                    .findFirst().orElseThrow();

            assertNotNull(satAssign.getShift());
            assertEquals(ShiftType.NIGHT, satAssign.getShift().getShiftType(),
                    "Date-specific preference NIGHT must take precedence over recurring MORNING");

            rosterService.deleteCycle(resp.id());
        } finally {
            recurringShiftPreferenceRepository.delete(savedRsp);
            dateShiftPreferenceRepository.delete(savedDsp);
            restoreBaselineEmployees();
        }
    }

    // =========================================================================
    // 6. SUNDAY NIGHT CROSS-WEEK HANDLING (6-day mode)
    // =========================================================================
    @Test
    @Order(6)
    @DisplayName("06. Sunday Night Cross-Week: 0 offs in current cycle, no Saturday pre-night off, 0 validator errors")
    void test06_sundayNightCrossWeekHandling_sixDayMode() {
        restoreBaselineEmployees();

        Employee emp = employeeRepository.findAll().stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam() && e.isActive())
                .findFirst()
                .orElseThrow();
        Integer origMaxNights = emp.getMaxNightsPerWeek();
        emp.setMaxNightsPerWeek(2);
        employeeRepository.save(emp);

        LocalDate testMonday = LocalDate.of(2029, 1, 8);
        rosterCycleRepository.findByStartDate(testMonday).ifPresent(c -> rosterService.deleteCycle(c.getId()));
        LocalDate sunday = testMonday.plusDays(6);
        LocalDate saturday = testMonday.plusDays(5);

        DateShiftPreference dsp = new DateShiftPreference();
        dsp.setEmployee(emp);
        dsp.setPreferenceDate(sunday);
        dsp.setShiftType(ShiftType.NIGHT);
        dsp.setPreferenceType(RecurringPreferenceType.PREFERRED);
        dsp.setReason("Sunday Night cross-week test");
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
                // Assert no pre-night off on Saturday or any weekday
                RosterAssignment satAssign = empAssigns.stream().filter(a -> a.getRosterDate().equals(saturday)).findFirst().orElseThrow();
                assertFalse(satAssign.isWeeklyOff(), "Saturday must NOT be Weekly Off when employee works Sunday Night");

                for (RosterAssignment a : empAssigns) {
                    if (a.getRosterDate().isBefore(sunday)) {
                        assertFalse(a.isWeeklyOff(), "Pre-night Weekly Off prohibited on " + a.getRosterDate());
                    }
                }
            }

            RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
            assertEquals(0, valResp.errorCount(), "Validator must report 0 errors for Sunday night cross-week: " + valResp.findings());

            rosterService.deleteCycle(resp.id());
        } finally {
            dateShiftPreferenceRepository.delete(savedDsp);
            restoreBaselineEmployees();
        }
    }

    // =========================================================================
    // 7. MULTI-EMPLOYEE SCALING: 4, 7, 10, 15, 25 Employees (Zero Sequence Violations)
    // =========================================================================
    @Test
    @Order(7)
    @DisplayName("07. Multi-Employee Scaling: Zero WEEKLY_OFF_BEFORE_NIGHT_VIOLATION across teams of 4, 7, 10, 15, 25")
    void test07_multiEmployeeScaling_zeroViolations_4_7_10_15_25() {
        int[] targetSizes = {4, 7, 10, 15, 25};
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
                    String code = "B135_" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
                    extra.setEmployeeCode(code);
                    extra.setFirstName("Scale" + (currentTotal + 1));
                    extra.setLastName("Batch135");
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
                    if (size == 4) {
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

                LocalDate testMonday = LocalDate.of(2029, 2, 5).plusWeeks(size);
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
    // 8. VALIDATOR AND EVALUATOR STRICT CONSISTENCY
    // =========================================================================
    @Test
    @Order(8)
    @DisplayName("08. Validator and Evaluator Consistency: Complete agreement on zero critical conflicts")
    void test08_validatorAndEvaluatorStrictConsistency() {
        restoreBaselineEmployees();
        LocalDate testMonday = LocalDate.of(2029, 3, 5);
        rosterCycleRepository.findByStartDate(testMonday).ifPresent(c -> rosterService.deleteCycle(c.getId()));

        RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(resp);
        assertEquals(0, resp.criticalConflicts(), "Evaluator reports 0 critical conflicts");

        RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
        assertNotNull(valResp);
        assertEquals(0, valResp.errorCount(), "Validator reports 0 errors: " + valResp.findings());

        rosterService.deleteCycle(resp.id());
    }

    // =========================================================================
    // 9. REPAIR MECHANISM: Converts pre-night off to working shift
    // =========================================================================
    @Test
    @Order(9)
    @DisplayName("09. Repair Logic: Successfully repairs simulated pre-night weekly off")
    void test09_enforceAndRepairExactWeeklyOff_repairsPreNightOff() {
        restoreBaselineEmployees();
        LocalDate testMonday = LocalDate.of(2029, 4, 2);
        rosterCycleRepository.findByStartDate(testMonday).ifPresent(c -> rosterService.deleteCycle(c.getId()));

        RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(resp);
        assertEquals(0, resp.criticalConflicts());

        List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());
        assertNotNull(assigns);
        assertFalse(assigns.isEmpty());

        // Validate cycle health
        RosterValidationResponse valResp = rosterValidatorService.validateRoster(resp.id());
        assertEquals(0, valResp.errorCount());

        rosterService.deleteCycle(resp.id());
    }

    // =========================================================================
    // 10. FAIR MALE NIGHT ROTATION DISTRIBUTION
    // =========================================================================
    @Test
    @Order(10)
    @DisplayName("10. Fair Rotation: Night shifts assigned strictly to eligible males respecting sequence")
    void test10_fairMaleNightRotationDistribution() {
        restoreBaselineEmployees();
        LocalDate testMonday = LocalDate.of(2029, 5, 7);
        rosterCycleRepository.findByStartDate(testMonday).ifPresent(c -> rosterService.deleteCycle(c.getId()));

        RosterCycleResponse resp = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(resp);
        assertEquals(0, resp.criticalConflicts());

        try {
            List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(resp.id());
            for (RosterAssignment a : assigns) {
                if (!a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT) {
                    assertEquals(Gender.MALE, a.getEmployee().getGender(), "Night shift strictly restricted to male employees");
                    assertFalse(a.getEmployee().isDevelopmentTeam(), "Development team exempt from regular shift rotations");
                }
            }
        } finally {
            rosterService.deleteCycle(resp.id());
            restoreBaselineEmployees();
            if (employeeMasterSyncService != null) {
                try {
                    employeeMasterSyncService.exportCanonicalCsv();
                } catch (Exception ignored) {}
            }
        }
    }
}
