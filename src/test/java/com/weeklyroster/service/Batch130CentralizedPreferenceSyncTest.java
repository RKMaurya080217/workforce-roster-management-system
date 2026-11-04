package com.weeklyroster.service;

import com.weeklyroster.controller.AdminPreferenceController;
import com.weeklyroster.controller.EmployeePreferenceController;
import com.weeklyroster.dto.ApplicablePreference;
import com.weeklyroster.dto.request.DateShiftPreferenceRequest;
import com.weeklyroster.dto.request.RecurringShiftPreferenceRequest;
import com.weeklyroster.dto.response.AdminFullEmployeeProfileResponse;
import com.weeklyroster.dto.response.DateShiftPreferenceResponse;
import com.weeklyroster.dto.response.RecurringShiftPreferenceResponse;
import com.weeklyroster.entity.Employee;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
        "spring.datasource.url=jdbc:sqlite:target/test-wrms-b130.db?busy_timeout=30000&journal_mode=WAL"
})
public class Batch130CentralizedPreferenceSyncTest {

    static {
        try {
            System.setProperty("spring.profiles.active", "test");
            System.setProperty("spring.datasource.url", "jdbc:sqlite:target/test-wrms-b130.db?busy_timeout=30000&journal_mode=WAL");
            File targetDir = new File("target");
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
            File realDb = new File("data/wrms.db");
            File testDb = new File("target/test-wrms-b130.db");
            if (realDb.exists()) {
                Files.copy(realDb.toPath(), testDb.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[Batch 130 Test] Isolated test replica created at target/test-wrms-b130.db");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

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

    private Employee testEmployee;
    private User testUser;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "admin",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );

        testEmployee = employeeRepository.findByEmployeeCode("EMP001").orElse(null);
        if (testEmployee == null) {
            testEmployee = new Employee();
            testEmployee.setEmployeeCode("EMP001");
            testEmployee.setFirstName("Test");
            testEmployee.setLastName("SyncUser");
            testEmployee.setEmail("test.sync@example.com");
            testEmployee.setGender(com.weeklyroster.entity.Gender.MALE);
            testEmployee.setActive(true);
            testEmployee = employeeRepository.save(testEmployee);
        }

        testUser = userRepository.findByUsername("EMP001").orElse(null);
        if (testUser == null) {
            testUser = new User();
            testUser.setUsername("EMP001");
            testUser.setPassword("password123");
            testUser.setRole(Role.ROLE_EMPLOYEE);
            testUser.setEmployee(testEmployee);
            testUser = userRepository.save(testUser);
        }
    }

    @Test
    @DisplayName("Test A: Recurring Weekly Preferences - Preferred and Do Not Assign")
    @Transactional
    void testRecurringPreferencesPreferredAndDoNotAssign() {
        // 1. Create a positive recurring preference: Monday MORNING PREFERRED
        RecurringShiftPreferenceRequest req1 = new RecurringShiftPreferenceRequest(
                testEmployee.getId(),
                DayOfWeek.MONDAY,
                ShiftType.MORNING,
                RecurringPreferenceType.PREFERRED,
                null,
                null,
                true,
                "Prefers morning on Mondays"
        );

        RecurringShiftPreferenceResponse resp1 = recurringShiftPreferenceService.createOrUpdate(req1, "admin");
        assertNotNull(resp1);
        assertEquals(DayOfWeek.MONDAY, resp1.dayOfWeek());
        assertEquals(ShiftType.MORNING, resp1.shiftType());
        assertEquals(RecurringPreferenceType.PREFERRED, resp1.preferenceType());
        assertTrue(resp1.active());

        // 2. Create a negative recurring preference: Friday NIGHT DO_NOT_ASSIGN
        RecurringShiftPreferenceRequest req2 = new RecurringShiftPreferenceRequest(
                testEmployee.getId(),
                DayOfWeek.FRIDAY,
                ShiftType.NIGHT,
                RecurringPreferenceType.DO_NOT_ASSIGN,
                null,
                null,
                true,
                "Cannot work Friday night"
        );

        RecurringShiftPreferenceResponse resp2 = recurringShiftPreferenceService.createOrUpdate(req2, "admin");
        assertNotNull(resp2);
        assertEquals(DayOfWeek.FRIDAY, resp2.dayOfWeek());
        assertEquals(ShiftType.NIGHT, resp2.shiftType());
        assertEquals(RecurringPreferenceType.DO_NOT_ASSIGN, resp2.preferenceType());
        assertTrue(resp2.active());

        // 3. Toggle active state
        RecurringShiftPreferenceResponse toggled = recurringShiftPreferenceService.toggleActive(resp2.id(), "admin");
        assertFalse(toggled.active());

        // Re-toggle to active
        RecurringShiftPreferenceResponse reToggled = recurringShiftPreferenceService.toggleActive(resp2.id(), "admin");
        assertTrue(reToggled.active());
    }

    @Test
    @DisplayName("Test B: Date-Specific Preferences - Preferred and Do Not Assign")
    @Transactional
    void testDateSpecificPreferencesPreferredAndDoNotAssign() {
        LocalDate testDatePreferred = LocalDate.of(2026, 11, 20);
        LocalDate testDateAvoid = LocalDate.of(2026, 11, 21);

        // 1. Create a positive date preference: 2026-11-20 EVENING PREFERRED
        DateShiftPreferenceRequest req1 = new DateShiftPreferenceRequest(
                testEmployee.getId(),
                testDatePreferred,
                ShiftType.EVENING,
                RecurringPreferenceType.PREFERRED,
                "Family dinner earlier, prefers evening"
        );

        DateShiftPreferenceResponse resp1 = dateShiftPreferenceService.createOrUpdate(req1, "admin");
        assertNotNull(resp1);
        assertEquals(testDatePreferred, resp1.preferenceDate());
        assertEquals(ShiftType.EVENING, resp1.shiftType());
        assertEquals(RecurringPreferenceType.PREFERRED, resp1.preferenceType());

        // 2. Create a negative date preference: 2026-11-21 NIGHT DO_NOT_ASSIGN
        DateShiftPreferenceRequest req2 = new DateShiftPreferenceRequest(
                testEmployee.getId(),
                testDateAvoid,
                ShiftType.NIGHT,
                RecurringPreferenceType.DO_NOT_ASSIGN,
                "Exam next day, do not assign night"
        );

        DateShiftPreferenceResponse resp2 = dateShiftPreferenceService.createOrUpdate(req2, "admin");
        assertNotNull(resp2);
        assertEquals(testDateAvoid, resp2.preferenceDate());
        assertEquals(ShiftType.NIGHT, resp2.shiftType());
        assertEquals(RecurringPreferenceType.DO_NOT_ASSIGN, resp2.preferenceType());

        // 3. Retrieve list for employee
        List<DateShiftPreferenceResponse> list = dateShiftPreferenceService.getDatePreferencesByEmployee(testEmployee.getId());
        assertFalse(list.isEmpty());
        assertTrue(list.stream().anyMatch(d -> d.preferenceDate().equals(testDateAvoid) && d.preferenceType() == RecurringPreferenceType.DO_NOT_ASSIGN));
        assertTrue(list.stream().anyMatch(d -> d.preferenceDate().equals(testDatePreferred) && d.preferenceType() == RecurringPreferenceType.PREFERRED));
    }

    @Test
    @DisplayName("Test C: Centralized Summary API for Admin and Employee")
    @Transactional
    void testCentralizedSummaryApi() {
        RecurringShiftPreferenceRequest recReq = new RecurringShiftPreferenceRequest(
                testEmployee.getId(),
                DayOfWeek.TUESDAY,
                ShiftType.GENERAL,
                RecurringPreferenceType.PREFERRED,
                null,
                null,
                true,
                "Regular general shift"
        );
        recurringShiftPreferenceService.createOrUpdate(recReq, "admin");

        LocalDate datePref = LocalDate.of(2026, 11, 25);
        DateShiftPreferenceRequest dateReq = new DateShiftPreferenceRequest(
                testEmployee.getId(),
                datePref,
                ShiftType.NIGHT,
                RecurringPreferenceType.DO_NOT_ASSIGN,
                "Doctor visit"
        );
        dateShiftPreferenceService.createOrUpdate(dateReq, "admin");

        // 1. Admin summary endpoint
        ResponseEntity<Map<String, Object>> adminResp = adminPreferenceController.getEmployeePreferenceSummary(testEmployee.getId());
        assertEquals(200, adminResp.getStatusCode().value());
        Map<String, Object> adminBody = adminResp.getBody();
        assertNotNull(adminBody);
        assertEquals(testEmployee.getId(), adminBody.get("employeeId"));
        assertTrue(adminBody.containsKey("recurringPreferences"));
        assertTrue(adminBody.containsKey("dateSpecificPreferences"));

        @SuppressWarnings("unchecked")
        List<RecurringShiftPreferenceResponse> recList = (List<RecurringShiftPreferenceResponse>) adminBody.get("recurringPreferences");
        assertTrue(recList.stream().anyMatch(r -> r.dayOfWeek() == DayOfWeek.TUESDAY && r.shiftType() == ShiftType.GENERAL));

        @SuppressWarnings("unchecked")
        List<DateShiftPreferenceResponse> dateList = (List<DateShiftPreferenceResponse>) adminBody.get("dateSpecificPreferences");
        assertTrue(dateList.stream().anyMatch(d -> d.preferenceDate().equals(datePref) && d.preferenceType() == RecurringPreferenceType.DO_NOT_ASSIGN));

        // 2. Employee self-service summary endpoint
        var auth = new UsernamePasswordAuthenticationToken(testUser.getUsername(), "password123",
                List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        ResponseEntity<Map<String, Object>> empResp = employeePreferenceController.getMyPreferenceSummary(auth);
        assertEquals(200, empResp.getStatusCode().value());
        Map<String, Object> empBody = empResp.getBody();
        assertNotNull(empBody);
        assertEquals(testEmployee.getId(), empBody.get("employeeId"));
        assertTrue(empBody.containsKey("recurringPreferences"));
        assertTrue(empBody.containsKey("dateSpecificPreferences"));
    }

    @Test
    @DisplayName("Test D: ApplicablePreference Evaluation Hierarchy & Avoid Shifts")
    void testApplicablePreferenceEvaluation() {
        LocalDate mon = LocalDate.of(2026, 11, 16); // Monday
        LocalDate fri = LocalDate.of(2026, 11, 20); // Friday

        // Setup maps:
        Map<DayOfWeek, ShiftType> recurringPreferred = Map.of(DayOfWeek.MONDAY, ShiftType.MORNING);
        Map<DayOfWeek, Set<ShiftType>> recurringAvoid = Map.of(DayOfWeek.FRIDAY, Set.of(ShiftType.NIGHT));

        Map<LocalDate, ShiftType> dateSpecificPreferred = Map.of(mon, ShiftType.EVENING);
        Map<LocalDate, Set<ShiftType>> dateSpecificAvoid = Map.of(fri, Set.of(ShiftType.EVENING));

        ApplicablePreference ap = ApplicablePreference.withDateAndRecurringShifts(
                testEmployee.getId(),
                dateSpecificPreferred,
                dateSpecificAvoid,
                recurringPreferred,
                recurringAvoid
        );

        // 1. Monday check: Date-specific EVENING must override recurring MORNING
        assertEquals(ShiftType.EVENING, ap.getTargetShiftForDate(mon));

        // 2. Friday check: No date-specific preferred, no recurring preferred -> falls back to null/base
        assertNull(ap.getTargetShiftForDate(fri));

        // 3. Avoid check on Friday:
        assertTrue(ap.isShiftAvoidedOnDate(ShiftType.NIGHT, fri), "Friday Night should be avoided due to recurring rule");
        assertTrue(ap.isShiftAvoidedOnDate(ShiftType.EVENING, fri), "Friday Evening should be avoided due to date-specific rule");
        assertFalse(ap.isShiftAvoidedOnDate(ShiftType.MORNING, fri), "Friday Morning is not avoided");

        // 4. Override: If date-specific has positive preference for a shift, it must NOT be avoided even if recurring avoided it
        LocalDate wed = LocalDate.of(2026, 11, 18);
        Map<DayOfWeek, Set<ShiftType>> recurringAvoidWed = Map.of(DayOfWeek.WEDNESDAY, Set.of(ShiftType.MORNING));
        Map<LocalDate, ShiftType> dateSpecificPreferredWed = Map.of(wed, ShiftType.MORNING);

        ApplicablePreference apOverride = ApplicablePreference.withDateAndRecurringShifts(
                testEmployee.getId(),
                dateSpecificPreferredWed,
                Collections.emptyMap(),
                Collections.emptyMap(),
                recurringAvoidWed
        );

        assertFalse(apOverride.isShiftAvoidedOnDate(ShiftType.MORNING, wed),
                "Date-specific preferred MORNING must override recurring avoid on same shift");
    }

    @Test
    @DisplayName("Test E: Admin Full Employee Profile contains synchronized preferences")
    @Transactional
    void testAdminFullProfileContainsPreferences() {
        RecurringShiftPreferenceRequest recReq = new RecurringShiftPreferenceRequest(
                testEmployee.getId(),
                DayOfWeek.THURSDAY,
                ShiftType.NIGHT,
                RecurringPreferenceType.DO_NOT_ASSIGN,
                null,
                null,
                true,
                "Thursday night restriction"
        );
        recurringShiftPreferenceService.createOrUpdate(recReq, "admin");

        LocalDate datePref = LocalDate.of(2026, 11, 28);
        DateShiftPreferenceRequest dateReq = new DateShiftPreferenceRequest(
                testEmployee.getId(),
                datePref,
                ShiftType.MORNING,
                RecurringPreferenceType.PREFERRED,
                "Saturday morning preference"
        );
        dateShiftPreferenceService.createOrUpdate(dateReq, "admin");

        AdminFullEmployeeProfileResponse profile = employeeService.getFullProfile(testEmployee.getId());
        assertNotNull(profile);
        assertEquals(testEmployee.getEmployeeCode(), profile.employeeCode());

        // Verify recurring preferences
        assertNotNull(profile.recurringPreferences());
        assertTrue(profile.recurringPreferences().stream().anyMatch(
                r -> r.dayOfWeek() == DayOfWeek.THURSDAY &&
                     r.shiftType() == ShiftType.NIGHT &&
                     r.preferenceType() == RecurringPreferenceType.DO_NOT_ASSIGN
        ));

        // Verify date-specific preferences
        assertNotNull(profile.dateSpecificPreferences());
        assertTrue(profile.dateSpecificPreferences().stream().anyMatch(
                d -> d.preferenceDate().equals(datePref) &&
                     d.shiftType() == ShiftType.MORNING &&
                     d.preferenceType() == RecurringPreferenceType.PREFERRED
        ));
    }
}
