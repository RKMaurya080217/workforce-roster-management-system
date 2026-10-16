package com.weeklyroster.service;

import com.weeklyroster.dto.request.ShiftChangeRequest;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BATCH 91: Draft Roster Preview + Non-Blocking Validation + Manual Correction Flow
 * Integration Test Suite
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {"wrms.dev.credential-mirror.enabled=false"})
public class Batch91DraftRosterPreviewIntegrationTest {

    @Autowired
    private RosterService rosterService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    private LocalDate nextMonday;

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken("admin", "admin",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        nextMonday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    }

    @Test
    @DisplayName("BATCH 91 Test 1: Roster generation creates candidate draft and does not throw blocking validation exception")
    void testDraftGenerationDoesNotThrowBlockingException() {
        LocalDate startDate = nextMonday.plusWeeks(16);
        RosterCycleResponse response = rosterService.generateWeeklyRoster(startDate, GenerationMode.MANUAL, 6);

        assertNotNull(response, "Generated candidate response must not be null");
        assertNotNull(response.id(), "Candidate draft must have a generated cycle ID");
        assertNotNull(response.assignments(), "Candidate draft must have assignments");
        assertFalse(response.assignments().isEmpty(), "Candidate assignments must not be empty");

        Optional<RosterCycle> cycleOpt = cycleRepository.findById(response.id());
        assertTrue(cycleOpt.isPresent(), "Cycle must be persisted in database");
        RosterCycle cycle = cycleOpt.get();

        // If there are critical conflicts, status must be DRAFT; if 0, GENERATED or DRAFT
        if (response.criticalConflicts() > 0) {
            assertEquals(RosterStatus.DRAFT, cycle.getStatus(), "Cycles with validation issues must be saved as DRAFT");
            assertNotNull(response.conflicts(), "Conflicts list must be returned with details");
            assertFalse(response.conflicts().isEmpty(), "Conflicts list must contain violation items");
        } else {
            assertTrue(cycle.getStatus() == RosterStatus.DRAFT || cycle.getStatus() == RosterStatus.GENERATED,
                    "Generated cycle status should be DRAFT or GENERATED");
        }
    }

    @Test
    @DisplayName("BATCH 91 Test 2: Validation endpoint returns complete issue breakdown and canFinalize flag")
    void testValidateEndpointBreakdown() {
        LocalDate startDate = nextMonday.plusWeeks(17);
        RosterCycleResponse response = rosterService.generateWeeklyRoster(startDate, GenerationMode.MANUAL, 6);

        RosterService.FinalValidationResult val = rosterService.validateCycle(response.id());
        assertNotNull(val, "Validation result must not be null");
        assertEquals(response.criticalConflicts(), val.criticalConflicts(), "Validation critical conflicts must match cycle response");
        assertEquals(val.criticalConflicts() == 0, val.canFinalize(), "canFinalize must be true if and only if critical conflicts == 0");
    }

    @Test
    @DisplayName("BATCH 91 Test 3: Manual override on draft allows shift rebalancing and updates live validation")
    void testManualShiftCorrectionInDraft() {
        LocalDate startDate = nextMonday.plusWeeks(18);
        RosterCycleResponse response = rosterService.generateWeeklyRoster(startDate, GenerationMode.MANUAL, 6);

        List<RosterAssignment> assignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(response.id());
        assertFalse(assignments.isEmpty());

        // Find a male employee assignment that is not off or leave
        RosterAssignment target = assignments.stream()
                .filter(a -> a.getEmployee().getGender() == Gender.MALE && !a.isWeeklyOff() && !a.isOnLeave())
                .findFirst()
                .orElse(assignments.get(0));

        // Change shift
        ShiftType newShift = target.getShift().getShiftType() == ShiftType.NIGHT ? ShiftType.GENERAL : ShiftType.GENERAL;
        ShiftChangeRequest req = new ShiftChangeRequest(newShift, "Admin manual draft adjustment");
        var updated = rosterService.changeShift(target.getId(), req);

        assertNotNull(updated);
        assertEquals(newShift, updated.shiftType(), "Updated shift type must match requested shift");

        // Validate cycle again to verify revalidation runs
        RosterService.FinalValidationResult valAfter = rosterService.validateCycle(response.id());
        assertNotNull(valAfter);
    }

