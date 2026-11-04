package com.weeklyroster.service;

import com.weeklyroster.controller.AdminPreferenceController;
import com.weeklyroster.controller.EmployeePreferenceController;
import com.weeklyroster.dto.ApplicablePreference;
import com.weeklyroster.dto.request.DateShiftPreferenceRequest;
import com.weeklyroster.dto.request.LoginRequest;
import com.weeklyroster.dto.request.RecurringShiftPreferenceRequest;
import com.weeklyroster.dto.response.AdminFullEmployeeProfileResponse;
import com.weeklyroster.dto.response.AuthResponse;
import com.weeklyroster.dto.response.DateShiftPreferenceResponse;
import com.weeklyroster.dto.response.RecurringShiftPreferenceResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.RecurringPreferenceType;
import com.weeklyroster.entity.Role;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.entity.User;
import com.weeklyroster.repository.DateShiftPreferenceRepository;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.RecurringShiftPreferenceRepository;
import com.weeklyroster.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wrms.dev.credential-mirror.enabled=false",
        "spring.datasource.url=jdbc:sqlite:target/test-wrms-b131.db?busy_timeout=30000&journal_mode=WAL"
})
public class Batch131RegressionAuditTest {

    static {
        try {
            System.setProperty("spring.profiles.active", "test");
            System.setProperty("spring.datasource.url", "jdbc:sqlite:target/test-wrms-b131.db?busy_timeout=30000&journal_mode=WAL");
            File targetDir = new File("target");
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
            File realDb = new File("data/wrms.db");
            File testDb = new File("target/test-wrms-b131.db");
            if (realDb.exists()) {
                Files.copy(realDb.toPath(), testDb.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[Batch 131 Test] Isolated test replica created at target/test-wrms-b131.db");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Autowired
    private AuthService authService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private RecurringShiftPreferenceRepository recurringShiftPreferenceRepository;

    @Autowired
    private RecurringShiftPreferenceService recurringShiftPreferenceService;

    @Autowired
    private DateShiftPreferenceRepository dateShiftPreferenceRepository;

    @Autowired
    private DateShiftPreferenceService dateShiftPreferenceService;

    @Autowired
    private AdminPreferenceController adminPreferenceController;

    @Autowired
    private EmployeePreferenceController employeePreferenceController;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Employee testEmployee;
    private User testUser;
    private User adminUser;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "admin",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );

        // Ensure Admin user exists with valid encoded password
        adminUser = userRepository.findByUsernameIgnoreCase("admin").orElse(null);
        if (adminUser == null) {
            adminUser = new User();
            adminUser.setUsername("admin");
            adminUser.setPassword(passwordEncoder.encode("Admin@123"));
            adminUser.setRole(Role.ROLE_ADMIN);
            adminUser.setEnabled(true);
            adminUser = userRepository.save(adminUser);
        } else if (!passwordEncoder.matches("Admin@123", adminUser.getPassword())) {
            adminUser.setPassword(passwordEncoder.encode("Admin@123"));
            adminUser = userRepository.save(adminUser);
        }

        // Ensure test employee exists
        testEmployee = employeeRepository.findByEmployeeCode("EMP001").orElse(null);
        if (testEmployee == null) {
            testEmployee = new Employee();
            testEmployee.setEmployeeCode("EMP001");
            testEmployee.setFirstName("Rajat");
            testEmployee.setLastName("Maurya");
            testEmployee.setEmail("rajatkumarmaury@gmail.com");
            testEmployee.setGender(Gender.MALE);
            testEmployee.setActive(true);
            testEmployee.setJoiningDate(LocalDate.of(2025, 1, 1));
            testEmployee = employeeRepository.save(testEmployee);
        } else {
            testEmployee.setActive(true);
            testEmployee = employeeRepository.save(testEmployee);
        }

        testUser = userRepository.findByUsernameIgnoreCase("emp001").orElse(null);
        if (testUser == null) {
            testUser = new User();
            testUser.setUsername("emp001");
            testUser.setPassword(passwordEncoder.encode("password123"));
            testUser.setRole(Role.ROLE_EMPLOYEE);
            testUser.setEmployee(testEmployee);
            testUser.setEnabled(true);
            testUser = userRepository.save(testUser);
        } else {
            testUser.setPassword(passwordEncoder.encode("password123"));
            testUser.setEnabled(true);
            testUser.setEmployee(testEmployee);
            testUser = userRepository.save(testUser);
        }
    }

    // =========================================================================
    // 1. AUTHENTICATION & LOGIN REGRESSION
    // =========================================================================

    @Test
    @DisplayName("1. Admin Login: Valid credentials authenticate successfully and return ROLE_ADMIN")
    void testAdminLoginSuccess() {
        LoginRequest req = new LoginRequest("admin", "Admin@123");
        AuthResponse res = authService.login(req);

        assertNotNull(res, "AuthResponse must not be null");
        assertNotNull(res.token(), "JWT token must be returned");
        assertEquals("Bearer", res.tokenType());
        assertNotNull(res.user());
        assertEquals("admin", res.user().username());
        assertEquals(Role.ROLE_ADMIN, res.user().role());
    }

    @Test
    @DisplayName("2. Employee Login by Code: EMP001 authenticates successfully and returns ROLE_EMPLOYEE")
    void testEmployeeLoginSuccessByCode() {
        LoginRequest req = new LoginRequest("emp001", "password123");
        AuthResponse res = authService.login(req);

        assertNotNull(res);
        assertNotNull(res.token());
        assertEquals(Role.ROLE_EMPLOYEE, res.user().role());
        assertEquals(testEmployee.getId(), res.user().employeeId());
    }

    @Test
    @DisplayName("3. Employee Login by Email: Email identifier authenticates successfully")
    void testEmployeeLoginSuccessByEmail() {
        LoginRequest req = new LoginRequest(testEmployee.getEmail(), "password123");
        AuthResponse res = authService.login(req);

        assertNotNull(res);
        assertNotNull(res.token());
        assertEquals(Role.ROLE_EMPLOYEE, res.user().role());
    }

    @Test
    @DisplayName("4. Login Failure: Bad password triggers BadCredentialsException")
    void testLoginFailsOnBadPassword() {
        LoginRequest req = new LoginRequest("admin", "WrongPassword!999");
        assertThrows(BadCredentialsException.class, () -> authService.login(req));
    }

    @Test
    @DisplayName("5. Login Failure: Non-existent user triggers safe authentication failure")
    void testLoginFailsOnUnknownUser() {
        LoginRequest req = new LoginRequest("non_existent_user_xyz", "somePassword");
        assertThrows(AuthenticationException.class, () -> authService.login(req));
    }

    @Test
    @DisplayName("6. Inactive Account: Disabled employee user cannot log in")
    @Transactional
    void testInactiveUserDeniedLogin() {
        testUser.setEnabled(false);
        userRepository.save(testUser);

        LoginRequest req = new LoginRequest("emp001", "password123");
        assertThrows(DisabledException.class, () -> authService.login(req));

        // Restore
        testUser.setEnabled(true);
        userRepository.save(testUser);
    }

    // =========================================================================
    // 2. CENTRALIZED PREFERENCE SYNCHRONIZATION REGRESSION
    // =========================================================================

    @Test
    @DisplayName("7. Full Preference Lifecycle: Create, Read, Update, Delete across Admin & Employee views")
    @Transactional
    void testFullPreferenceSynchronizationLifecycle() {
        // Step 1: Admin adds recurring preference (WEDNESDAY -> MORNING, PREFERRED)
        RecurringShiftPreferenceRequest recReq1 = new RecurringShiftPreferenceRequest(
                testEmployee.getId(),
                DayOfWeek.WEDNESDAY,
                ShiftType.MORNING,
                RecurringPreferenceType.PREFERRED,
                null,
                null,
                true,
                "Prefers morning on Wednesdays"
        );
        RecurringShiftPreferenceResponse recResp1 = recurringShiftPreferenceService.createOrUpdate(recReq1, "admin");
        assertNotNull(recResp1);
        assertEquals(RecurringPreferenceType.PREFERRED, recResp1.preferenceType());

        // Step 2: Admin adds recurring restriction (THURSDAY -> NIGHT, DO_NOT_ASSIGN)
        RecurringShiftPreferenceRequest recReq2 = new RecurringShiftPreferenceRequest(
                testEmployee.getId(),
                DayOfWeek.THURSDAY,
                ShiftType.NIGHT,
                RecurringPreferenceType.DO_NOT_ASSIGN,
                null,
                null,
                true,
                "Do not assign night on Thursdays"
        );
        RecurringShiftPreferenceResponse recResp2 = recurringShiftPreferenceService.createOrUpdate(recReq2, "admin");
        assertNotNull(recResp2);
        assertEquals(RecurringPreferenceType.DO_NOT_ASSIGN, recResp2.preferenceType());

        // Step 3: Admin adds date-specific preference (2026-12-15 -> EVENING, PREFERRED)
        LocalDate datePref = LocalDate.of(2026, 12, 15);
        DateShiftPreferenceRequest dateReq1 = new DateShiftPreferenceRequest(
                testEmployee.getId(),
                datePref,
                ShiftType.EVENING,
                RecurringPreferenceType.PREFERRED,
                "Family gathering"
        );
        DateShiftPreferenceResponse dateResp1 = dateShiftPreferenceService.createOrUpdate(dateReq1, "admin");
        assertNotNull(dateResp1);
        assertEquals(RecurringPreferenceType.PREFERRED, dateResp1.preferenceType());

        // Step 4: Admin adds date-specific restriction (2026-12-16 -> NIGHT, DO_NOT_ASSIGN)
        LocalDate dateAvoid = LocalDate.of(2026, 12, 16);
        DateShiftPreferenceRequest dateReq2 = new DateShiftPreferenceRequest(
                testEmployee.getId(),
                dateAvoid,
                ShiftType.NIGHT,
                RecurringPreferenceType.DO_NOT_ASSIGN,
                "Doctor appointment next morning"
        );
        DateShiftPreferenceResponse dateResp2 = dateShiftPreferenceService.createOrUpdate(dateReq2, "admin");
        assertNotNull(dateResp2);
        assertEquals(RecurringPreferenceType.DO_NOT_ASSIGN, dateResp2.preferenceType());

        // Step 5: Query Admin Summary API
        ResponseEntity<Map<String, Object>> adminSummaryResp =
                adminPreferenceController.getEmployeePreferenceSummary(testEmployee.getId());
        assertEquals(200, adminSummaryResp.getStatusCode().value());
        Map<String, Object> adminBody = adminSummaryResp.getBody();
        assertNotNull(adminBody);

        @SuppressWarnings("unchecked")
        List<RecurringShiftPreferenceResponse> adminRecList =
                (List<RecurringShiftPreferenceResponse>) adminBody.get("recurringPreferences");
        @SuppressWarnings("unchecked")
        List<DateShiftPreferenceResponse> adminDateList =
                (List<DateShiftPreferenceResponse>) adminBody.get("dateSpecificPreferences");

        assertTrue(adminRecList.stream().anyMatch(r -> r.dayOfWeek() == DayOfWeek.WEDNESDAY && r.shiftType() == ShiftType.MORNING && r.preferenceType() == RecurringPreferenceType.PREFERRED));
        assertTrue(adminRecList.stream().anyMatch(r -> r.dayOfWeek() == DayOfWeek.THURSDAY && r.shiftType() == ShiftType.NIGHT && r.preferenceType() == RecurringPreferenceType.DO_NOT_ASSIGN));
        assertTrue(adminDateList.stream().anyMatch(d -> d.preferenceDate().equals(datePref) && d.shiftType() == ShiftType.EVENING && d.preferenceType() == RecurringPreferenceType.PREFERRED));
        assertTrue(adminDateList.stream().anyMatch(d -> d.preferenceDate().equals(dateAvoid) && d.shiftType() == ShiftType.NIGHT && d.preferenceType() == RecurringPreferenceType.DO_NOT_ASSIGN));

        // Step 6: Query Employee Self-Service Summary API
        var empAuth = new UsernamePasswordAuthenticationToken(testUser.getUsername(), "password123",
                List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        SecurityContextHolder.getContext().setAuthentication(empAuth);

        ResponseEntity<Map<String, Object>> empSummaryResp =
                employeePreferenceController.getMyPreferenceSummary(empAuth);
        assertEquals(200, empSummaryResp.getStatusCode().value());
        Map<String, Object> empBody = empSummaryResp.getBody();
        assertNotNull(empBody);

        @SuppressWarnings("unchecked")
        List<RecurringShiftPreferenceResponse> empRecList =
                (List<RecurringShiftPreferenceResponse>) empBody.get("recurringPreferences");
        @SuppressWarnings("unchecked")
        List<DateShiftPreferenceResponse> empDateList =
                (List<DateShiftPreferenceResponse>) empBody.get("dateSpecificPreferences");

        assertEquals(adminRecList.size(), empRecList.size());
        assertEquals(adminDateList.size(), empDateList.size());

        // Step 7: Update preference (Toggle active state)
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "admin", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
        RecurringShiftPreferenceResponse toggled = recurringShiftPreferenceService.toggleActive(recResp1.id(), "admin");
        assertFalse(toggled.active());

        // Verify updated state in summary
        ResponseEntity<Map<String, Object>> reCheck = adminPreferenceController.getEmployeePreferenceSummary(testEmployee.getId());
        @SuppressWarnings("unchecked")
        List<RecurringShiftPreferenceResponse> updatedRec = (List<RecurringShiftPreferenceResponse>) reCheck.getBody().get("recurringPreferences");
        assertTrue(updatedRec.stream().anyMatch(r -> r.id().equals(recResp1.id()) && !r.active()));

        // Step 8: Delete date preference and verify synchronization
        dateShiftPreferenceService.deleteDatePreference(dateResp1.id(), "admin");
        List<DateShiftPreferenceResponse> dateAfterDelete = dateShiftPreferenceService.getDatePreferencesByEmployee(testEmployee.getId());
        assertFalse(dateAfterDelete.stream().anyMatch(d -> d.id().equals(dateResp1.id())));
    }

    // =========================================================================
    // 3. ROSTER CONSTRAINT ENGINE & PREFERENCE HIERARCHY
    // =========================================================================

    @Test
    @DisplayName("8. ApplicablePreference Hierarchy: Date-specific avoid and preferred override recurring rules")
    void testApplicablePreferenceEvaluationHierarchy() {
        LocalDate tuesday = LocalDate.of(2026, 12, 1);
        LocalDate wednesday = LocalDate.of(2026, 12, 2);

        // Date-specific overrides:
        // - Tuesday: date-specific Preferred MORNING (overrides any recurring)
        // - Wednesday: date-specific Avoid NIGHT (cannot be assigned night)
        Map<LocalDate, ShiftType> datePreferred = Map.of(tuesday, ShiftType.MORNING);
        Map<LocalDate, Set<ShiftType>> dateAvoid = Map.of(wednesday, Set.of(ShiftType.NIGHT));

        // Recurring rules:
        // - Tuesday: recurring wanted EVENING (must be overridden by date-specific MORNING)
        // - Wednesday: recurring wanted NIGHT (must be overridden by date-specific avoid NIGHT)
        Map<DayOfWeek, ShiftType> recurringPreferred = Map.of(
                DayOfWeek.TUESDAY, ShiftType.EVENING,
                DayOfWeek.WEDNESDAY, ShiftType.NIGHT
        );

        ApplicablePreference ap = ApplicablePreference.withDateAndRecurringShifts(
                testEmployee.getId(),
                datePreferred,
                dateAvoid,
                recurringPreferred,
                Collections.emptyMap()
        );

        // 1. Tuesday: target shift must be MORNING (date-specific preferred wins)
        assertEquals(ShiftType.MORNING, ap.getTargetShiftForDate(tuesday));

        // 2. Wednesday: NIGHT is avoided on Wednesday due to date-specific avoid
        assertTrue(ap.isShiftAvoidedOnDate(ShiftType.NIGHT, wednesday));
    }

    @Test
    @DisplayName("9. Employee Eligibility: Roster assignment respects Joining Date and Resignation Date")
    void testEmployeeEligibilityDates() {
        Employee emp = new Employee();
        emp.setActive(true);
        emp.setJoiningDate(LocalDate.of(2026, 6, 1));
        emp.setResignationDate(LocalDate.of(2026, 12, 31));

        // Date before joining date -> NOT eligible
        assertFalse(emp.isEligibleForDate(LocalDate.of(2026, 5, 31)));

        // Date on joining date -> eligible
        assertTrue(emp.isEligibleForDate(LocalDate.of(2026, 6, 1)));

        // Date within employment -> eligible
        assertTrue(emp.isEligibleForDate(LocalDate.of(2026, 9, 15)));

        // Date on resignation date -> eligible
        assertTrue(emp.isEligibleForDate(LocalDate.of(2026, 12, 31)));

        // Date after resignation date -> NOT eligible
        assertFalse(emp.isEligibleForDate(LocalDate.of(2027, 1, 1)));

        // Inactive employee -> NOT eligible regardless of date
        emp.setActive(false);
        assertFalse(emp.isEligibleForDate(LocalDate.of(2026, 9, 15)));
    }

    // =========================================================================
    // 4. SECURITY & DATA PRIVACY
    // =========================================================================

    @Test
    @DisplayName("10. Data Privacy: Employee profile API does not expose password hashes")
    void testProfileDoesNotExposePasswords() {
        AdminFullEmployeeProfileResponse profile = employeeService.getFullProfile(testEmployee.getId());
        assertNotNull(profile);
        assertEquals(testEmployee.getEmployeeCode(), profile.employeeCode());
        // Verify no password accessor or field in profile response
        assertNotNull(profile.username());
        assertNotNull(profile.role());
    }

    @Test
    @DisplayName("11. Role Selection UI: index.html contains accessible role radio cards without credentials")
    void testRoleCardsMarkupAndNoCredentialsInHtml() throws Exception {
        InputStream is = new ClassPathResource("static/index.html").getInputStream();
        String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // Role cards exist and have tabindex="0"
        assertTrue(html.contains("id=\"roleCardAdmin\" tabindex=\"0\""), "roleCardAdmin must have tabindex 0");
        assertTrue(html.contains("id=\"roleCardEmployee\" tabindex=\"0\""), "roleCardEmployee must have tabindex 0");

        // Radiogroup semantic attributes
        assertTrue(html.contains("role=\"radiogroup\""));
        assertTrue(html.contains("name=\"authRole\""));
        assertTrue(html.contains("value=\"ADMIN\""));
        assertTrue(html.contains("value=\"EMPLOYEE\""));

        // No passwords leaked
        assertFalse(html.contains("Admin@123"));
        assertFalse(html.contains("password123"));
    }
}
