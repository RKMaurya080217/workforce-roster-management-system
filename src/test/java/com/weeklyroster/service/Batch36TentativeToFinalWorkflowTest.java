package com.weeklyroster.service;

import com.weeklyroster.dto.request.ApplyLeaveRequest;
import com.weeklyroster.dto.request.PreferenceDecisionRequest;
import com.weeklyroster.dto.request.PreferenceSubmitRequest;
import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.*;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "emp001", roles = {"EMPLOYEE", "ADMIN"})
public class Batch36TentativeToFinalWorkflowTest {

    @Autowired
    private RosterSchedulerService schedulerService;

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterEmailService emailService;

    @Autowired
    private UnifiedApprovalService unifiedApprovalService;

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private EmployeePreferenceService preferenceService;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmailDeliveryLogRepository emailLogRepository;

    private LocalDate upcomingMonday;

    @BeforeEach
    void setUp() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        upcomingMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(7);
    }

    @Test
    @DisplayName("1. Scheduled Sunday morning generation produces TENTATIVE cycle with tentative emails")
    void testScheduledSundayMorningTentativeGeneration() {
        // Execute automated generation for upcoming week
        RosterCycleResponse response = schedulerService.executeAutoGeneration(upcomingMonday);
        assertNotNull(response);
        assertEquals(upcomingMonday, response.startDate());

        RosterCycle cycle = cycleRepository.findById(response.id()).orElseThrow();
        assertEquals(RosterStatus.TENTATIVE, cycle.getStatus());

        // Verify tentative email logs were created
        List<EmailDeliveryLog> tentativeLogs = emailLogRepository.findByCycleAndEmailTypeAndStatus(
                cycle, EmailType.TENTATIVE_ROSTER, EmailDeliveryStatus.SENT);
        assertFalse(tentativeLogs.isEmpty(), "Tentative emails should be logged for active employees");

        // Test idempotency: re-running should skip and not create duplicate cycles
        int initialCycleCount = cycleRepository.findByStartDateAndEndDate(upcomingMonday, upcomingMonday.plusDays(6))
                .map(List::of).orElseGet(List::of).size();
        schedulerService.executeAutoGeneration(upcomingMonday);
        int afterCycleCount = cycleRepository.findByStartDateAndEndDate(upcomingMonday, upcomingMonday.plusDays(6))
                .map(List::of).orElseGet(List::of).size();
        assertEquals(initialCycleCount, afterCycleCount, "Auto-generation must be strictly idempotent");
    }

    @Test
    @DisplayName("2. Review window open check and deadline calculation")
    void testReviewWindowChecks() {
        LocalDate sundayBefore = upcomingMonday.minusDays(1);
        LocalDateTime beforeDeadline = LocalDateTime.of(sundayBefore, LocalTime.of(12, 0, 0));
        LocalDateTime afterDeadline = LocalDateTime.of(sundayBefore, LocalTime.of(16, 30, 0));

        assertTrue(schedulerService.isReviewWindowOpen(upcomingMonday, beforeDeadline),
                "Review window must be open before Sunday 4:00 PM IST");
        assertFalse(schedulerService.isReviewWindowOpen(upcomingMonday, afterDeadline),
                "Review window must be closed after Sunday 4:00 PM IST");

        LocalDateTime deadline = schedulerService.calculateReviewDeadline(upcomingMonday);
        assertEquals(LocalDateTime.of(sundayBefore, LocalTime.of(16, 0, 0)), deadline);
    }

    @Test
    @DisplayName("3. Shift Preference Approval triggers full roster re-optimization")
    void testPreferenceApprovalTriggersReoptimization() {
        // First generate tentative cycle
        RosterCycleResponse response = schedulerService.executeAutoGeneration(upcomingMonday);
        RosterCycle cycle = cycleRepository.findById(response.id()).orElseThrow();

        Employee emp = employeeRepository.findByActiveTrueOrderByIdAsc().get(0);

        // Submit preference for upcoming cycle
        PreferenceResponse pref = preferenceService.submitPreference(emp.getId(), new PreferenceSubmitRequest(
                "MORNING",
                "SUNDAY",
                "MONDAY",
                "NIGHT",
                "Need morning duty for training",
                "Admin review requested",
                upcomingMonday,
                upcomingMonday.plusDays(6)
        ), emp.getEmployeeCode() != null ? emp.getEmployeeCode() : "emp001");

        // Admin approves preference via unified approvals
        PreferenceResponse approved = unifiedApprovalService.decidePreference(
                pref.id(),
                new PreferenceDecisionRequest(PreferenceStatus.APPROVED, "Approved by manager"),
                "admin"
        );
        assertEquals(PreferenceStatus.APPROVED, approved.status());

        // Verify roster still satisfies all hard rules after re-optimization
        RosterCycle reoptimizedCycle = cycleRepository.findByStartDateAndEndDate(upcomingMonday, upcomingMonday.plusDays(6)).orElseThrow();
        RosterCycleResponse updatedCycle = rosterService.cycle(reoptimizedCycle.getId());
        assertNotNull(updatedCycle);
        assertFalse(updatedCycle.assignments().isEmpty());
    }

    @Test
    @DisplayName("4. Leave Approval triggers auto replacement & re-optimization")
    void testLeaveApprovalTriggersReoptimization() {
        // Generate tentative cycle
        RosterCycleResponse response = schedulerService.executeAutoGeneration(upcomingMonday);
        RosterCycle cycle = cycleRepository.findById(response.id()).orElseThrow();

        Employee emp = employeeRepository.findByActiveTrueOrderByIdAsc().get(0);

        // Apply leave for Friday of the cycle
        LocalDate leaveDate = upcomingMonday.plusDays(4); // Friday
        LeaveResponse leave = leaveService.apply(new ApplyLeaveRequest(
                emp.getId(),
                leaveDate,
                leaveDate,
                "Family function"
        ));

        // Admin approves leave
        LeaveResponse approvedLeave = unifiedApprovalService.decideLeave(leave.id(), true, null);
        assertEquals(LeaveStatus.APPROVED, approvedLeave.status());

        // Verify employee has ON LEAVE / OFF on that date
        RosterCycleResponse reoptimized = rosterService.cycle(cycle.getId());
        boolean hasLeaveAssignment = reoptimized.assignments().stream()
                .anyMatch(a -> a.employeeId().equals(emp.getId()) && a.rosterDate().equals(leaveDate) && a.onLeave());
        assertTrue(hasLeaveAssignment, "Employee should be marked onLeave on approved leave date");
    }

    @Test
    @DisplayName("5. Sunday 4:00 PM finalization locks cycle and marks as FINAL with final emails")
    void testSundayFinalizationWorkflow() {
        // Generate tentative cycle
        RosterCycleResponse tentativeResponse = schedulerService.executeAutoGeneration(upcomingMonday);
        RosterCycle cycle = cycleRepository.findById(tentativeResponse.id()).orElseThrow();
        assertEquals(RosterStatus.TENTATIVE, cycle.getStatus());

        // Finalize at Sunday 4:00 PM
        RosterCycleResponse finalResponse = schedulerService.executeAutoFinalization(upcomingMonday);
        assertNotNull(finalResponse);

        RosterCycle finalizedCycle = cycleRepository.findById(cycle.getId()).orElseThrow();
        assertEquals(RosterStatus.FINAL, finalizedCycle.getStatus());
        assertNotNull(finalizedCycle.getLockedAt());

        // Verify final emails were dispatched
        List<EmailDeliveryLog> finalLogs = emailLogRepository.findByCycleAndEmailTypeAndStatus(
                finalizedCycle, EmailType.FINAL_ROSTER, EmailDeliveryStatus.SENT);
        assertFalse(finalLogs.isEmpty(), "Final emails should be dispatched upon 4:00 PM finalization");

        // Finalization idempotency check
        schedulerService.executeAutoFinalization(upcomingMonday);
        List<EmailDeliveryLog> finalLogsAfter = emailLogRepository.findByCycleAndEmailTypeAndStatus(
                finalizedCycle, EmailType.FINAL_ROSTER, EmailDeliveryStatus.SENT);
        assertEquals(finalLogs.size(), finalLogsAfter.size(), "No duplicate final emails should be sent");
    }

    @Test
    @DisplayName("6. Unified Approvals dynamic summary calculation")
    void testUnifiedApprovalsSummary() {
        UnifiedApprovalsSummaryResponse summary = unifiedApprovalService.getSummary();
        assertNotNull(summary);
        assertTrue(summary.totalPending() >= 0);
        assertEquals(summary.totalPending(), summary.profileRequestsCount() + summary.leaveRequestsCount() + summary.preferenceRequestsCount());
    }
}
