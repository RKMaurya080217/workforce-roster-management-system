package com.weeklyroster.service;

import com.weeklyroster.dto.request.*;
import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class Batch44DeepFunctionalValidationTest {

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterSchedulerService schedulerService;

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private ProfileChangeRequestService profileChangeRequestService;

    @Autowired
    private ExportCenterService exportCenterService;

    @Autowired
    private RosterHealthService healthService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private RosterVersionRepository versionRepository;

    @Autowired
    private RosterOverrideRepository overrideRepository;

    @Autowired
    private EmailDeliveryLogRepository emailDeliveryLogRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private ProfileChangeRequestRepository profileChangeRequestRepository;

    @Autowired
    private EmployeePreferenceRepository preferenceRepository;

    private LocalDate systemUpcomingMonday;
    private LocalDate futureMonday;

    @BeforeEach
    void setUp() {
        overrideRepository.deleteAll();
        assignmentRepository.deleteAll();
        versionRepository.deleteAll();
        emailDeliveryLogRepository.deleteAll();
        cycleRepository.deleteAll();
        profileChangeRequestRepository.deleteAll();
        leaveRequestRepository.deleteAll();
        preferenceRepository.deleteAll();

        systemUpcomingMonday = schedulerService.calculateUpcomingWeekStart(LocalDate.now());
        futureMonday = LocalDate.of(2027, 3, 1); // Future Monday
    }

    @Test
    @DisplayName("Batch 44 [1]: End-to-End Role-Based Access Control (RBAC)")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void test01_EndToEndRoleBasedAccessControl() {
        // Employee attempting administrative actions should be blocked with AccessDeniedException
        assertThrows(AccessDeniedException.class, () -> {
            rosterService.publishRoster(999L);
        }, "Employee cannot publish roster cycles");

        assertThrows(AccessDeniedException.class, () -> {
            rosterService.lockRoster(999L);
        }, "Employee cannot lock roster cycles");

        assertThrows(AccessDeniedException.class, () -> {
            rosterService.unlockRoster(999L, new UnlockRosterRequest("Unauthorized unlock"));
        }, "Employee cannot unlock roster cycles");

        assertThrows(AccessDeniedException.class, () -> {
            rosterService.deleteCycle(999L);
        }, "Employee cannot delete roster cycles");

        assertThrows(AccessDeniedException.class, () -> {
            leaveService.approve(999L, new LeaveDecisionRequest("Approved"));
        }, "Employee cannot approve leave requests");
    }

    @Test
    @DisplayName("Batch 44 [2]: Employee Profile Change Workflow Lifecycle")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void test02_ProfileChangeRequestCompleteLifecycle() {
        Employee emp = employeeRepository.findByUserUsername("emp001").orElseThrow();

        String newPhone = "9876543210".equals(emp.getContactNumber()) ? "9123456780" : "9876543210";

        // 1. Employee submits profile change request
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", newPhone);
        ProfileChangeRequestResponse submitted = profileChangeRequestService.submitRequest(req);
        assertNotNull(submitted);
        assertEquals(ProfileChangeStatus.PENDING, submitted.status());

        // Verify request in DB
        List<ProfileChangeRequest> pending = profileChangeRequestRepository.findByEmployeeIdOrderByRequestedAtDesc(emp.getId());
        assertFalse(pending.isEmpty());
        ProfileChangeRequest changeReq = pending.get(0);
        assertEquals(newPhone, changeReq.getRequestedValue());

        // 2. Admin approves request
        approveProfileAsAdmin(changeReq.getId());

        // 3. Verify Employee DB updated
        Employee updated = employeeRepository.findById(emp.getId()).orElseThrow();
        assertEquals(newPhone, updated.getContactNumber());

        // 4. Verify request status is APPROVED
        ProfileChangeRequest approvedReq = profileChangeRequestRepository.findById(changeReq.getId()).orElseThrow();
        assertEquals(ProfileChangeStatus.APPROVED, approvedReq.getStatus());
    }

    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    private void approveProfileAsAdmin(Long reqId) {
        ProfileChangeRequestResponse response = profileChangeRequestService.approve(reqId, new ProfileChangeDecisionRequest("Admin approved update"));
        assertNotNull(response);
        assertEquals(ProfileChangeStatus.APPROVED, response.status());
    }

    @Test
    @DisplayName("Batch 44 [3]: Leave Workflow & Roster Collision Prevention")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test03_LeaveWorkflowAndRosterCollision() {
        Employee emp = employeeRepository.findByUserUsername("emp001").orElseThrow();
        LocalDate wednesday = futureMonday.plusDays(2); // Wednesday

        // 1. Submit & Approve Leave for Wednesday
        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(emp);
        leave.setStartDate(wednesday);
        leave.setEndDate(wednesday);
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setReason("Doctor visit");
        leave.setRequestedAt(LocalDateTime.now());
        leaveRequestRepository.save(leave);

        // 2. Generate Roster for the week
        RosterCycleResponse cycleResp = rosterService.generateWeeklyRoster(futureMonday, GenerationMode.MANUAL);
        assertNotNull(cycleResp);

        List<RosterAssignment> empAssignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycleResp.id()).stream()
                .filter(a -> a.getEmployee().getId().equals(emp.getId()))
                .toList();

        assertEquals(7, empAssignments.size());

        // 3. Verify Wednesday assignment is marked onLeave = true, weeklyOff = false
        RosterAssignment wednesdayAssign = empAssignments.stream().filter(a -> a.getRosterDate().equals(wednesday)).findFirst().orElseThrow();
        assertTrue(wednesdayAssign.isOnLeave(), "Wednesday assignment must be marked onLeave");
        assertFalse(wednesdayAssign.isWeeklyOff(), "Leave day should not be marked weeklyOff");
        assertEquals(ShiftType.OFF, wednesdayAssign.getShift().getShiftType());

        // 4. Verify employee has exactly 1 weekly off and 1 leave
        long leaveDays = empAssignments.stream().filter(RosterAssignment::isOnLeave).count();
        long offDays = empAssignments.stream().filter(RosterAssignment::isWeeklyOff).count();
        long workingDays = empAssignments.stream().filter(a -> !a.isOnLeave() && !a.isWeeklyOff()).count();

        assertEquals(1, leaveDays, "Must have 1 leave day");
        assertEquals(1, offDays, "Must receive exactly 1 separate weekly OFF");
        assertEquals(5, workingDays, "Must work remaining 5 days");
    }

    @Test
    @DisplayName("Batch 44 [4]: Shift Preferences & Avoid Rules Respected")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test04_ShiftPreferencesAndAvoidRulesEnforced() {
        Employee emp = employeeRepository.findByUserUsername("emp001").orElseThrow();

        // 1. Set Avoid Evening preference for EMP001
        EmployeePreference pref = new EmployeePreference();
        pref.setEmployee(emp);
        pref.setAvoidShiftTypes("EVENING");
        pref.setPreferredOffDays("SUNDAY");
        pref.setStatus(PreferenceStatus.APPROVED);
        preferenceRepository.save(pref);

        // 2. Generate roster
        RosterCycleResponse cycleResp = rosterService.generateWeeklyRoster(futureMonday, GenerationMode.MANUAL);

        List<RosterAssignment> assignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycleResp.id()).stream()
                .filter(a -> a.getEmployee().getId().equals(emp.getId()))
                .toList();

        // 3. Verify EMP001 is NEVER assigned Evening
        for (RosterAssignment a : assignments) {
            if (a.getShift() != null && !a.isWeeklyOff() && !a.isOnLeave()) {
                assertNotEquals(ShiftType.EVENING, a.getShift().getShiftType(), "EMP001 must not receive avoided Evening shift");
            }
        }
    }

    @Test
    @DisplayName("Batch 44 [5]: Comprehensive Multi-Scenario Roster Safety & Coverage Rules")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test05_MultiScenarioRosterSafetyAndCoverageRules() {
        RosterCycleResponse cycleResp = rosterService.generateWeeklyRoster(futureMonday, GenerationMode.MANUAL);
        List<RosterAssignment> assignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycleResp.id());

        assertFalse(assignments.isEmpty());

        for (LocalDate d = futureMonday; !d.isAfter(futureMonday.plusDays(6)); d = d.plusDays(1)) {
            final LocalDate curDate = d;
            long morning = assignments.stream().filter(a -> a.getRosterDate().equals(curDate) && a.getShift() != null && a.getShift().getShiftType() == ShiftType.MORNING && !a.isWeeklyOff() && !a.isOnLeave()).count();
            long general = assignments.stream().filter(a -> a.getRosterDate().equals(curDate) && a.getShift() != null && a.getShift().getShiftType() == ShiftType.GENERAL && !a.isWeeklyOff() && !a.isOnLeave()).count();
            long evening = assignments.stream().filter(a -> a.getRosterDate().equals(curDate) && a.getShift() != null && a.getShift().getShiftType() == ShiftType.EVENING && !a.isWeeklyOff() && !a.isOnLeave()).count();
            long night = assignments.stream().filter(a -> a.getRosterDate().equals(curDate) && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT && !a.isWeeklyOff() && !a.isOnLeave()).count();

            assertTrue(morning >= 1, "Morning coverage >= 1 on " + curDate);
            assertTrue(general >= 1, "General coverage >= 1 on " + curDate);
            assertTrue(evening >= 1, "Evening coverage >= 1 on " + curDate);
            assertEquals(1, night, "Night coverage == 1 on " + curDate);
        }

        // Female Staff Safety Invariant: 0 Evening, 0 Night across all 7 days
        for (RosterAssignment a : assignments) {
            if (a.getEmployee().getGender() == Gender.FEMALE) {
                if (a.getShift() != null && !a.isWeeklyOff() && !a.isOnLeave()) {
                    assertNotEquals(ShiftType.EVENING, a.getShift().getShiftType(), "Female staff cannot be assigned Evening shift");
                    assertNotEquals(ShiftType.NIGHT, a.getShift().getShiftType(), "Female staff cannot be assigned Night shift");
                }
            }
        }

        // Max 2 Nights Per Cycle for any employee
        List<Employee> allEmployees = employeeRepository.findByActiveTrueOrderByIdAsc();
        for (Employee e : allEmployees) {
            long nights = assignments.stream()
                    .filter(a -> a.getEmployee().getId().equals(e.getId()) && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT && !a.isWeeklyOff() && !a.isOnLeave())
                    .count();
            assertTrue(nights <= 2, "Employee " + e.getEmployeeCode() + " exceeded max 2 nights: " + nights);
        }
    }

    @Test
    @DisplayName("Batch 44 [6]: Upcoming-Week-Only Automatic Generation Rule Strict Enforcement")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test06_UpcomingWeekOnlyRuleStrictEnforcement() {
        // Automatic generation for past dates or far future dates must be strictly rejected
        LocalDate pastDate = LocalDate.of(2025, 1, 6);
        assertThrows(BusinessException.class, () -> {
            schedulerService.executeAutoGeneration(pastDate);
        }, "Auto generation must reject past dates");

        LocalDate farFuture = LocalDate.of(2030, 1, 7);
        assertThrows(BusinessException.class, () -> {
            schedulerService.executeAutoGeneration(farFuture);
        }, "Auto generation must reject far future dates");

        // Auto generation on valid system upcoming Monday succeeds
        RosterCycleResponse resp = schedulerService.executeAutoGeneration(systemUpcomingMonday);
        assertNotNull(resp);
        assertEquals(systemUpcomingMonday, resp.startDate());
        assertEquals(RosterStatus.TENTATIVE, resp.status());
    }

    @Test
    @DisplayName("Batch 44 [7]: Idempotent Regeneration & Duplicate Prevention")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test07_IdempotentRegenerationAndDuplicatePrevention() {
        // 1. Generate roster first time
        RosterCycleResponse cycle1 = rosterService.generateWeeklyRoster(futureMonday, GenerationMode.MANUAL);
        assertNotNull(cycle1);
        int count1 = assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle1.id()).size();
        assertEquals(49, count1);

        // 2. Re-generate same week
        RosterCycleResponse cycle2 = rosterService.generateWeeklyRoster(futureMonday, GenerationMode.MANUAL);
        assertNotNull(cycle2);
        int count2 = assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle2.id()).size();
        assertEquals(49, count2, "Assignments should be updated without duplicating records");

        // Cycles count should not duplicate
        List<RosterCycle> cycles = cycleRepository.findOverlappingCycles(futureMonday, futureMonday.plusDays(6));
        assertEquals(1, cycles.size(), "Only 1 cycle should exist for the date range");
    }

    @Test
    @DisplayName("Batch 44 [8]: Roster Health Evaluation Precision on Valid Roster")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test08_RosterHealthEvaluationPrecision() {
        RosterCycleResponse cycleResp = rosterService.generateWeeklyRoster(futureMonday, GenerationMode.MANUAL);

        RosterHealthReport health = healthService.getCycleHealth(cycleResp.id());
        assertNotNull(health);
        assertEquals(0, health.criticalConflictsCount(), "Valid generated roster must have 0 critical conflicts");
        assertTrue(health.readyToPublish(), "Valid generated roster must be ready to publish");
    }

    @Test
    @DisplayName("Batch 44 [9]: Dynamic Today's Duty Resolution across Shifts")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void test09_DynamicTodayDutyResolutionAcrossShifts() {
        Employee emp = employeeRepository.findByUserUsername("emp001").orElseThrow();
        rosterService.generateWeeklyRoster(futureMonday, GenerationMode.MANUAL);

        // Test Morning Duty query
        TodayDutyResponse dutyMorning = rosterService.getTodayEffectiveDuty(emp.getId(), futureMonday, LocalDateTime.of(2027, 3, 1, 8, 0));
        assertNotNull(dutyMorning);
        assertNotNull(dutyMorning.queryDate());
        assertNotNull(dutyMorning.status());

        // Test Evening Duty query
        TodayDutyResponse dutyEvening = rosterService.getTodayEffectiveDuty(emp.getId(), futureMonday, LocalDateTime.of(2027, 3, 1, 16, 0));
        assertNotNull(dutyEvening);
        assertNotNull(dutyEvening.status());
    }

    @Test
    @DisplayName("Batch 44 [10]: Export Center Format Integrity (PDF, XLSX, CSV, PNG)")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test10_ExportCenterIntegrityAllFormats() {
        RosterCycleResponse cycleResp = rosterService.generateWeeklyRoster(futureMonday, GenerationMode.MANUAL);

        // 1. PDF
        ExportReportRequest pdfReq = new ExportReportRequest("WEEKLY_ROSTER", "pdf", futureMonday, futureMonday.plusDays(6), cycleResp.id(), null, null);
        byte[] pdf = exportCenterService.generateExport(pdfReq);
        assertNotNull(pdf);
        assertTrue(pdf.length > 500);

        // 2. Excel
        ExportReportRequest xlsxReq = new ExportReportRequest("WEEKLY_ROSTER", "xlsx", futureMonday, futureMonday.plusDays(6), cycleResp.id(), null, null);
        byte[] xlsx = exportCenterService.generateExport(xlsxReq);
        assertNotNull(xlsx);
        assertTrue(xlsx.length > 1000);

        // 3. CSV
        ExportReportRequest csvReq = new ExportReportRequest("WEEKLY_ROSTER", "csv", futureMonday, futureMonday.plusDays(6), cycleResp.id(), null, null);
        byte[] csv = exportCenterService.generateExport(csvReq);
        assertNotNull(csv);
        assertTrue(csv.length > 100);

        // 4. PNG
        ExportReportRequest pngReq = new ExportReportRequest("WEEKLY_ROSTER", "png", futureMonday, futureMonday.plusDays(6), cycleResp.id(), null, null);
        byte[] png = exportCenterService.generateExport(pngReq);
        assertNotNull(png);
        assertTrue(png.length > 1000);
    }
}