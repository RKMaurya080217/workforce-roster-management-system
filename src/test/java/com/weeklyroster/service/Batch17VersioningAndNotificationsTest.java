package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;

import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class Batch17VersioningAndNotificationsTest {

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterVersionService versionService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private RosterSchedulerService schedulerService;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterVersionRepository versionRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

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
    @DisplayName("Batch 17: Version Creation - Generation creates v1 snapshot with status and mode")
    void testVersionCreation_OnRosterGeneration() {
        LocalDate monday = LocalDate.of(2026, 11, 2);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL);
        assertNotNull(cycle.id());

        List<RosterVersionResponse> versions = versionService.getCycleVersions(cycle.id());
        assertFalse(versions.isEmpty(), "At least one version should be recorded upon generation");

        RosterVersionResponse v1 = versions.get(versions.size() - 1);
        assertEquals(1, v1.versionNumber());
        assertEquals("GENERATED", v1.action());
        assertEquals("MANUAL", v1.generationMode());
        assertNotNull(v1.snapshotData());
        assertTrue(v1.snapshotData().contains("employeeCode"));
    }

    @Test
    @DisplayName("Batch 17: Version Numbering - Sequential version increments on snapshots and publish")
    void testSequentialVersionIncrement() {
        LocalDate monday = LocalDate.of(2026, 11, 9);
        RosterCycleResponse cycleResp = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL);
        RosterCycle cycle = cycleRepository.findById(cycleResp.id()).orElseThrow();

        // 1. Record override snapshot -> creates v2
        versionService.recordVersionSnapshot(cycle, "OVERRIDE_APPLIED", "Shift override on Monday", "admin");

        // 2. Publish cycle -> creates v3
        rosterService.publishRoster(cycle.getId());

        List<RosterVersionResponse> versions = versionService.getCycleVersions(cycle.getId());
        assertTrue(versions.size() >= 3, "Should have at least 3 versions (Generate, Override, Publish)");

        RosterVersionResponse latest = versions.get(0);
        assertEquals("PUBLISHED", latest.action());
        assertEquals("PUBLISHED", latest.status());
    }

    @Test
    @DisplayName("Batch 17: Version Comparison - Diffs detected accurately between versions")
    void testVersionComparisonDiff() {
        LocalDate monday = LocalDate.of(2026, 11, 16);
        RosterCycleResponse cycleResp = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL);
        RosterCycle cycle = cycleRepository.findById(cycleResp.id()).orElseThrow();

        // Simulate snapshot 2 with an updated shift change recorded
        versionService.recordVersionSnapshot(cycle, "OVERRIDE_APPLIED", "Recorded schedule modification", "admin");

        VersionComparisonResponse diff = versionService.compareVersions(cycle.getId(), 1, 2);
        assertNotNull(diff);
        assertEquals(1, diff.version1Number());
        assertEquals(2, diff.version2Number());
        assertNotNull(diff.diffs());
        assertFalse(diff.diffs().isEmpty());
    }

    @Test
    @DisplayName("Batch 17: Safe Restore - Restores historical state and creates new RESTORED version")
    void testSafeRestore() {
        LocalDate monday = LocalDate.of(2026, 11, 23);
        RosterCycleResponse cycleResp = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL);
        RosterCycle cycle = cycleRepository.findById(cycleResp.id()).orElseThrow();

        // Create v2
        versionService.recordVersionSnapshot(cycle, "OVERRIDE_APPLIED", "Modified shifts", "admin");

        // Restore to v1 -> should create v3 with action RESTORED
        RosterVersionResponse restored = versionService.restoreVersion(cycle.getId(), 1, "admin");
        assertNotNull(restored);
        assertEquals(3, restored.versionNumber());
        assertEquals("RESTORED", restored.action());
        assertTrue(restored.actionReason().contains("Restored snapshot from version v1"));

        // Historical snapshots v1 and v2 must remain intact
        List<RosterVersionResponse> allVersions = versionService.getCycleVersions(cycle.getId());
        assertEquals(3, allVersions.size());
    }

    @Test
    @DisplayName("Batch 17: Notification Center - Creation, Filtering, and Mark as Read")
    void testNotificationLifecycleAndFiltering() {
        authenticateAdmin();
        String testUser = "emp001";
        Employee emp = employeeRepository.findByUserUsername(testUser).orElseThrow();

        // Dispatch diverse notifications
        notificationService.createNotification(testUser, emp.getId(), "Roster Published", "New schedule available",
                NotificationType.ROSTER_PUBLISHED, "roster", 101L);
        notificationService.createNotification(testUser, emp.getId(), "Leave Approved", "Annual leave granted",
                NotificationType.LEAVE_DECISION, "leaves", 202L);
        notificationService.createNotification(testUser, emp.getId(), "System Maintenance", "Scheduled server update",
                NotificationType.SYSTEM_ANNOUNCEMENT, "system", 303L);

        authenticateEmployee(testUser);

        // 1. Unread count check
        long unread = notificationService.getUnreadCount(testUser);
        assertTrue(unread >= 3);

        // 2. Category filtering
        List<NotificationResponse> allNotifs = notificationService.getMyNotificationsFiltered(testUser, "ALL", 50);
        List<NotificationResponse> rosterNotifs = notificationService.getMyNotificationsFiltered(testUser, "ROSTER", 50);
        List<NotificationResponse> leaveNotifs = notificationService.getMyNotificationsFiltered(testUser, "LEAVE", 50);

        assertTrue(allNotifs.size() >= 3);
        assertTrue(rosterNotifs.stream().anyMatch(n -> n.type() == NotificationType.ROSTER_PUBLISHED));
        assertTrue(leaveNotifs.stream().anyMatch(n -> n.type() == NotificationType.LEAVE_DECISION));

        // 3. Mark Single as Read
        NotificationResponse first = allNotifs.get(0);
        NotificationResponse readResp = notificationService.markAsRead(first.id(), testUser);
        assertTrue(readResp.readStatus());

        // 4. Mark All as Read
        notificationService.markAllAsRead(testUser);
        assertEquals(0, notificationService.getUnreadCount(testUser));
    }

    @Test
    @DisplayName("Batch 17: Duplicate Notification Protection - Suppresses identical event duplicates")
    void testDuplicateNotificationSuppression() {
        String testUser = "emp002";
        Employee emp = employeeRepository.findByUserUsername(testUser).orElseThrow();

        // First notification should be created
        Notification n1 = notificationService.createNotification(testUser, emp.getId(),
                "Roster Published", "Your roster is live", NotificationType.ROSTER_PUBLISHED, "roster", 999L);
        assertNotNull(n1);
        assertNotNull(n1.getId());

        // Identical duplicate notification within 15 minutes should be suppressed
        Notification n2 = notificationService.createNotification(testUser, emp.getId(),
                "Roster Published", "Your roster is live", NotificationType.ROSTER_PUBLISHED, "roster", 999L);
        assertNull(n2, "Duplicate notification should be suppressed and return null");
    }

    @Test
    @DisplayName("Batch 17: Security - Employees cannot view other employees' notifications")
    void testNotificationSecurityIsolation() {
        authenticateEmployee("emp001");
        List<NotificationResponse> notifs1 = notificationService.getMyNotifications("emp001");
        for (NotificationResponse n : notifs1) {
            assertEquals("emp001", n.recipientUsername());
        }

        authenticateEmployee("emp002");
        List<NotificationResponse> notifs2 = notificationService.getMyNotifications("emp002");
        for (NotificationResponse n : notifs2) {
            assertEquals("emp002", n.recipientUsername());
        }
    }

    @Test
    @DisplayName("Batch 17 Critical Roster Safety: Automatic generation creates ONLY immediate upcoming week")
    void testCriticalRosterSafety_AutomaticGenerationScope() {
        authenticateAdmin();

        // Sunday 23-Aug-2026 -> Target Monday 24-Aug-2026
        LocalDate sunday23 = LocalDate.of(2026, 8, 23);
        LocalDate targetMonday = schedulerService.calculateTargetMonday(sunday23);
        assertEquals(LocalDate.of(2026, 8, 24), targetMonday, "Target Monday on Sunday 23-Aug must be 24-Aug-2026");

        // Centralized Guard Check: target Monday 24-Aug is allowed, next-next Monday 31-Aug is rejected
        assertTrue(schedulerService.isAutomaticGenerationAllowed(LocalDate.of(2026, 8, 24), sunday23));
        assertFalse(schedulerService.isAutomaticGenerationAllowed(LocalDate.of(2026, 8, 31), sunday23));

        // Execute automatic generation for immediate week
        RosterCycleResponse autoCycle = schedulerService.executeAutoGeneration(targetMonday);
        assertNotNull(autoCycle);
        assertEquals(LocalDate.of(2026, 8, 24), autoCycle.startDate());
        assertEquals(LocalDate.of(2026, 8, 30), autoCycle.endDate());

        // Attempting to auto-generate next-next week throws BusinessException
        assertThrows(BusinessException.class, () -> schedulerService.executeAutoGeneration(LocalDate.of(2026, 8, 31)));
    }
}