    @Test
    @DisplayName("BATCH 91 Test 4: 12-hour rest interval violation is blocked on manual edit with clear reason")
    void test12HourRestRuleViolationBlockedOnManualEdit() {
        LocalDate startDate = nextMonday.plusWeeks(19);
        RosterCycleResponse response = rosterService.generateWeeklyRoster(startDate, GenerationMode.MANUAL, 6);

        List<RosterAssignment> assignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(response.id());
        // Find a male employee with assignments on consecutive days
        Long empId = assignments.stream()
                .filter(a -> a.getEmployee().getGender() == Gender.MALE)
                .map(a -> a.getEmployee().getId())
                .findFirst().orElseThrow();

        List<RosterAssignment> empAssignments = assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(empId))
                .sorted(Comparator.comparing(RosterAssignment::getRosterDate))
                .toList();

        assertTrue(empAssignments.size() >= 2);
        RosterAssignment day1 = empAssignments.get(0);
        RosterAssignment day2 = empAssignments.get(1);

        // First set day2 to OFF so setting day1 to NIGHT does not conflict with day2's existing daytime shift
        rosterService.changeShift(day2.getId(), new ShiftChangeRequest(ShiftType.OFF, "Set off"));
        rosterService.changeShift(day1.getId(), new ShiftChangeRequest(ShiftType.NIGHT, "Set night"));

        // Attempt to set day2 to MORNING (Night ends 07:00, Morning starts 07:00 -> 0h rest, violates 12h rule)
        BusinessException ex = assertThrows(BusinessException.class, () -> {
            rosterService.changeShift(day2.getId(), new ShiftChangeRequest(ShiftType.MORNING, "Violate rest rule"));
        });

        assertTrue(ex.getMessage().contains("12-hour rest conflict") || ex.getMessage().contains("rest"),
                "Exception message must mention 12-hour rest rule: " + ex.getMessage());
    }

    @Test
    @DisplayName("BATCH 91 Test 5: Assigning working shift on approved leave is blocked with clear reason")
    void testWorkingShiftOnLeaveBlockedOnManualEdit() {
        LocalDate startDate = nextMonday.plusWeeks(20);
        RosterCycleResponse response = rosterService.generateWeeklyRoster(startDate, GenerationMode.MANUAL, 6);

        List<RosterAssignment> assignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(response.id());
        RosterAssignment target = assignments.get(0);

        // Simulate leave
        target.setOnLeave(true);
        assignmentRepository.save(target);

        BusinessException ex = assertThrows(BusinessException.class, () -> {
            rosterService.changeShift(target.getId(), new ShiftChangeRequest(ShiftType.MORNING, "Try working on leave"));
        });

        assertTrue(ex.getMessage().contains("approved Leave"), "Exception message must mention approved leave: " + ex.getMessage());
    }

    @Test
    @DisplayName("BATCH 91 Test 6: Finalize is blocked when critical conflicts exist, succeeds when 0 conflicts")
    void testFinalizeBlocksOnErrorsAndSucceedsOnValid() {
        LocalDate startDate = nextMonday.plusWeeks(21);
        RosterCycleResponse response = rosterService.generateWeeklyRoster(startDate, GenerationMode.MANUAL, 6);

        RosterService.FinalValidationResult val = rosterService.validateCycle(response.id());

        if (val.criticalConflicts() > 0) {
            // Must block finalize
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                rosterService.finalizeRoster(response.id());
            });
            assertTrue(ex.getMessage().contains("Cannot finalize roster"), "Error message must state cannot finalize roster: " + ex.getMessage());
        } else {
            // Must succeed and publish
            RosterCycleResponse finalized = rosterService.finalizeRoster(response.id());
            assertNotNull(finalized);
            assertEquals(RosterStatus.PUBLISHED, finalized.status(), "Finalized roster status must be PUBLISHED");
        }
    }

    @Test
    @DisplayName("BATCH 91 Test 7: Discard draft removes the cycle safely without deleting employees or settings")
    void testDiscardDraftPreservesUnderlyingData() {
        long initialEmployeeCount = employeeRepository.count();

        LocalDate startDate = nextMonday.plusWeeks(22);
        RosterCycleResponse response = rosterService.generateWeeklyRoster(startDate, GenerationMode.MANUAL, 6);

        Long cycleId = response.id();
        assertTrue(cycleRepository.existsById(cycleId));

        // Discard/delete the draft
        rosterService.deleteCycle(cycleId);

        assertFalse(cycleRepository.existsById(cycleId), "Draft cycle must be deleted");
        assertTrue(assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycleId).isEmpty(), "Draft assignments must be deleted");

        assertEquals(initialEmployeeCount, employeeRepository.count(), "Employee records must remain completely intact");
    }
}
