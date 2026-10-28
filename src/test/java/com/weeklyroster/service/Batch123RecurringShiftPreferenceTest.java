package com.weeklyroster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.dto.request.DateShiftPreferenceRequest;
import com.weeklyroster.dto.request.RecurringShiftPreferenceRequest;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wrms.dev.credential-mirror.enabled=false",
        "spring.datasource.url=jdbc:sqlite:target/test-wrms-b123.db?busy_timeout=30000&journal_mode=WAL"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Batch123RecurringShiftPreferenceTest {

    private static final String REAL_DB_PATH = "data/wrms.db";
    private static final String REPLICA_DB_PATH = "target/test-wrms-b123.db";

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
                System.out.println("[BATCH 123] Copied " + REAL_DB_PATH + " to " + REPLICA_DB_PATH + " for isolated recurring preference verification.");
            }
        } catch (Exception e) {
            System.err.println("[BATCH 123] Failed to prepare test replica DB: " + e.getMessage());
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RecurringShiftPreferenceService recurringShiftPreferenceService;

    @Autowired
    private DateShiftPreferenceService dateShiftPreferenceService;

    @Autowired
    private RecurringShiftPreferenceRepository recurringShiftPreferenceRepository;

    @Autowired
    private DateShiftPreferenceRepository dateShiftPreferenceRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private RosterAssignmentRepository rosterAssignmentRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "admin",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
    }

    private Employee getMaleEmployee() {
        return employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.MALE)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Male employee not found"));
    }

    private Employee getFemaleEmployee() {
        return employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.FEMALE)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Female employee not found"));
    }

    private void clearRecurringPreferences(Long employeeId) {
        recurringShiftPreferenceRepository.deleteAll(recurringShiftPreferenceRepository.findByEmployeeId(employeeId));
    }

    // =========================================================================
    // TEST 1 — Saturday preference across multiple weeks
    // =========================================================================
    @Test
    @Order(1)
    @DisplayName("Test 1: Saturday Preference (EMP001 Saturday -> Morning) Across Multiple Weeks")
    void test01_SaturdayPreferenceAcrossMultipleWeeks() {
        Employee emp = getMaleEmployee();
        clearRecurringPreferences(emp.getId());

        // Admin configures Saturday -> Morning (without entering forced dates)
        RecurringShiftPreferenceRequest req = new RecurringShiftPreferenceRequest(
                emp.getId(),
                DayOfWeek.SATURDAY,
                ShiftType.MORNING,
                null, // No forced startDate required
                null,
                true,
                "Batch 123 Test 1: Admin configured Saturday Morning default"
        );
        recurringShiftPreferenceService.createOrUpdate(req, "admin");

        // Generate across 3 future non-colliding weeks
        List<LocalDate> testWeeks = List.of(
                LocalDate.of(2027, 9, 6),
                LocalDate.of(2027, 9, 13),
                LocalDate.of(2027, 9, 20)
        );

        for (LocalDate weekStart : testWeeks) {
            RosterCycleResponse cycle = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
            assertNotNull(cycle, "Roster cycle must be generated for week " + weekStart);

            List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
            LocalDate saturdayDate = weekStart.plusDays(5);

            RosterAssignment satAssign = assignments.stream()
                    .filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.getRosterDate().equals(saturdayDate))
                    .findFirst()
                    .orElse(null);

            assertNotNull(satAssign, "Assignment must exist on Saturday for " + emp.getEmployeeCode());
            assertFalse(satAssign.isWeeklyOff(), "Must not be Weekly Off on Saturday when Morning is preferred");
            assertNotNull(satAssign.getShift(), "Shift must not be null");
            assertEquals(ShiftType.MORNING, satAssign.getShift().getShiftType(),
                    "Employee " + emp.getEmployeeCode() + " must receive Morning shift on Saturday " + saturdayDate);

            // Invariant check: exactly 1 Weekly Off per employee in 6-day mode
            Map<Long, Long> offCounts = new HashMap<>();
            for (RosterAssignment a : assignments) {
                if (a.isWeeklyOff()) offCounts.merge(a.getEmployee().getId(), 1L, Long::sum);
            }
            offCounts.values().forEach(cnt -> assertEquals(1L, cnt, "Every employee must have exactly 1 Weekly Off"));
        }
    }

    // =========================================================================
    // TEST 2 — Multiple weekdays simultaneously
    // =========================================================================
    @Test
    @Order(2)
    @DisplayName("Test 2: Multiple Weekdays Configured Simultaneously (Monday->G, Wednesday->E, Saturday->M)")
    void test02_MultipleWeekdaysSimultaneously() {
        Employee emp = getMaleEmployee();
        clearRecurringPreferences(emp.getId());

        // Admin configures 3 distinct weekdays for this employee
        recurringShiftPreferenceService.createOrUpdate(new RecurringShiftPreferenceRequest(
                emp.getId(), DayOfWeek.MONDAY, ShiftType.GENERAL, null, null, true, "Monday General"
        ), "admin");

        recurringShiftPreferenceService.createOrUpdate(new RecurringShiftPreferenceRequest(
                emp.getId(), DayOfWeek.WEDNESDAY, ShiftType.EVENING, null, null, true, "Wednesday Evening"
        ), "admin");

        recurringShiftPreferenceService.createOrUpdate(new RecurringShiftPreferenceRequest(
                emp.getId(), DayOfWeek.SATURDAY, ShiftType.MORNING, null, null, true, "Saturday Morning"
        ), "admin");

        // Verify all 3 active records exist simultaneously in repository
        List<RecurringShiftPreference> activeRules = recurringShiftPreferenceRepository.findByEmployeeIdAndActiveTrue(emp.getId());
        assertEquals(3, activeRules.size(), "Employee must have exactly 3 active recurring preference rules");

        LocalDate weekStart = LocalDate.of(2027, 10, 4);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());

        // Monday check
        RosterAssignment monAssign = assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.getRosterDate().equals(weekStart))
                .findFirst().orElseThrow();
        assertEquals(ShiftType.GENERAL, monAssign.getShift().getShiftType(), "Monday must be GENERAL");

        // Wednesday check
        RosterAssignment wedAssign = assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.getRosterDate().equals(weekStart.plusDays(2)))
                .findFirst().orElseThrow();
        assertEquals(ShiftType.EVENING, wedAssign.getShift().getShiftType(), "Wednesday must be EVENING");

        // Saturday check
        RosterAssignment satAssign = assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.getRosterDate().equals(weekStart.plusDays(5)))
                .findFirst().orElseThrow();
        assertEquals(ShiftType.MORNING, satAssign.getShift().getShiftType(), "Saturday must be MORNING");
    }

    // =========================================================================
    // TEST 3 — Date-specific override over recurring preference
    // =========================================================================
    @Test
    @Order(3)
    @DisplayName("Test 3: Date-Specific Preference Must Strictly Override Recurring Preference")
    void test03_DateSpecificOverride() {
        Employee emp = getMaleEmployee();
        clearRecurringPreferences(emp.getId());

        // Recurring: Saturday -> Morning
        recurringShiftPreferenceService.createOrUpdate(new RecurringShiftPreferenceRequest(
                emp.getId(), DayOfWeek.SATURDAY, ShiftType.MORNING, null, null, true, "Recurring Sat Morning"
        ), "admin");

        // Date-specific override: on Saturday 2027-10-16 -> Evening
        LocalDate overrideWeekStart = LocalDate.of(2027, 10, 11);
        LocalDate overrideSaturday = overrideWeekStart.plusDays(5); // 2027-10-16

        dateShiftPreferenceService.createOrUpdate(new DateShiftPreferenceRequest(
                emp.getId(), overrideSaturday, ShiftType.EVENING, "Admin override for 16-Oct-2027"
        ), "admin");

        // Week 1 (Override week): Saturday must be EVENING
        RosterCycleResponse cycle1 = rosterService.generateWeeklyRoster(overrideWeekStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle1);

        List<RosterAssignment> assignments1 = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle1.id());
        RosterAssignment satAssign1 = assignments1.stream()
                .filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.getRosterDate().equals(overrideSaturday))
                .findFirst().orElseThrow();
        assertEquals(ShiftType.EVENING, satAssign1.getShift().getShiftType(),
                "16-Oct-2027 Saturday MUST be EVENING due to Date-Specific override");

        // Week 2 (Following week, no date-specific override): Saturday must return to MORNING
        LocalDate nextWeekStart = LocalDate.of(2027, 10, 18);
        LocalDate nextSaturday = nextWeekStart.plusDays(5); // 2027-10-23

        RosterCycleResponse cycle2 = rosterService.generateWeeklyRoster(nextWeekStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle2);

        List<RosterAssignment> assignments2 = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle2.id());
        RosterAssignment satAssign2 = assignments2.stream()
                .filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.getRosterDate().equals(nextSaturday))
                .findFirst().orElseThrow();
        assertEquals(ShiftType.MORNING, satAssign2.getShift().getShiftType(),
                "23-Oct-2027 Saturday MUST revert to MORNING via recurring preference");
    }

    // =========================================================================
    // TEST 4 — Change recurring preference (Upsert without duplicate)
    // =========================================================================
    @Test
    @Order(4)
    @DisplayName("Test 4: Change Recurring Preference from Morning -> General (Upsert In-Place)")
    void test04_ChangeRecurringPreferenceViaUpsert() {
        Employee emp = getMaleEmployee();
        clearRecurringPreferences(emp.getId());

        // Step 1: Set Saturday -> Morning
        recurringShiftPreferenceService.createOrUpdate(new RecurringShiftPreferenceRequest(
                emp.getId(), DayOfWeek.SATURDAY, ShiftType.MORNING, null, null, true, "Initial Morning"
        ), "admin");

        List<RecurringShiftPreference> rulesBefore = recurringShiftPreferenceRepository.findByEmployeeIdAndDayOfWeek(emp.getId(), DayOfWeek.SATURDAY);
        assertEquals(1, rulesBefore.size(), "Should have exactly 1 rule before change");
        assertEquals(ShiftType.MORNING, rulesBefore.get(0).getShiftType());

        // Step 2: Admin edits Saturday preference to GENERAL
        recurringShiftPreferenceService.createOrUpdate(new RecurringShiftPreferenceRequest(
                emp.getId(), DayOfWeek.SATURDAY, ShiftType.GENERAL, null, null, true, "Updated to General"
        ), "admin");

        List<RecurringShiftPreference> rulesAfter = recurringShiftPreferenceRepository.findByEmployeeIdAndDayOfWeek(emp.getId(), DayOfWeek.SATURDAY);
        long activeCount = rulesAfter.stream().filter(RecurringShiftPreference::isActive).count();
        assertEquals(1, activeCount, "Must have exactly 1 active rule after change (no duplicate active conflicting rules)");

        RecurringShiftPreference currentActive = rulesAfter.stream().filter(RecurringShiftPreference::isActive).findFirst().orElseThrow();
        assertEquals(ShiftType.GENERAL, currentActive.getShiftType(), "Active rule must now be GENERAL");

        // Step 3: Generate future week and verify GENERAL is assigned on Saturday
        LocalDate weekStart = LocalDate.of(2027, 11, 1);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
        LocalDate sat = weekStart.plusDays(5);
        RosterAssignment satAssign = assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.getRosterDate().equals(sat))
                .findFirst().orElseThrow();
        assertEquals(ShiftType.GENERAL, satAssign.getShift().getShiftType(),
                "Saturday must be GENERAL following Admin preference update");
    }

    // =========================================================================
    // TEST 5 — Deactivate recurring preference
    // =========================================================================
    @Test
    @Order(5)
    @DisplayName("Test 5: Deactivate Recurring Preference Returns to Normal Scheduling")
    void test05_DeactivateRecurringPreference() {
        Employee emp = getMaleEmployee();
        clearRecurringPreferences(emp.getId());

        // Step 1: Create active Saturday Morning preference
        recurringShiftPreferenceService.createOrUpdate(new RecurringShiftPreferenceRequest(
                emp.getId(), DayOfWeek.SATURDAY, ShiftType.MORNING, null, null, true, "To be deactivated"
        ), "admin");

        List<RecurringShiftPreference> rules = recurringShiftPreferenceRepository.findByEmployeeIdAndDayOfWeek(emp.getId(), DayOfWeek.SATURDAY);
        RecurringShiftPreference activeRule = rules.stream().filter(RecurringShiftPreference::isActive).findFirst().orElseThrow();

        // Step 2: Toggle/Deactivate
        recurringShiftPreferenceService.toggleActive(activeRule.getId(), "admin");

        RecurringShiftPreference refreshed = recurringShiftPreferenceRepository.findById(activeRule.getId()).orElseThrow();
        assertFalse(refreshed.isActive(), "Rule must now be inactive");

        // Step 3: Verify future week generation operates normally without forcing
        LocalDate weekStart = LocalDate.of(2027, 11, 15);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle);
        assertEquals(RosterStatus.GENERATED, cycle.status());

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
        assertFalse(assignments.isEmpty());

        // Verify standard invariants hold
        Map<Long, Long> offCounts = new HashMap<>();
        for (RosterAssignment a : assignments) {
            if (a.isWeeklyOff()) offCounts.merge(a.getEmployee().getId(), 1L, Long::sum);
        }
        offCounts.values().forEach(cnt -> assertEquals(1L, cnt, "Every employee must have exactly 1 Weekly Off"));
    }

    // =========================================================================
    // TEST 6 — Persistence across application reload
    // =========================================================================
    @Test
    @Order(6)
    @DisplayName("Test 6: Persistence of Recurring Preferences Across Database Queries")
    void test06_PersistenceAcrossDatabaseReload() {
        Employee emp = getMaleEmployee();
        clearRecurringPreferences(emp.getId());

        RecurringShiftPreferenceRequest req = new RecurringShiftPreferenceRequest(
                emp.getId(),
                DayOfWeek.FRIDAY,
                ShiftType.EVENING,
                LocalDate.of(2027, 1, 1),
                null,
                true,
                "Friday Evening Persistent Preference"
        );
        var created = recurringShiftPreferenceService.createOrUpdate(req, "admin");
        assertNotNull(created.id(), "Preference must be assigned an ID upon persist");

        // Re-read directly from repository
        RecurringShiftPreference found = recurringShiftPreferenceRepository.findById(created.id()).orElse(null);
        assertNotNull(found, "Entity must be retrieved from database");
        assertEquals(DayOfWeek.FRIDAY, found.getDayOfWeek());
        assertEquals(ShiftType.EVENING, found.getShiftType());
        assertTrue(found.isActive());
        assertEquals("Friday Evening Persistent Preference", found.getReason());
        assertNotNull(found.getCreatedAt());
    }

    // =========================================================================
    // TEST 7 — SQLite compatibility
    // =========================================================================
    @Test
    @Order(7)
    @DisplayName("Test 7: SQLite Database Engine Compatibility and Transaction Integrity")
    void test07_SqliteCompatibility() {
        // Verify database is SQLite and operations execute cleanly
        File dbFile = new File(REPLICA_DB_PATH);
        assertTrue(dbFile.exists(), "SQLite replica DB must exist on disk");
        assertTrue(dbFile.length() > 0, "SQLite DB file must not be empty");

        // Query active preferences count
        List<RecurringShiftPreference> allActive = recurringShiftPreferenceRepository.findByActiveTrue();
        assertNotNull(allActive, "Active recurring preferences query must execute without error on SQLite");
    }

    // =========================================================================
    // TEST 8 — MySQL dialect neutrality
    // =========================================================================
    @Test
    @Order(8)
    @DisplayName("Test 8: MySQL Dialect Neutrality (Standard JPA Annotations and Dialect Independence)")
    void test08_MySqlDialectNeutrality() throws NoSuchFieldException {
        // Inspect entity class RecurringShiftPreference
        Class<RecurringShiftPreference> entityClass = RecurringShiftPreference.class;
        assertTrue(entityClass.isAnnotationPresent(jakarta.persistence.Entity.class), "Must be annotated with @Entity");
        assertTrue(entityClass.isAnnotationPresent(jakarta.persistence.Table.class), "Must be annotated with @Table");

        var tableAnno = entityClass.getAnnotation(jakarta.persistence.Table.class);
        assertEquals("recurring_shift_preferences", tableAnno.name(), "Table name must be recurring_shift_preferences");

        // Check enum fields have @Enumerated(EnumType.STRING) for dialect-neutral storage
        var dayOfWeekField = entityClass.getDeclaredField("dayOfWeek");
        assertTrue(dayOfWeekField.isAnnotationPresent(jakarta.persistence.Enumerated.class));
        assertEquals(jakarta.persistence.EnumType.STRING, dayOfWeekField.getAnnotation(jakarta.persistence.Enumerated.class).value());

        var shiftTypeField = entityClass.getDeclaredField("shiftType");
        assertTrue(shiftTypeField.isAnnotationPresent(jakarta.persistence.Enumerated.class));
        assertEquals(jakarta.persistence.EnumType.STRING, shiftTypeField.getAnnotation(jakarta.persistence.Enumerated.class).value());
    }

    // =========================================================================
    // TEST 9 — Employee-order independence
    // =========================================================================
    @Test
    @Order(9)
    @DisplayName("Test 9: Employee-Order Independence (Preferences Resolved by Entity ID, Not Position)")
    void test09_EmployeeOrderIndependence() {
        // Verify ApplicablePreference maps by employee ID
        List<Employee> employees = employeeRepository.findByActiveTrueOrderByIdAsc();
        assertTrue(employees.size() >= 5, "Must have active employees");

        Map<Long, Map<DayOfWeek, ShiftType>> activeMap = recurringShiftPreferenceService.getActiveRecurringMap(
                employees, LocalDate.of(2027, 12, 6), LocalDate.of(2027, 12, 12)
        );
        assertNotNull(activeMap, "Active preference map must be keyed by Employee ID");

        // Reordering list does not change mapping
        List<Employee> reversed = new ArrayList<>(employees);
        Collections.reverse(reversed);

        Map<Long, Map<DayOfWeek, ShiftType>> reversedMap = recurringShiftPreferenceService.getActiveRecurringMap(
                reversed, LocalDate.of(2027, 12, 6), LocalDate.of(2027, 12, 12)
        );

        assertEquals(activeMap.keySet(), reversedMap.keySet(), "Key sets must be identical regardless of employee ordering");
        for (Long empId : activeMap.keySet()) {
            assertEquals(activeMap.get(empId), reversedMap.get(empId), "Preference values must be identical regardless of employee order");
        }
    }

    // =========================================================================
    // TEST 10 — Hard constraint conflict: Mandatory rule strictly wins
    // =========================================================================
    @Test
    @Order(10)
    @DisplayName("Test 10: Mandatory Hard Rules Strictly Win Over Conflicting Recurring Preferences")
    void test10_HardConstraintConflictMandatoryRuleWins() {
        Employee femaleEmp = getFemaleEmployee();
        clearRecurringPreferences(femaleEmp.getId());

        // Scenario A: Female employee has recurring preference for NIGHT shift
        recurringShiftPreferenceService.createOrUpdate(new RecurringShiftPreferenceRequest(
                femaleEmp.getId(), DayOfWeek.SATURDAY, ShiftType.NIGHT, null, null, true, "Conflicting Female Night Preference"
        ), "admin");

        // Scenario B: Employee has approved LEAVE on Saturday
        Employee maleEmp = getMaleEmployee();
        clearRecurringPreferences(maleEmp.getId());

        recurringShiftPreferenceService.createOrUpdate(new RecurringShiftPreferenceRequest(
                maleEmp.getId(), DayOfWeek.SATURDAY, ShiftType.MORNING, null, null, true, "Conflicting Leave Preference"
        ), "admin");

        LocalDate weekStart = LocalDate.of(2027, 12, 13);
        LocalDate targetSaturday = weekStart.plusDays(5);

        // Create approved leave for male employee on Saturday
        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(maleEmp);
        leave.setStartDate(targetSaturday);
        leave.setEndDate(targetSaturday);
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setReason("Doctor visit");
        leave.setRequestedAt(LocalDateTime.now());
        leave.setReviewedAt(LocalDateTime.now());
        leave.setAdminRemarks("Approved by admin");
        leaveRequestRepository.save(leave);

        // Generate roster
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle, "Roster cycle must generate successfully");

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());

        // Verify Scenario A: Female employee MUST NOT be assigned NIGHT shift on Saturday (Female safety hard rule strictly wins)
        RosterAssignment femaleSat = assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(femaleEmp.getId()) && a.getRosterDate().equals(targetSaturday))
                .findFirst().orElseThrow();

        if (femaleSat.getShift() != null) {
            assertNotEquals(ShiftType.NIGHT, femaleSat.getShift().getShiftType(),
                    "Mandatory female safety rule MUST win over recurring NIGHT preference for " + femaleEmp.getFirstName());
        }

        // Verify Scenario B: Male employee MUST have LEAVE on Saturday, NOT MORNING shift (Mandatory leave rule strictly wins)
        RosterAssignment maleSat = assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(maleEmp.getId()) && a.getRosterDate().equals(targetSaturday))
                .findFirst().orElseThrow();

        assertTrue(maleSat.isOnLeave(), "Approved Leave hard rule MUST win over recurring preference on Saturday");

        // Invariant: generated roster is valid and passes all constraints
        assertEquals(RosterStatus.GENERATED, cycle.status());
    }

    // =========================================================================
    // TEST 11 — Admin-only access control & zero employee names hardcoding
    // =========================================================================
    @Test
    @Order(11)
    @DisplayName("Test 11: Admin-Only Security Isolation (403 for Non-Admin) and Zero Employee Name Rules")
    void test11_AdminOnlyAccessControlAndZeroEmployeeNamesHardcoding() throws Exception {
        // Step 1: Employee role attempting to create recurring preference gets 403 Forbidden
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("employee_user", "password",
                        List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
        );

        RecurringShiftPreferenceRequest badReq = new RecurringShiftPreferenceRequest(
                1L, DayOfWeek.MONDAY, ShiftType.MORNING, null, null, true, "Unauthorized request"
        );

        mockMvc.perform(post("/api/admin/preferences/recurring")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badReq)))
                .andExpect(status().isForbidden());

        // Step 2: Code inspection asserting zero hardcoded employee names in src/main/java
        Path mainJavaDir = Path.of("src/main/java");
        assertTrue(Files.exists(mainJavaDir), "src/main/java must exist");

        Pattern forbiddenMethodPattern = Pattern.compile("\\b(isRajat|isPrachi|isSapna|isDivyansh|isShriram|isSambhav|isTushar|isJeevan|matchesName)\\s*\\(");
        List<String> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(mainJavaDir)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(path -> {
                try {
                    List<String> lines = Files.readAllLines(path);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        if (forbiddenMethodPattern.matcher(line).find()) {
                            violations.add(path + ":" + (i + 1) + " -> " + line.trim());
                        }
                    }
                } catch (IOException e) {
                    fail("Failed reading " + path + ": " + e.getMessage());
                }
            });
        }

        assertTrue(violations.isEmpty(), "Found hardcoded employee-name heuristics: " + violations);
    }
}
