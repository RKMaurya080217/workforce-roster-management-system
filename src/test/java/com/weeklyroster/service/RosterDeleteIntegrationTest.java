package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;

import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class RosterDeleteIntegrationTest {

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private RosterOverrideRepository overrideRepository;

    @Autowired
    private EmailDeliveryLogRepository emailLogRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private RosterEmailService emailService;

    @Autowired
    private RosterVersionRepository versionRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private EmployeePreferenceRepository preferenceRepository;

    @BeforeEach
    void setUp() {
        overrideRepository.deleteAll();
        assignmentRepository.deleteAll();
        versionRepository.deleteAll();
        emailLogRepository.deleteAll();
        cycleRepository.deleteAll();
        leaveRequestRepository.deleteAll();
        preferenceRepository.deleteAll();
    }

    private void authenticateAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "N/A", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
    }

    private void authenticateEmployee() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("emp001", "N/A", List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
        );
    }

    @Test
    @DisplayName("Test reproducing and verifying deleteCycle on a cycle with assignments, overrides, and email logs")
    void testDeleteCycle_CompleteDependencyCleanup() {
        authenticateAdmin();

        LocalDate monday = LocalDate.of(2026, 9, 7);
        RosterCycleResponse gen = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL);
        Long cycleId = gen.id();
        assertNotNull(cycleId);

        RosterCycle cycle = cycleRepository.findById(cycleId).orElseThrow();

        // 1. Add an override
        List<RosterAssignment> assignments = assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(cycle);
        assertFalse(assignments.isEmpty());
        RosterAssignment firstAssn = assignments.get(0);
        RosterOverride override = new RosterOverride();
        override.setAssignment(firstAssn);
        override.setPreviousShiftType(firstAssn.getShift().getShiftType());
        override.setNewShiftType(ShiftType.GENERAL);
        override.setWeeklyOff(false);
        override.setReason("Testing delete cleanup");
        override.setCreatedAt(java.time.LocalDateTime.now());
        overrideRepository.save(override);

        // 2. Add email logs
        emailService.distributeRosterEmails(cycle, gen, GenerationMode.MANUAL);

        List<EmailDeliveryLog> logs = emailLogRepository.findByCycleOrderBySentAtDesc(cycle);
        assertFalse(logs.isEmpty(), "Email logs should exist for this cycle");

        long totalEmployeesBefore = employeeRepository.count();
        long totalShiftsBefore = shiftRepository.count();

        // 3. Attempt Delete
        System.out.println("Deleting cycle " + cycleId + "...");
        rosterService.deleteCycle(cycleId);

        // 4. Verify post-delete assertions
        assertTrue(cycleRepository.findById(cycleId).isEmpty(), "RosterCycle must be deleted");
        assertTrue(assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(cycle).isEmpty(), "Assignments must be deleted");
        assertEquals(0, emailLogRepository.findByCycleOrderBySentAtDesc(cycle).size(), "Email logs for this cycle must be deleted");

        // 5. Unrelated data must remain completely intact
        assertEquals(totalEmployeesBefore, employeeRepository.count(), "Employees must NOT be deleted");
        assertEquals(totalShiftsBefore, shiftRepository.count(), "Shifts must NOT be deleted");
    }

    @Test
    @DisplayName("Non-admin user cannot delete roster cycle")
    void testDeleteCycle_Unauthorized() {
        authenticateEmployee();
        assertThrows(AccessDeniedException.class, () -> rosterService.deleteCycle(999L));
    }

    @Test
    @DisplayName("Deleting non-existent cycle throws ResourceNotFoundException")
    void testDeleteCycle_NotFound() {
        authenticateAdmin();
        assertThrows(ResourceNotFoundException.class, () -> rosterService.deleteCycle(999999L));
    }

    @Test
    @DisplayName("Test generating roster when an overlapping cycle already exists with email logs and overrides")
    void testGenerateWeeklyRoster_OverlappingCycleCleanup() {
        authenticateAdmin();

        LocalDate monday = LocalDate.of(2026, 8, 17);
        // First generation
        RosterCycleResponse firstGen = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL);
        RosterCycle firstCycle = cycleRepository.findById(firstGen.id()).orElseThrow();

        // Add email logs
        emailService.distributeRosterEmails(firstCycle, firstGen, GenerationMode.MANUAL);

        // Second generation for same week (must clean up first generation safely)
        RosterCycleResponse secondGen = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL);
        assertNotNull(secondGen);
        assertNotNull(secondGen.id());
    }
}
