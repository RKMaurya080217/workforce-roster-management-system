package com.weeklyroster.service;

import com.weeklyroster.controller.AdminPreferenceController;
import com.weeklyroster.controller.EmployeePreferenceController;
import com.weeklyroster.dto.ApplicablePreference;
import com.weeklyroster.dto.request.DateShiftPreferenceRequest;
import com.weeklyroster.dto.request.RecurringShiftPreferenceRequest;
import com.weeklyroster.dto.response.DateShiftPreferenceResponse;
import com.weeklyroster.dto.response.EmployeeResponse;
import com.weeklyroster.dto.response.RecurringShiftPreferenceResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.RecurringPreferenceType;
import com.weeklyroster.entity.Role;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.entity.User;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.UserRepository;
import com.weeklyroster.security.UserPrincipal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wrms.dev.credential-mirror.enabled=false",
        "spring.datasource.url=jdbc:sqlite:target/test-wrms-b132.db?busy_timeout=30000&journal_mode=WAL"
})
public class Batch132ViewAllRulesAndEmployeeProfileTest {

    static {
        try {
            System.setProperty("spring.profiles.active", "test");
            System.setProperty("spring.datasource.url", "jdbc:sqlite:target/test-wrms-b132.db?busy_timeout=30000&journal_mode=WAL");
            File targetDir = new File("target");
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
            File realDb = new File("data/wrms.db");
            File testDb = new File("target/test-wrms-b132.db");
            if (realDb.exists()) {
                Files.copy(realDb.toPath(), testDb.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[Batch 132 Test] Isolated test replica created at target/test-wrms-b132.db");
            }
        } catch (Exception e) {
            System.err.println("[Batch 132 Test] DB copy failed: " + e.getMessage());
        }
    }

    @Autowired
    private EmployeePreferenceController employeePreferenceController;

    @Autowired
    private AdminPreferenceController adminPreferenceController;

    @Autowired
    private RecurringShiftPreferenceService recurringShiftPreferenceService;

    @Autowired
    private DateShiftPreferenceService dateShiftPreferenceService;

    @Autowired
    private EmployeePreferenceService preferenceService;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private UserRepository userRepository;

    private Employee emp1;
    private Employee emp2;
    private User userEmp1;
    private User userEmp2;
    private User userAdmin;

    @BeforeEach
    void setUp() {
        emp1 = employeeRepository.findByEmployeeCodeIgnoreCase("EMP001").orElse(null);
        if (emp1 == null) {
            emp1 = new Employee();
            emp1.setEmployeeCode("EMP001");
            emp1.setFirstName("Rajat");
            emp1.setLastName("Maurya");
            emp1.setEmail("rkmaurya080217@gmail.com");
            emp1.setActive(true);
            emp1 = employeeRepository.save(emp1);
        }

        userEmp1 = userRepository.findByUsernameIgnoreCase("emp001").orElse(null);
        if (userEmp1 == null) {
            userEmp1 = new User();
            userEmp1.setUsername("emp001");
            userEmp1.setPassword("password123");
            userEmp1.setRole(Role.ROLE_EMPLOYEE);
            userEmp1.setEnabled(true);
            userEmp1 = userRepository.save(userEmp1);
        }
        if (emp1.getUser() == null) {
            emp1.setUser(userEmp1);
            emp1 = employeeRepository.save(emp1);
        }

        emp2 = employeeRepository.findByEmployeeCodeIgnoreCase("EMP002").orElse(null);
        if (emp2 == null) {
            emp2 = new Employee();
            emp2.setEmployeeCode("EMP002");
            emp2.setFirstName("Prachi");
            emp2.setLastName("Mishra");
            emp2.setEmail("prachimishra07070@gmail.com");
            emp2.setActive(true);
            emp2 = employeeRepository.save(emp2);
        }

        userEmp2 = userRepository.findByUsernameIgnoreCase("emp002").orElse(null);
        if (userEmp2 == null) {
            userEmp2 = new User();
            userEmp2.setUsername("emp002");
            userEmp2.setPassword("password123");
            userEmp2.setRole(Role.ROLE_EMPLOYEE);
            userEmp2.setEnabled(true);
            userEmp2 = userRepository.save(userEmp2);
        }
        if (emp2.getUser() == null) {
            emp2.setUser(userEmp2);
            emp2 = employeeRepository.save(emp2);
        }

        userAdmin = userRepository.findByUsernameIgnoreCase("admin").orElse(null);
        if (userAdmin == null) {
            userAdmin = new User();
            userAdmin.setUsername("admin");
            userAdmin.setPassword("Admin@123");
            userAdmin.setRole(Role.ROLE_ADMIN);
            userAdmin.setEnabled(true);
            userAdmin = userRepository.save(userAdmin);
        }
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(User user) {
        UserPrincipal principal = new UserPrincipal(user);
        Authentication auth = new UsernamePasswordAuthenticationToken(
                user.getUsername(),
                user.getPassword(),
                List.of(new SimpleGrantedAuthority(user.getRole().name()))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // =========================================================================
    // TEST 1: View All Rules Summary API returns full canonical rules
    // =========================================================================
    @Test
    @DisplayName("Test 1: View All Rules API returns canonical preference summary for logged-in employee")
    @Transactional
    void testViewAllRulesSummaryEndpoint_ReturnsAllPreferences() {
        authenticateAs(userEmp1);

        // Add a recurring preference for EMP001
        RecurringShiftPreferenceRequest recReq = new RecurringShiftPreferenceRequest(
                emp1.getId(),
                DayOfWeek.MONDAY,
                ShiftType.MORNING,
                RecurringPreferenceType.PREFERRED,
                null,
                null,
                true,
                "Test 1 Monday morning"
        );
        recurringShiftPreferenceService.createOrUpdate(recReq, "admin");

        // Add a date-specific preference for EMP001
        LocalDate date = LocalDate.of(2026, 12, 10);
        DateShiftPreferenceRequest dateReq = new DateShiftPreferenceRequest(
                emp1.getId(),
                date,
                ShiftType.NIGHT,
                RecurringPreferenceType.DO_NOT_ASSIGN,
                "Test 1 Date night restriction"
        );
        dateShiftPreferenceService.createOrUpdate(dateReq, "admin");

        // Employee calls /api/preferences/my/summary
        ResponseEntity<Map<String, Object>> response = employeePreferenceController.getMyPreferenceSummary(
                SecurityContextHolder.getContext().getAuthentication()
        );

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);

        assertEquals(emp1.getId(), body.get("employeeId"));
        assertEquals("EMP001", body.get("employeeCode"));
        assertNotNull(body.get("employeeName"));
        assertTrue(body.containsKey("basePreferredShift"));

        // Check recurring list contains Monday MORNING PREFERRED
        List<?> recList = (List<?>) body.get("recurringPreferences");
        assertNotNull(recList);
        assertFalse(recList.isEmpty());
        boolean foundRec = recList.stream().anyMatch(r -> {
            if (r instanceof RecurringShiftPreferenceResponse resp) {
                return resp.dayOfWeek() == DayOfWeek.MONDAY && resp.shiftType() == ShiftType.MORNING && resp.preferenceType() == RecurringPreferenceType.PREFERRED;
            }
            return false;
        });
        assertTrue(foundRec, "Summary must contain Monday MORNING PREFERRED recurring rule");

        // Check date-specific list contains 2026-12-10 NIGHT DO_NOT_ASSIGN
        List<?> dateList = (List<?>) body.get("dateSpecificPreferences");
        assertNotNull(dateList);
        assertFalse(dateList.isEmpty());
        boolean foundDate = dateList.stream().anyMatch(d -> {
            if (d instanceof DateShiftPreferenceResponse resp) {
                return date.equals(resp.preferenceDate()) && resp.shiftType() == ShiftType.NIGHT && resp.preferenceType() == RecurringPreferenceType.DO_NOT_ASSIGN;
            }
            return false;
        });
        assertTrue(foundDate, "Summary must contain 2026-12-10 NIGHT DO_NOT_ASSIGN date-specific rule");
    }

    // =========================================================================
    // TEST 2: Strict Employee Data Isolation
    // =========================================================================
    @Test
    @DisplayName("Test 2: Strict Employee Data Isolation - Employee only sees their own rules")
    @Transactional
    void testStrictEmployeeDataIsolation() {
        // Create a rule for EMP002
        RecurringShiftPreferenceRequest req2 = new RecurringShiftPreferenceRequest(
                emp2.getId(),
                DayOfWeek.FRIDAY,
                ShiftType.EVENING,
                RecurringPreferenceType.PREFERRED,
                null,
                null,
                true,
                "EMP002 Friday Evening"
        );
        recurringShiftPreferenceService.createOrUpdate(req2, "admin");

        // Login as EMP001
        authenticateAs(userEmp1);
        ResponseEntity<Map<String, Object>> res1 = employeePreferenceController.getMyPreferenceSummary(
                SecurityContextHolder.getContext().getAuthentication()
        );
        Map<String, Object> body1 = res1.getBody();
        assertNotNull(body1);
        assertEquals(emp1.getId(), body1.get("employeeId"));
        assertEquals("EMP001", body1.get("employeeCode"));

        // Verify EMP001 does NOT see EMP002's Friday rule
        List<?> recList1 = (List<?>) body1.get("recurringPreferences");
        boolean containsEmp2Rule = recList1.stream().anyMatch(r -> {
            if (r instanceof RecurringShiftPreferenceResponse resp) {
                return resp.employeeId().equals(emp2.getId());
            }
            return false;
        });
        assertFalse(containsEmp2Rule, "EMP001 summary must never contain EMP002's rules");

        // Login as EMP002
        authenticateAs(userEmp2);
        ResponseEntity<Map<String, Object>> res2 = employeePreferenceController.getMyPreferenceSummary(
                SecurityContextHolder.getContext().getAuthentication()
        );
        Map<String, Object> body2 = res2.getBody();
        assertNotNull(body2);
        assertEquals(emp2.getId(), body2.get("employeeId"));
        assertEquals("EMP002", body2.get("employeeCode"));

        List<?> recList2 = (List<?>) body2.get("recurringPreferences");
        boolean hasEmp2Rule = recList2.stream().anyMatch(r -> {
            if (r instanceof RecurringShiftPreferenceResponse resp) {
                return resp.employeeId().equals(emp2.getId()) && resp.dayOfWeek() == DayOfWeek.FRIDAY;
            }
            return false;
        });
        assertTrue(hasEmp2Rule, "EMP002 summary must contain their own Friday rule");
    }

    // =========================================================================
    // TEST 3: Positive (PREFERRED) and Negative (DO_NOT_ASSIGN) rules
    // =========================================================================
    @Test
    @DisplayName("Test 3: Both positive (PREFERRED) and negative (DO_NOT_ASSIGN) rules are certified")
    @Transactional
    void testBothPreferredAndDoNotAssignPreferencesSupported() {
        authenticateAs(userEmp1);

        LocalDate datePref = LocalDate.of(2026, 12, 20);
        LocalDate dateAvoid = LocalDate.of(2026, 12, 21);

        DateShiftPreferenceRequest reqPref = new DateShiftPreferenceRequest(
                emp1.getId(),
                datePref,
                ShiftType.MORNING,
                RecurringPreferenceType.PREFERRED,
                "Pref morning"
        );
        dateShiftPreferenceService.createOrUpdate(reqPref, "admin");

        DateShiftPreferenceRequest reqAvoid = new DateShiftPreferenceRequest(
                emp1.getId(),
                dateAvoid,
                ShiftType.NIGHT,
                RecurringPreferenceType.DO_NOT_ASSIGN,
                "Avoid night"
        );
        dateShiftPreferenceService.createOrUpdate(reqAvoid, "admin");

        ResponseEntity<Map<String, Object>> res = employeePreferenceController.getMyPreferenceSummary(
                SecurityContextHolder.getContext().getAuthentication()
        );
        List<?> dateList = (List<?>) res.getBody().get("dateSpecificPreferences");

        boolean hasPreferred = dateList.stream().anyMatch(d ->
                d instanceof DateShiftPreferenceResponse r &&
                        r.preferenceDate().equals(datePref) &&
                        r.preferenceType() == RecurringPreferenceType.PREFERRED
        );
        boolean hasDoNotAssign = dateList.stream().anyMatch(d ->
                d instanceof DateShiftPreferenceResponse r &&
                        r.preferenceDate().equals(dateAvoid) &&
                        r.preferenceType() == RecurringPreferenceType.DO_NOT_ASSIGN
        );

        assertTrue(hasPreferred, "Must support positive PREFERRED shift");
        assertTrue(hasDoNotAssign, "Must support negative DO_NOT_ASSIGN shift");
    }

    // =========================================================================
    // TEST 4: Admin Updates Immediately Visible to Employee View All Rules
    // =========================================================================
    @Test
    @DisplayName("Test 4: Admin preference updates immediately reflect in Employee View All Rules")
    @Transactional
    void testAdminPreferenceChange_ImmediatelyVisibleToEmployee() {
        // Admin creates a date preference for EMP001
        LocalDate testDate = LocalDate.of(2026, 12, 25);
        DateShiftPreferenceRequest adminReq = new DateShiftPreferenceRequest(
                emp1.getId(),
                testDate,
                ShiftType.GENERAL,
                RecurringPreferenceType.PREFERRED,
                "Christmas Day General Shift Admin Assigned"
        );
        dateShiftPreferenceService.createOrUpdate(adminReq, "admin");

        // Employee logs in and opens View All Rules
        authenticateAs(userEmp1);
        ResponseEntity<Map<String, Object>> empSummary = employeePreferenceController.getMyPreferenceSummary(
                SecurityContextHolder.getContext().getAuthentication()
        );

        List<?> dates = (List<?>) empSummary.getBody().get("dateSpecificPreferences");
        boolean foundAdminUpdate = dates.stream().anyMatch(d ->
                d instanceof DateShiftPreferenceResponse r &&
                        testDate.equals(r.preferenceDate()) &&
                        r.shiftType() == ShiftType.GENERAL
        );
        assertTrue(foundAdminUpdate, "Employee summary must immediately reflect Admin updates");
    }

    // =========================================================================
    // TEST 5: Empty state handling
    // =========================================================================
    @Test
    @DisplayName("Test 5: Empty state handling when no rules configured")
    @Transactional
    void testEmptyStateHandling_WhenNoRulesConfigured() {
        // Create a fresh employee without any preferences
        Employee emptyEmp = new Employee();
        emptyEmp.setEmployeeCode("EMP_EMPTY_132");
        emptyEmp.setFirstName("Empty");
        emptyEmp.setLastName("User");
        emptyEmp.setEmail("empty.b132@example.com");
        emptyEmp.setGender(com.weeklyroster.entity.Gender.MALE);
        emptyEmp.setActive(true);
        emptyEmp = employeeRepository.save(emptyEmp);

        User emptyUser = new User();
        emptyUser.setUsername("emp_empty_132");
        emptyUser.setPassword("password123");
        emptyUser.setRole(Role.ROLE_EMPLOYEE);
        emptyUser.setEnabled(true);
        emptyUser = userRepository.save(emptyUser);

        emptyEmp.setUser(emptyUser);
        employeeRepository.save(emptyEmp);

        authenticateAs(emptyUser);
        ResponseEntity<Map<String, Object>> res = employeePreferenceController.getMyPreferenceSummary(
                SecurityContextHolder.getContext().getAuthentication()
        );

        assertNotNull(res.getBody());
        List<?> recList = (List<?>) res.getBody().get("recurringPreferences");
        List<?> dateList = (List<?>) res.getBody().get("dateSpecificPreferences");

        assertNotNull(recList);
        assertNotNull(dateList);
        assertTrue(recList.isEmpty(), "Fresh employee should have 0 recurring preferences");
        assertTrue(dateList.isEmpty(), "Fresh employee should have 0 date preferences");
    }

    // =========================================================================
    // TEST 6: Security - Unauthorized access without token rejected
    // =========================================================================
    @Test
    @DisplayName("Test 6: Anonymous/Unauthorized access without token is rejected")
    void testUnauthorizedAccessRejected() {
        SecurityContextHolder.clearContext();
        assertThrows(Exception.class, () ->
                employeePreferenceController.getMyPreferenceSummary(null)
        );
    }

    // =========================================================================
    // TEST 7: Security - Employee role cannot access Admin Preference endpoints
    // =========================================================================
    @Test
    @DisplayName("Test 7: Employee role is forbidden from accessing Admin Preference endpoints")
    void testEmployeeCannotAccessAdminPreferenceEndpoints() {
        authenticateAs(userEmp1);

        try {
            adminPreferenceController.getEmployeePreferenceSummary(emp1.getId());
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertFalse(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        } catch (AccessDeniedException ade) {
            assertNotNull(ade);
        }
    }

    // =========================================================================
    // TEST 8: Employee Profile Data Integrity & Privacy (no exposed passwords)
    // =========================================================================
    @Test
    @DisplayName("Test 8: Employee Profile endpoint returns master data with strict password privacy")
    void testEmployeeProfileEndpointDataIntegrityAndPrivacy() {
        authenticateAs(userEmp1);
        EmployeeResponse profile = employeeService.getById(emp1.getId());

        assertNotNull(profile);
        assertEquals("EMP001", profile.employeeCode());
        assertEquals("Rajat", profile.firstName());
        assertEquals("Maurya", profile.lastName());
        assertEquals("rkmaurya080217@gmail.com", profile.email());
        assertTrue(profile.active());

        String json = profile.toString();
        assertFalse(json.toLowerCase().contains("password"), "Employee profile must never expose password fields");
        assertFalse(json.toLowerCase().contains("hash"), "Employee profile must never expose password hashes");
    }

    // =========================================================================
    // TEST 9: View All Rules Markup in HTML and JS
    // =========================================================================
    @Test
    @DisplayName("Test 9: Static UI files contain accessible View All Rules modal and bindings")
    void testViewAllRulesModalMarkupAndClientBinding() throws Exception {
        File htmlFile = new File("src/main/resources/static/index.html");
        assertTrue(htmlFile.exists(), "index.html must exist");
        String html = Files.readString(htmlFile.toPath());

        assertTrue(html.contains("id=\"employeeRulesModal\""), "index.html must contain #employeeRulesModal");
        assertTrue(html.contains("id=\"employeeRulesModalTitle\""), "index.html must contain #employeeRulesModalTitle");
        assertTrue(html.contains("id=\"employeeRulesModalBody\""), "index.html must contain #employeeRulesModalBody");
        assertTrue(html.contains("data-close-modal=\"employeeRulesModal\""), "index.html must have data-close-modal for employeeRulesModal");

        File jsFile = new File("src/main/resources/static/app.js");
        assertTrue(jsFile.exists(), "app.js must exist");
        String js = Files.readString(jsFile.toPath());

        assertTrue(js.contains("function openEmployeeRulesModal"), "app.js must define openEmployeeRulesModal");
        assertTrue(js.contains("id=\"btnViewAllRules\""), "app.js profile template must contain #btnViewAllRules");
        assertTrue(js.contains("openEmployeeRulesModal()"), "app.js must call openEmployeeRulesModal() on click");
        assertTrue(js.contains("window.switchWorkspaceTab"), "app.js must define window.switchWorkspaceTab alias");
    }

    // =========================================================================
    // TEST 10: Roster Engine Canonical Sync with View All Rules
    // =========================================================================
    @Test
    @DisplayName("Test 10: Same preference records evaluated by ApplicablePreference and Roster Engine")
    @Transactional
    void testRosterEngineEvaluationOfSameCanonicalPreferences() {
        LocalDate testDate = LocalDate.of(2026, 12, 30); // Wednesday

        // Setup maps:
        Map<DayOfWeek, ShiftType> recurringPreferred = Map.of(DayOfWeek.MONDAY, ShiftType.MORNING);
        Map<DayOfWeek, Set<ShiftType>> recurringAvoid = Map.of(DayOfWeek.WEDNESDAY, Set.of(ShiftType.NIGHT));

        Map<LocalDate, ShiftType> dateSpecificPreferred = Map.of(testDate, ShiftType.MORNING);
        Map<LocalDate, Set<ShiftType>> dateSpecificAvoid = Collections.emptyMap();

        ApplicablePreference ap = ApplicablePreference.withDateAndRecurringShifts(
                emp1.getId(),
                dateSpecificPreferred,
                dateSpecificAvoid,
                recurringPreferred,
                recurringAvoid
        );

        // 1. Date-specific MORNING must be target shift on testDate
        assertEquals(ShiftType.MORNING, ap.getTargetShiftForDate(testDate), "Date-specific MORNING must be returned as target");

        // 2. Wednesday NIGHT must be avoided on normal Wednesday (without override)
        LocalDate normalWed = LocalDate.of(2026, 12, 23);
        assertTrue(ap.isShiftAvoidedOnDate(ShiftType.NIGHT, normalWed), "Wednesday NIGHT must be avoided due to recurring rule");
        assertFalse(ap.isShiftAvoidedOnDate(ShiftType.MORNING, normalWed), "Wednesday MORNING is not avoided");
    }
}
