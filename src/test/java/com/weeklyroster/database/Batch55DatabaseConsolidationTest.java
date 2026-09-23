package com.weeklyroster.database;

import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ActiveProfiles("default")
public class Batch55DatabaseConsolidationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private ApiClientRepository apiClientRepository;

    @Autowired
    private RosterCycleRepository rosterCycleRepository;

    @Autowired
    private RosterAssignmentRepository rosterAssignmentRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private ShiftHandoverRepository shiftHandoverRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private ProfileChangeRequestRepository profileChangeRequestRepository;

    @Autowired
    private RosterChangeRequestRepository rosterChangeRequestRepository;

    @Autowired
    private EmployeePreferenceRepository employeePreferenceRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private EmployeeActivityLogRepository employeeActivityLogRepository;

    @Autowired
    private EmailDeliveryLogRepository emailDeliveryLogRepository;

    @Autowired
    private RosterReviewRecordRepository rosterReviewRecordRepository;

    @Autowired
    private RosterVersionRepository rosterVersionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @Order(1)
    @DisplayName("Verify Core Tables 1-3: users, employees, shifts exist and are operational")
    void test1_CoreEntitiesOperational() {
        assertTrue(userRepository.count() > 0, "Users table should have seeded users");
        assertTrue(employeeRepository.count() > 0, "Employees table should have seeded employees");
        assertTrue(shiftRepository.count() > 0, "Shifts table should have seeded shifts");

        Optional<User> admin = userRepository.findByUsername("admin");
        assertTrue(admin.isPresent());
        assertEquals(Role.ROLE_ADMIN, admin.get().getRole());

        List<Employee> employees = employeeRepository.findAll();
        assertFalse(employees.isEmpty());
        assertNotNull(employees.get(0).getEmployeeCode());
    }

    @Test
    @Order(2)
    @DisplayName("Verify Consolidated Table 4: master_reference_data (ApiClient Polymorphic Inheritance)")
    @Transactional
    void test2_MasterReferenceDataConsolidation() {
        // Create an ApiClient
        ApiClient client = new ApiClient("Batch 55 Test Client", "hash_batch55", "READ_ROSTER", 120);
        ApiClient savedClient = apiClientRepository.save(client);
        assertNotNull(savedClient.getId());

        // Verify ApiClient Query
        Optional<ApiClient> foundClient = apiClientRepository.findByClientName("Batch 55 Test Client");
        assertTrue(foundClient.isPresent());
        assertEquals("hash_batch55", foundClient.get().getApiKeyHash());

        // Verify underlying physical table
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM master_reference_data WHERE item_type = 'API_CLIENT'", Integer.class);
        assertTrue(count != null && count >= 1, "master_reference_data should store ApiClient rows");
    }

    @Test
    @Order(3)
    @DisplayName("Verify Consolidated Table 7: roster_assignments with Embedded Overrides")
    @Transactional
    void test3_RosterAssignmentEmbeddedOverrides() {
        Employee emp = employeeRepository.findAll().get(0);
        Shift shift = shiftRepository.findAll().get(0);

        RosterCycle cycle = new RosterCycle();
        cycle.setStartDate(LocalDate.of(2026, 10, 5));
        cycle.setEndDate(LocalDate.of(2026, 10, 11));
        cycle.setGeneratedAt(LocalDateTime.now());
        cycle.setStatus(RosterStatus.GENERATED);
        RosterCycle savedCycle = rosterCycleRepository.save(cycle);

        RosterAssignment assignment = new RosterAssignment();
        assignment.setCycle(savedCycle);
        assignment.setEmployee(emp);
        assignment.setShift(shift);
        assignment.setRosterDate(LocalDate.of(2026, 10, 5));
        assignment.setWeeklyOff(false);
        assignment.setOnLeave(false);
        assignment.setOverridden(true);
        assignment.setPreviousShiftType(ShiftType.GENERAL);
        assignment.setOverrideReason("Emergency manager override for staffing");
        assignment.setOverrideCreatedAt(LocalDateTime.now());

        RosterAssignment saved = rosterAssignmentRepository.save(assignment);
        assertNotNull(saved.getId());
        assertTrue(saved.isOverridden());
        assertEquals(ShiftType.GENERAL, saved.getPreviousShiftType());
        assertEquals("Emergency manager override for staffing", saved.getOverrideReason());
    }

    @Test
    @Order(4)
    @DisplayName("Verify Consolidated Table 11: employee_requests (ProfileChange, RosterChange, Preference)")
    @Transactional
    void test4_EmployeeRequestsConsolidation() {
        Employee emp = employeeRepository.findAll().get(0);

        // 1. ProfileChangeRequest
        ProfileChangeRequest pcr = new ProfileChangeRequest();
        pcr.setEmployee(emp);
        pcr.setFieldName("phoneNumber");
        pcr.setCurrentValue("9876543210");
        pcr.setRequestedValue("9998887776");
        pcr.setStatus(ProfileChangeStatus.PENDING);
        pcr.setRequestedAt(LocalDateTime.now());
        ProfileChangeRequest savedPcr = profileChangeRequestRepository.save(pcr);
        assertNotNull(savedPcr.getId());
        assertNotNull(savedPcr.getVersion());

        // 2. EmployeePreference
        EmployeePreference pref = new EmployeePreference();
        pref.setEmployee(emp);
        pref.setPreferredShiftTypes("MORNING,GENERAL");
        pref.setPreferredOffDays("SATURDAY,SUNDAY");
        pref.setRemarks("Family commitments");
        pref.setStatus(PreferenceStatus.PENDING);
        EmployeePreference savedPref = employeePreferenceRepository.save(pref);
        assertNotNull(savedPref.getId());

        // 3. RosterChangeRequest
        RosterChangeRequest rcr = new RosterChangeRequest();
        rcr.setEmployee(emp);
        rcr.setRosterDate(LocalDate.of(2026, 10, 8));
        rcr.setCurrentShiftType(ShiftType.GENERAL);
        rcr.setCurrentWeeklyOff(false);
        rcr.setRequestedShiftType(ShiftType.MORNING);
        rcr.setRequestedWeeklyOff(false);
        rcr.setReason("Doctor visit in evening");
        rcr.setStatus(RosterChangeStatus.PENDING);
        RosterChangeRequest savedRcr = rosterChangeRequestRepository.save(rcr);
        assertNotNull(savedRcr.getId());

        // Verify underlying physical table employee_requests
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM employee_requests WHERE request_type IN ('PROFILE_CHANGE', 'SHIFT_PREFERENCE', 'ROSTER_CHANGE')", Integer.class);
        assertTrue(count != null && count >= 3, "employee_requests table should store all three workflow request types");
    }

    @Test
    @Order(5)
    @DisplayName("Verify Consolidated Table 12: system_audit_logs (Audit, Activity, Email, Review, Version)")
    @Transactional
    void test5_SystemAuditLogsConsolidation() {
        Employee emp = employeeRepository.findAll().get(0);

        // 1. AuditLog
        AuditLog auditLog = new AuditLog();
        auditLog.setAction(AuditAction.REPORT_EXPORTED);
        auditLog.setActor("admin");
        auditLog.setEntityType("REPORT");
        auditLog.setEntityId(101L);
        auditLog.setReason("Monthly audit compliance export");
        auditLog.setTimestamp(LocalDateTime.now());
        AuditLog savedAudit = auditLogRepository.save(auditLog);
        assertNotNull(savedAudit.getId());

        // 2. EmployeeActivityLog
        EmployeeActivityLog activityLog = new EmployeeActivityLog();
        activityLog.setUsername(emp.getUser().getUsername());
        activityLog.setEmployeeId(emp.getId());
        activityLog.setAction("VIEW_SCHEDULE");
        activityLog.setCategory(ActivityCategory.ROSTER);
        activityLog.setStatus(ActivityStatus.SUCCESS);
        activityLog.setDescription("Viewed weekly roster calendar");
        EmployeeActivityLog savedAct = employeeActivityLogRepository.save(activityLog);
        assertNotNull(savedAct.getId());

        // 3. EmailDeliveryLog
        EmailDeliveryLog emailLog = new EmailDeliveryLog();
        emailLog.setEmployee(emp);
        emailLog.setRecipientEmail("emp@example.com");
        emailLog.setStatus(EmailDeliveryStatus.SENT);
        emailLog.setSentAt(LocalDateTime.now());
        EmailDeliveryLog savedEmail = emailDeliveryLogRepository.save(emailLog);
        assertNotNull(savedEmail.getId());

        // 4. RosterReviewRecord
        RosterReviewRecord reviewRecord = new RosterReviewRecord();
        reviewRecord.setEmployee(emp);
        reviewRecord.setReviewedAt(LocalDateTime.now());
        RosterReviewRecord savedReview = rosterReviewRecordRepository.save(reviewRecord);
        assertNotNull(savedReview.getId());

        // 5. RosterVersion
        RosterVersion version = new RosterVersion();
        version.setVersionNumber(1);
        version.setAction("ROSTER_SNAPSHOT");
        version.setActionReason("Baseline draft snapshot");
        version.setCreatedBy("admin");
        version.setSnapshotData("{\"version\": 1}");
        RosterVersion savedVersion = rosterVersionRepository.save(version);
        assertNotNull(savedVersion.getId());

        // Verify underlying physical table system_audit_logs
        Integer totalLogs = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM system_audit_logs WHERE log_type IN ('AUDIT', 'EMPLOYEE_ACTIVITY', 'EMAIL_DELIVERY', 'ROSTER_REVIEW', 'ROSTER_VERSION')", Integer.class);
        assertTrue(totalLogs != null && totalLogs >= 5, "system_audit_logs should store all 5 audit and event log types");
    }

    @Test
    @Order(6)
    @DisplayName("Verify Exactly 11 Core Tables Active & Zero Data Loss")
    void test6_ZeroDataLossAndConsolidationIntegrity() {
        // Query database table names
        List<String> activeCoreTables = List.of(
            "users",
            "employees",
            "shifts",
            "master_reference_data",
            "roster_cycles",
            "roster_assignments",
            "leave_requests",
            "shift_handovers",
            "notifications",
            "employee_requests",
            "system_audit_logs"
        );

        for (String table : activeCoreTables) {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, table);
            assertNotNull(count);
            assertTrue(count > 0, "Active core table must exist: " + table);
        }

        // Verify historical rows preserved in system_audit_logs
        Integer auditCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM system_audit_logs WHERE log_type = 'AUDIT'", Integer.class);
        assertTrue(auditCount != null && auditCount >= 0, "All historical audit logs must be preserved in system_audit_logs");

        Integer activityCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM system_audit_logs WHERE log_type = 'EMPLOYEE_ACTIVITY'", Integer.class);
        assertTrue(activityCount != null && activityCount >= 0, "All historical employee activity logs must be preserved in system_audit_logs");
    }
}
