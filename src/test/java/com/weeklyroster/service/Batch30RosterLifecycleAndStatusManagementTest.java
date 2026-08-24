package com.weeklyroster.service;

import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.*;
import com.weeklyroster.util.RosterLifecycleUtil;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class Batch30RosterLifecycleAndStatusManagementTest {

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterSchedulerService schedulerService;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    private void authenticateAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "N/A", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
    }

    @BeforeEach
    void setUp() {
        authenticateAdmin();
    }

    @Test
    @DisplayName("Batch 30 Test 1: Cycle Classification Engine accurately categorizes CURRENT, UPCOMING, PAST, and FUTURE")
    void test1_ClassificationEngine() {
        LocalDate baseDate = LocalDate.of(2026, 8, 24); // Monday

        // Current week: 24 Aug -> 30 Aug 2026
        assertEquals("CURRENT", RosterLifecycleUtil.classifyCycle(LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30), baseDate));

        // Upcoming week: 31 Aug -> 06 Sep 2026
        assertEquals("UPCOMING", RosterLifecycleUtil.classifyCycle(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 6), baseDate));

        // Past week: 17 Aug -> 23 Aug 2026
        assertEquals("PAST", RosterLifecycleUtil.classifyCycle(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23), baseDate));

        // Future week: 07 Sep -> 13 Sep 2026
        assertEquals("FUTURE", RosterLifecycleUtil.classifyCycle(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 13), baseDate));

        // Far-future week: 14 Dec -> 20 Dec 2026
        assertEquals("FUTURE", RosterLifecycleUtil.classifyCycle(LocalDate.of(2026, 12, 14), LocalDate.of(2026, 12, 20), baseDate));
    }

    @Test
    @DisplayName("Batch 30 Test 2: Source Model clearly distinguishes AUTOMATIC vs MANUAL_ADMIN")
    void test2_SourceIdentification() {
        assertEquals("AUTOMATIC", RosterLifecycleUtil.resolveSource(GenerationMode.AUTOMATIC));
        assertEquals("MANUAL_ADMIN", RosterLifecycleUtil.resolveSource(GenerationMode.MANUAL));

        LocalDate monday = LocalDate.of(2026, 9, 14);
        RosterCycleResponse resp = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL);
        assertEquals("MANUAL_ADMIN", resp.source());
        assertEquals("FUTURE", resp.classification());
        assertEquals(RosterStatus.GENERATED, resp.status());
    }

    @Test
    @DisplayName("Batch 30 Test 3: Status Transitions validate valid workflows and reject illegal jumps")
    void test3_StatusTransitions() {
        assertTrue(RosterStatus.DRAFT.canTransitionTo(RosterStatus.GENERATED));
        assertTrue(RosterStatus.GENERATED.canTransitionTo(RosterStatus.PUBLISHED));
        assertTrue(RosterStatus.PUBLISHED.canTransitionTo(RosterStatus.ACTIVE));
        assertTrue(RosterStatus.PUBLISHED.canTransitionTo(RosterStatus.LOCKED));
        assertTrue(RosterStatus.LOCKED.canTransitionTo(RosterStatus.PUBLISHED));
        assertTrue(RosterStatus.ACTIVE.canTransitionTo(RosterStatus.COMPLETED));
        assertTrue(RosterStatus.COMPLETED.canTransitionTo(RosterStatus.ARCHIVED));

        // Reject invalid transition
        assertFalse(RosterStatus.COMPLETED.canTransitionTo(RosterStatus.DRAFT));
        assertFalse(RosterStatus.ARCHIVED.canTransitionTo(RosterStatus.PUBLISHED));
    }

    @Test
    @DisplayName("Batch 30 Test 4: Batch 29 Automation Guard strictly preserved - rejects non-upcoming weeks")
    void test4_Batch29UpcomingWeekSafetyPreserved() {
        LocalDate farFuture = LocalDate.of(2026, 12, 14);
        assertThrows(BusinessException.class, () -> schedulerService.executeAutoGeneration(farFuture));

        LocalDate nextNextWeek = LocalDate.now(ZoneId.of("Asia/Kolkata"))
                .plusWeeks(2)
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        assertThrows(BusinessException.class, () -> schedulerService.executeAutoGeneration(nextNextWeek));
    }

    @Test
    @DisplayName("Batch 30 Test 5: Admin manual generation permitted for any valid future week without automatic cascading")
    void test5_AdminManualGenerationAllowedForAnyWeek() {
        LocalDate customMonday = LocalDate.of(2026, 12, 14);
        RosterCycleResponse manualCycle = rosterService.generateWeeklyRoster(customMonday, GenerationMode.MANUAL);
        assertNotNull(manualCycle);
        assertEquals(customMonday, manualCycle.startDate());
        assertEquals(LocalDate.of(2026, 12, 20), manualCycle.endDate());
        assertEquals("MANUAL_ADMIN", manualCycle.source());
        assertEquals("FUTURE", manualCycle.classification());

        // Ensure automatic scheduler is not affected and still targets only the immediate upcoming week
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate upcomingMonday = schedulerService.calculateUpcomingWeekStart(today);
        assertTrue(schedulerService.isAutomaticGenerationAllowed(upcomingMonday, today));
        assertFalse(schedulerService.isAutomaticGenerationAllowed(customMonday, today));
    }

    @Test
    @DisplayName("Batch 30 Test 6: Safe Cycle Deletion - removes un-published cycle without foreign key constraints")
    void test6_SafeCycleDeletion() {
        LocalDate testMonday = LocalDate.of(2026, 10, 5);
        RosterCycleResponse created = rosterService.generateWeeklyRoster(testMonday, GenerationMode.MANUAL);
        Long id = created.id();

        assertTrue(RosterLifecycleUtil.isDeletable(cycleRepository.findById(id).orElseThrow()));

        // Delete cycle
        rosterService.deleteCycle(id);
        assertTrue(cycleRepository.findById(id).isEmpty());
    }

    @Test
    @DisplayName("Batch 30 Test 7: Duplicate Cycle Prevention - existing cycle replaced or reused without duplicating rows")
    void test7_DuplicateCyclePrevention() {
        LocalDate monday = LocalDate.of(2026, 11, 2);
        RosterCycleResponse first = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL);
        long countAfterFirst = cycleRepository.findAll().stream()
                .filter(c -> c.getStartDate().equals(monday))
                .count();
        assertEquals(1, countAfterFirst);

        // Second generation for same week
        RosterCycleResponse second = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL);
        long countAfterSecond = cycleRepository.findAll().stream()
                .filter(c -> c.getStartDate().equals(monday))
                .count();
        assertEquals(1, countAfterSecond, "Must maintain exactly 1 RosterCycle record for the same week");
    }
}
