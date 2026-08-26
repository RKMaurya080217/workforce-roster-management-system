package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;

import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class Batch22FreshResetTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private RosterVersionRepository versionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private ProfileChangeRequestRepository profileChangeRequestRepository;

    @Autowired
    private EmailDeliveryLogRepository emailDeliveryLogRepository;

    @Autowired
    private EmployeeActivityLogRepository activityLogRepository;

    @Autowired
    private ShiftHandoverRepository handoverRepository;

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterVersionService versionService;

    private void authenticateAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "N/A", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
    }

    private void authenticateEmployee(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "N/A", List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
        );
    }

    @Test
    @DisplayName("Batch 22 â€” Execute Clean Database Reset & Preserve Master Data")
    @Transactional
    @Commit
    void executeCleanReset() {
        System.out.println("=== STARTING BATCH 22 DATABASE RESET ===");

        // 1. Log Counts Before Cleanup
        long cyclesBefore = cycleRepository.count();
        long assignmentsBefore = assignmentRepository.count();
        long versionsBefore = versionRepository.count();
        long auditBefore = auditLogRepository.count();
        long notifsBefore = notificationRepository.count();
        long leavesBefore = leaveRequestRepository.count();
        long profileReqBefore = profileChangeRequestRepository.count();
        long emailsBefore = emailDeliveryLogRepository.count();
        long activitiesBefore = activityLogRepository.count();
        long handoversBefore = handoverRepository.count();

        System.out.println("Counts Before Cleanup:");
        System.out.println("  roster_cycles: " + cyclesBefore);
        System.out.println("  roster_assignments: " + assignmentsBefore);
        System.out.println("  roster_versions: " + versionsBefore);
        System.out.println("  audit_logs: " + auditBefore);
        System.out.println("  notifications: " + notifsBefore);
        System.out.println("  leave_requests: " + leavesBefore);
        System.out.println("  profile_change_requests: " + profileReqBefore);
        System.out.println("  email_delivery_logs: " + emailsBefore);
        System.out.println("  employee_activity_logs: " + activitiesBefore);
        System.out.println("  shift_handovers: " + handoversBefore);

        // 2. Perform targeted cleanup in strict foreign-key order
        jdbcTemplate.execute("DELETE FROM email_delivery_logs");
        jdbcTemplate.execute("DELETE FROM employee_activity_logs");
        jdbcTemplate.execute("DELETE FROM notifications");
        jdbcTemplate.execute("DELETE FROM audit_logs");
        jdbcTemplate.execute("DELETE FROM profile_change_requests");
        jdbcTemplate.execute("DELETE FROM leave_requests");
        jdbcTemplate.execute("DELETE FROM shift_handovers");
        jdbcTemplate.execute("DELETE FROM roster_overrides");
        jdbcTemplate.execute("DELETE FROM roster_versions");
        jdbcTemplate.execute("DELETE FROM roster_assignments");
        jdbcTemplate.execute("DELETE FROM roster_cycles");

        // 3. Verify Counts After Cleanup
        assertEquals(0, cycleRepository.count(), "roster_cycles must be 0");
        assertEquals(0, assignmentRepository.count(), "roster_assignments must be 0");
        assertEquals(0, versionRepository.count(), "roster_versions must be 0");
        assertEquals(0, auditLogRepository.count(), "audit_logs must be 0");
        assertEquals(0, notificationRepository.count(), "notifications must be 0");
        assertEquals(0, leaveRequestRepository.count(), "leave_requests must be 0");
        assertEquals(0, profileChangeRequestRepository.count(), "profile_change_requests must be 0");
        assertEquals(0, emailDeliveryLogRepository.count(), "email_delivery_logs must be 0");
        assertEquals(0, activityLogRepository.count(), "employee_activity_logs must be 0");
        assertEquals(0, handoverRepository.count(), "shift_handovers must be 0");

        // 4. Verify Master Data Preserved
        assertEquals(7, employeeRepository.count(), "All 7 employees must remain preserved");
        assertTrue(userRepository.count() >= 8, "Admin + 7 employee users must remain preserved");
        assertTrue(shiftRepository.count() >= 5, "All 5 shift types must remain preserved");

        // 5. Verify Target Employee Email
        Employee emp1 = employeeRepository.findByEmployeeCode("EMP001").orElseThrow();
        assertEquals("rkmaurya080217@gmail.com", emp1.getEmail(), "EMP001 email must remain rkmaurya080217@gmail.com");

        System.out.println("=== BATCH 22 DATABASE RESET COMPLETED SUCCESSFULLY ===");
    }

    @Test
    @DisplayName("Batch 22 â€” Verify Fresh First Roster Generation & Single Audit Entry")
    @Transactional
    void testFirstFreshRosterGeneration() {
        authenticateAdmin();
        LocalDate targetMonday = LocalDate.of(2026, 8, 24);

        // 1. Generate First Roster
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(targetMonday, GenerationMode.MANUAL);
        assertNotNull(cycle.id());
        assertEquals(employeeRepository.countByActiveTrue() * 7, cycle.assignments().size());

        // 2. Verify Exactly 1 Roster Cycle exists in test transaction
        assertEquals(1, cycleRepository.count());

        // 3. Verify Version History starts at Version 1
        List<RosterVersionResponse> versions = versionService.getCycleVersions(cycle.id());
        assertFalse(versions.isEmpty());
        assertEquals(1, versions.get(0).versionNumber());
    }
}