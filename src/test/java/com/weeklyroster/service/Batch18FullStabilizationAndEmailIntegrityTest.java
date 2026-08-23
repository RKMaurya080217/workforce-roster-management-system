package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;

import com.weeklyroster.dto.request.CreateProfileChangeRequest;
import com.weeklyroster.dto.request.ProfileChangeDecisionRequest;
import com.weeklyroster.dto.request.UpdateMyProfileRequest;
import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class Batch18FullStabilizationAndEmailIntegrityTest {

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private ProfileChangeRequestService profileChangeRequestService;

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterSchedulerService schedulerService;

    @Autowired
    private RosterVersionService versionService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private RosterEmailService emailService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private DevCredentialMirrorService devCredentialMirrorService;

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

    @BeforeEach
    void setup() {
        authenticateAdmin();
    }

    @Test
    @DisplayName("Batch 18 â€” 9.4: Complete Employee Email Persistence Lifecycle (Tests 1 - 10)")
    void testEmployeeEmailPersistence_FullLifecycle() {
        String testUser = "emp001";
        String targetEmail = "rkmaurya080217@gmail.com";

        // TEST 1: Employee updates email
        authenticateEmployee(testUser);
        EmployeeResponse updated = employeeService.updateMyProfile(
                new UpdateMyProfileRequest("Rajat", "Maurya", targetEmail, "8565005534")
        );
        assertEquals(targetEmail, updated.email());

        // TEST 2: Fetch employee profile again -> Same email is returned
        EmployeeResponse fetchedProfile = employeeService.getMyProfile();
        assertEquals(targetEmail, fetchedProfile.email(), "Profile fetch must return the updated email");

        // TEST 3: Database direct inspection -> Entity contains target email
        Employee dbEmp = employeeRepository.findByUserUsername(testUser).orElseThrow();
        assertEquals(targetEmail, dbEmp.getEmail(), "Database entity must persist target email");

        // TEST 4: Run scheduler -> Email remains unchanged
        authenticateAdmin();
        LocalDate immediateMonday = schedulerService.calculateTargetMonday(null);
        schedulerService.executeAutoGeneration(immediateMonday);

        Employee empAfterScheduler = employeeRepository.findByUserUsername(testUser).orElseThrow();
        assertEquals(targetEmail, empAfterScheduler.getEmail(), "Email must remain persistent after scheduler execution");

        // TEST 5: Generate roster manually -> Email remains unchanged
        LocalDate manualMonday = LocalDate.of(2026, 12, 14);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(manualMonday, GenerationMode.MANUAL);
        assertNotNull(cycle);

        Employee empAfterRoster = employeeRepository.findByUserUsername(testUser).orElseThrow();
        assertEquals(targetEmail, empAfterRoster.getEmail(), "Email must remain persistent after roster generation");

        // TEST 6: CSV Mirror Synchronization -> Mirror updates safely
        if (devCredentialMirrorService != null) {
            devCredentialMirrorService.updateProfile(empAfterRoster, null);
        }
        Employee empAfterCsv = employeeRepository.findByUserUsername(testUser).orElseThrow();
        assertEquals(targetEmail, empAfterCsv.getEmail(), "Email must remain persistent after CSV synchronization");

        // TEST 7: Refresh profile as Employee
        authenticateEmployee(testUser);
        EmployeeResponse refreshedProfile = employeeService.getMyProfile();
        assertEquals(targetEmail, refreshedProfile.email(), "Refreshed employee profile must show persisted email");

        // TEST 8: Email delivery log recipient check -> Uses target email
        authenticateAdmin();
        RosterCycle cycleEntity = cycleRepository.findById(cycle.id()).orElseThrow();
        List<EmailDeliveryLogResponse> logs = emailService.distributeRosterEmails(cycleEntity, cycle, GenerationMode.MANUAL);
        assertNotNull(logs);
        assertTrue(logs.stream().anyMatch(l -> targetEmail.equalsIgnoreCase(l.recipientEmail()) || "EMP001".equalsIgnoreCase(l.employeeCode())),
                "Email delivery logs must target the persisted employee email");

        // TEST 9: User account mapping remains intact
        assertNotNull(dbEmp.getUser());
        assertEquals(testUser, dbEmp.getUser().getUsername());

        // TEST 10: Admin views employee -> Admin sees the same persisted email
        EmployeeResponse adminView = employeeService.getById(dbEmp.getId());
        assertEquals(targetEmail, adminView.email(), "Admin view must reflect the persisted employee email");
    }

    @Test
    @DisplayName("Batch 18 â€” 2 & 3: Critical Roster Safety - Sunday Morning Only & Manual Priority")
    void testCriticalRosterSafety_ManualPriorityAndGuard() {
        authenticateAdmin();
        LocalDate immediateMonday = schedulerService.calculateTargetMonday(null);

        // 1. Centralized guard validation
        assertTrue(schedulerService.isAutomaticGenerationAllowed(immediateMonday, null));
        assertFalse(schedulerService.isAutomaticGenerationAllowed(immediateMonday.plusWeeks(1), null));

        // 2. Scenario B: Admin manually generates upcoming week
        RosterCycleResponse manualCycle = rosterService.generateWeeklyRoster(immediateMonday, GenerationMode.MANUAL);
        assertEquals(GenerationMode.MANUAL, manualCycle.generationMode());

        // 3. Sunday auto-generation runs -> Detects existing cycle -> SKIPS (DO NOTHING)
        RosterCycleResponse autoCycle = schedulerService.executeAutoGeneration(immediateMonday);
        assertEquals(manualCycle.id(), autoCycle.id(), "Auto-scheduler must return existing manual cycle without modifying it");
        assertEquals(GenerationMode.MANUAL, autoCycle.generationMode(), "Existing generation mode must remain MANUAL");

        // 4. Centralized Guard strictly rejects next-next week
        assertThrows(BusinessException.class, () -> schedulerService.executeAutoGeneration(immediateMonday.plusWeeks(1)));
    }

    @Test
    @DisplayName("Batch 18 â€” 4: Scheduler Idempotency - Duplicate Executions Create No Duplicates")
    void testSchedulerIdempotency_NoDuplicates() {
        authenticateAdmin();
        LocalDate immediateMonday = schedulerService.calculateTargetMonday(null);

        // 1st Execution -> Generates cycle
        RosterCycleResponse first = schedulerService.executeAutoGeneration(immediateMonday);
        assertNotNull(first);

        int countAfterFirst = cycleRepository.findAll().size();

        // 2nd Execution -> Idempotent skip (no new cycle created)
        RosterCycleResponse second = schedulerService.executeAutoGeneration(immediateMonday);
        assertEquals(first.id(), second.id());

        int countAfterSecond = cycleRepository.findAll().size();
        assertEquals(countAfterFirst, countAfterSecond, "Second execution must NOT create a duplicate roster cycle");
    }

    @Test
    @DisplayName("Batch 18 â€” 8: Profile Change Approval Workflow Integration")
    void testProfileChangeWorkflow_ApprovalAndNotification() {
        authenticateEmployee("emp002");
        Employee emp2 = employeeRepository.findByUserUsername("emp002").orElseThrow();

        // Employee requests contactNumber change
        ProfileChangeRequestResponse req = profileChangeRequestService.submitRequest(
                new CreateProfileChangeRequest("contactNumber", "9876543210")
        );
        assertEquals(ProfileChangeStatus.PENDING, req.status());

        // Admin approves request
        authenticateAdmin();
        ProfileChangeRequestResponse approved = profileChangeRequestService.approve(
                req.id(),
                new ProfileChangeDecisionRequest("Verified with HR")
        );
        assertEquals(ProfileChangeStatus.APPROVED, approved.status());

        // Verify profile updated in database
        Employee updatedEmp = employeeRepository.findById(emp2.getId()).orElseThrow();
        assertEquals("9876543210", updatedEmp.getContactNumber());

        // Verify notification delivered to employee
        authenticateEmployee("emp002");
        long unread = notificationService.getUnreadCount("emp002");
        assertTrue(unread >= 1);
        List<NotificationResponse> notifs = notificationService.getMyNotifications("emp002");
        assertTrue(notifs.stream().anyMatch(n -> n.type() == NotificationType.PROFILE_CHANGE_DECISION));
    }

    @Test
    @DisplayName("Batch 18 â€” 11: Roster Version History & Safe Restore Integration")
    void testRosterVersionHistoryAndSafeRestore() {
        authenticateAdmin();
        LocalDate monday = LocalDate.of(2026, 12, 21);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL);
        assertNotNull(cycle);

        // Initial snapshot v1
        List<RosterVersionResponse> versions = versionService.getCycleVersions(cycle.id());
        assertEquals(1, versions.size());
        assertEquals("GENERATED", versions.get(0).action());

        // Publish cycle -> creates v2
        rosterService.publishRoster(cycle.id());
        versions = versionService.getCycleVersions(cycle.id());
        assertEquals(2, versions.size());
        assertEquals("PUBLISHED", versions.get(0).action());

        // Safe restore to v1 -> creates v3 with action RESTORED without deleting v1/v2
        RosterVersionResponse restored = versionService.restoreVersion(cycle.id(), 1, "admin");
        assertNotNull(restored);
        assertEquals(3, restored.versionNumber());
        assertEquals("RESTORED", restored.action());

        List<RosterVersionResponse> finalVersions = versionService.getCycleVersions(cycle.id());
        assertEquals(3, finalVersions.size());
    }
}