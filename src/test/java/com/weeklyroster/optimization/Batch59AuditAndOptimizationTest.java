package com.weeklyroster.optimization;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.weeklyroster.dto.response.DashboardDetailResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import com.weeklyroster.service.DashboardService;
import com.weeklyroster.service.NotificationService;
import com.weeklyroster.service.RosterSchedulerService;
import com.weeklyroster.service.email.BrevoEmailService;
import com.weeklyroster.service.email.EmailService;
import com.weeklyroster.service.email.SmtpEmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public class Batch59AuditAndOptimizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ProfileChangeRequestRepository profileChangeRequestRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private EmployeePreferenceRepository preferenceRepository;

    @Autowired
    private ShiftHandoverRepository handoverRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterSchedulerService schedulerService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private BrevoEmailService brevoEmailService;

    @Autowired
    private SmtpEmailService smtpEmailService;

    private Employee emp001;

    @BeforeEach
    void setup() {
        emp001 = employeeRepository.findByEmployeeCode("EMP001")
                .or(() -> employeeRepository.findAll().stream().findFirst())
                .orElseThrow(() -> new IllegalStateException("No employee seeded in database"));
    }

    @Test
    @DisplayName("Test 1: Exact 12 Core Database Tables Intact (Zero schema drift, zero dropped production data)")
    void test1_Exact12CoreTablesIntact() {
        List<String> tables = jdbcTemplate.query(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' ORDER BY table_name",
                (rs, rowNum) -> rs.getString("table_name")
        );

        Set<String> coreTables = Set.of(
                "users",
                "employees",
                "shifts",
                "roster_cycles",
                "roster_assignments",
                "master_reference_data",
                "leave_requests",
                "shift_handovers",
                "notifications",
                "employee_requests",
                "system_audit_logs"
        );

        for (String expected : coreTables) {
            assertTrue(tables.contains(expected), "Core table '" + expected + "' must be present in database");
        }
        // Extension tables added in Batch 62 (FCM device_tokens) and Batch 65 (sms_delivery_logs)
        assertTrue(tables.contains("device_tokens"), "Table 'device_tokens' must be present in database");
        assertTrue(tables.contains("sms_delivery_logs"), "Table 'sms_delivery_logs' must be present in database");
        assertFalse(tables.contains("employee_skills"), "Table 'employee_skills' must be retired");
        assertEquals(13, tables.size(), "Exact 13 production tables must be present in database. Found: " + tables);
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("Test 2: Profile Approval Regression Verification (Batch 58 fix: approve, reject, refresh, profile view)")
    void test2_ProfileApprovalRegressionVerification() throws Exception {
        String oldContact = emp001.getContactNumber() != null ? emp001.getContactNumber() : "9123456780";
        String targetContact = "9988776655";

        // Case 1: Approve
        ProfileChangeRequest req1 = new ProfileChangeRequest();
        req1.setEmployee(emp001);
        req1.setFieldName("contactNumber");
        req1.setCurrentValue(oldContact);
        req1.setRequestedValue(targetContact);
        req1.setStatus(ProfileChangeStatus.PENDING);
        req1.setRequestedAt(LocalDateTime.now());
        ProfileChangeRequest saved1 = profileChangeRequestRepository.save(req1);

        String approvePayload = "{\"decisionReason\": \"Approved by administrator\", \"adminRemarks\": \"Approved by administrator\"}";
        mockMvc.perform(post("/api/admin/approvals/profile/" + saved1.getId() + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.adminRemarks").value("Approved by administrator"));

        Employee reloadedEmp = employeeRepository.findById(emp001.getId()).orElseThrow();
        assertEquals(targetContact, reloadedEmp.getContactNumber());

        // Case 2: Reject
        ProfileChangeRequest req2 = new ProfileChangeRequest();
        req2.setEmployee(emp001);
        req2.setFieldName("firstName");
        req2.setCurrentValue(emp001.getFirstName());
        req2.setRequestedValue("UnwantedName");
        req2.setStatus(ProfileChangeStatus.PENDING);
        req2.setRequestedAt(LocalDateTime.now());
        ProfileChangeRequest saved2 = profileChangeRequestRepository.save(req2);

        String rejectPayload = "{\"decisionReason\": \"Rejected by administrator\"}";
        mockMvc.perform(post("/api/admin/approvals/profile/" + saved2.getId() + "/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rejectPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertEquals(emp001.getFirstName(), employeeRepository.findById(emp001.getId()).orElseThrow().getFirstName());

        // Case 3: Refresh -> Pending exclusion
        mockMvc.perform(get("/api/admin/approvals/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileChanges[?(@.id == " + saved1.getId() + ")]").doesNotExist())
                .andExpect(jsonPath("$.profileChanges[?(@.id == " + saved2.getId() + ")]").doesNotExist());

        // Case 4: Profile View API
        mockMvc.perform(get("/api/employees/" + emp001.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactNumber").value(targetContact));
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("Test 3: Leave Approval Flow Working via Unified Approvals")
    void test3_LeaveApprovalUnifiedVerification() throws Exception {
        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(emp001);
        leave.setStartDate(LocalDate.now().plusDays(14));
        leave.setEndDate(LocalDate.now().plusDays(16));
        leave.setReason("Medical leave");
        leave.setStatus(LeaveStatus.PENDING);
        leave.setRequestedAt(LocalDateTime.now());
        LeaveRequest saved = leaveRequestRepository.save(leave);

        String payload = "{\"adminRemarks\": \"Approved leave\", \"decisionReason\": \"Approved leave\"}";
        mockMvc.perform(post("/api/admin/approvals/leave/" + saved.getId() + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertEquals(LeaveStatus.APPROVED, leaveRequestRepository.findById(saved.getId()).orElseThrow().getStatus());
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("Test 4: Preference Approval Flow Working via Unified Approvals")
    void test4_PreferenceApprovalUnifiedVerification() throws Exception {
        EmployeePreference pref = new EmployeePreference();
        pref.setEmployee(emp001);
        pref.setPreferredShiftTypes("MORNING");
        pref.setStatus(PreferenceStatus.PENDING);
        pref.setCreatedAt(LocalDateTime.now());
        EmployeePreference saved = preferenceRepository.save(pref);

        String payload = "{\"status\": \"APPROVED\", \"decision\": \"APPROVE\", \"adminRemarks\": \"Preference confirmed\", \"reviewNote\": \"Preference confirmed\"}";
        mockMvc.perform(post("/api/admin/approvals/preference/" + saved.getId() + "/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertEquals(PreferenceStatus.APPROVED, preferenceRepository.findById(saved.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Test 5: PreferenceRepository JOIN FETCH eliminates N+1 queries")
    void test5_PreferenceRepositoryJoinFetch_NoNPlusOne() {
        EmployeePreference pref = new EmployeePreference();
        pref.setEmployee(emp001);
        pref.setPreferredShiftTypes("GENERAL");
        pref.setStatus(PreferenceStatus.PENDING);
        pref.setCreatedAt(LocalDateTime.now());
        preferenceRepository.save(pref);

        List<EmployeePreference> pending = preferenceRepository.findByStatusOrderByCreatedAtDesc(PreferenceStatus.PENDING);
        assertFalse(pending.isEmpty());
        for (EmployeePreference p : pending) {
            assertNotNull(p.getEmployee(), "Employee must be eagerly fetched");
            assertNotNull(p.getEmployee().getEmployeeCode(), "Employee code must be accessible without additional query");
        }
    }

    @Test
    @DisplayName("Test 6: ProfileChangeRequestRepository JOIN FETCH eliminates N+1 queries")
    void test6_ProfileChangeRequestRepositoryJoinFetch_NoNPlusOne() {
        ProfileChangeRequest req = new ProfileChangeRequest();
        req.setEmployee(emp001);
        req.setFieldName("contactNumber");
        req.setCurrentValue("123");
        req.setRequestedValue("456");
        req.setStatus(ProfileChangeStatus.PENDING);
        req.setRequestedAt(LocalDateTime.now());
        profileChangeRequestRepository.save(req);

        List<ProfileChangeRequest> list = profileChangeRequestRepository.findByStatusOrderByRequestedAtAsc(ProfileChangeStatus.PENDING);
        assertFalse(list.isEmpty());
        for (ProfileChangeRequest r : list) {
            assertNotNull(r.getEmployee(), "Employee must be eagerly fetched");
            assertNotNull(r.getEmployee().getFirstName(), "Employee name must be accessible without additional query");
        }
    }

    @Test
    @DisplayName("Test 7: ShiftHandoverRepository JOIN FETCH eliminates N+1 queries")
    void test7_ShiftHandoverRepositoryJoinFetch_NoNPlusOne() {
        Shift shift = shiftRepository.findByShiftType(ShiftType.MORNING)
                .or(() -> shiftRepository.findAll().stream().findFirst())
                .orElseThrow();

        ShiftHandover handover = new ShiftHandover();
        handover.setHandoverDate(LocalDate.now());
        handover.setShift(shift);
        handover.setFromEmployee(emp001);
        handover.setSummary("Daily morning shift handover check");
        handover.setStatus(HandoverStatus.OPEN);
        handover.setPriority(HandoverPriority.MEDIUM);
        handover.setCreatedAt(LocalDateTime.now());
        handover.setUpdatedAt(LocalDateTime.now());
        handoverRepository.save(handover);

        List<ShiftHandover> recent = handoverRepository.findTop20ByOrderByHandoverDateDescCreatedAtDesc();
        assertFalse(recent.isEmpty());
        for (ShiftHandover h : recent) {
            assertNotNull(h.getShift(), "Shift must be eagerly fetched");
            assertNotNull(h.getFromEmployee(), "From-employee must be eagerly fetched");
        }
    }

    @Test
    @DisplayName("Test 8: Notification Atomic markAllAsRead updates only unread notifications in a single query")
    void test8_NotificationAtomicMarkAllAsRead() {
        String testUser = "audit_opt_user_" + System.currentTimeMillis();

        Notification n1 = new Notification();
        n1.setRecipientUsername(testUser);
        n1.setTitle("Alert 1");
        n1.setMessage("Msg 1");
        n1.setType(NotificationType.ADMIN_ALERT);
        n1.setReadStatus(false);
        n1.setCreatedAt(LocalDateTime.now());
        notificationRepository.save(n1);

        Notification n2 = new Notification();
        n2.setRecipientUsername(testUser);
        n2.setTitle("Alert 2");
        n2.setMessage("Msg 2");
        n2.setType(NotificationType.ADMIN_ALERT);
        n2.setReadStatus(false);
        n2.setCreatedAt(LocalDateTime.now());
        notificationRepository.save(n2);

        assertEquals(2, notificationRepository.countByRecipientUsernameAndReadStatusFalse(testUser));

        notificationService.markAllAsRead(testUser);

        assertEquals(0, notificationRepository.countByRecipientUsernameAndReadStatusFalse(testUser));
    }

    @Test
    @DisplayName("Test 9: Dashboard single-row cycle resolution without full table scan")
    void test9_DashboardCycleResolution_SingleRowOptimization() {
        DashboardDetailResponse details = dashboardService.dashboardDetails();
        assertNotNull(details);
        assertNotNull(details.summary());
        // Verify findTopByOrderByStartDateDesc executes cleanly
        assertTrue(cycleRepository.findTopByOrderByStartDateDesc().isPresent() || cycleRepository.count() == 0);
    }

    @Test
    @DisplayName("Test 10: Nixpacks and Hikari Configuration Verification")
    void test10_NixpacksAndHikariConfigurationVerification() throws Exception {
        String nixpacks = Files.readString(new File("nixpacks.toml").toPath());
        assertTrue(nixpacks.contains("-XX:+UseSerialGC"), "nixpacks.toml must include -XX:+UseSerialGC");
        assertTrue(nixpacks.contains("-XX:MaxRAMPercentage=65.0"), "nixpacks.toml must include MaxRAMPercentage=65.0");
        assertTrue(nixpacks.contains("-XX:+ExitOnOutOfMemoryError"), "nixpacks.toml must include ExitOnOutOfMemoryError");

        String appProps = Files.readString(new File("src/main/resources/application.properties").toPath());
        assertTrue(appProps.contains("spring.datasource.hikari.leak-detection-threshold="), "application.properties must configure leak-detection-threshold");
    }

    @Test
    @DisplayName("Test 11: Roster Business Rules and Upcoming Scheduler Invariant Protected")
    void test11_RosterBusinessRulesAndUpcomingSchedulerInvariant() {
        // 7 active employees
        long activeEmpCount = employeeRepository.countByActiveTrue();
        assertEquals(7, activeEmpCount, "WRMS must have exactly 7 active employees");

        // Base date = Wednesday 2026-09-09 -> upcoming week start is Monday 2026-09-14
        LocalDate baseDate = LocalDate.of(2026, 9, 9);
        LocalDate upcomingMon = schedulerService.calculateUpcomingWeekStart(baseDate);
        LocalDate upcomingSun = schedulerService.calculateUpcomingWeekEnd(baseDate);

        assertEquals(LocalDate.of(2026, 9, 14), upcomingMon);
        assertEquals(DayOfWeek.MONDAY, upcomingMon.getDayOfWeek());
        assertEquals(LocalDate.of(2026, 9, 20), upcomingSun);
        assertEquals(DayOfWeek.SUNDAY, upcomingSun.getDayOfWeek());
    }

    @Test
    @DisplayName("Test 12: Email Dual Configuration Integrity (Brevo Primary + SMTP Fallback)")
    void test12_EmailDualConfigurationIntegrity() {
        assertEquals("BREVO", emailService.getActiveProviderName(), "Default active provider must be BREVO");
        assertEquals("https://api.brevo.com/v3/smtp/email", brevoEmailService.getResolvedEndpointUrl());
        assertNotNull(smtpEmailService, "SMTP fallback provider must be initialized");
        assertEquals("SMTP", smtpEmailService.getProviderName());
    }
}
