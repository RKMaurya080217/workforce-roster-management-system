package com.weeklyroster.service;

import com.weeklyroster.dto.request.ExportReportRequest;
import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class Batch43FullStabilizationAndIntegrityTest {

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterSchedulerService schedulerService;

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
    private ShiftRepository shiftRepository;

    @Autowired
    private ExportCenterService exportCenterService;

    @Autowired
    private RosterHealthService healthService;

    @Autowired
    private RosterVersionService versionService;

    private LocalDate systemUpcomingMonday;

    @BeforeEach
    void setUp() {
        overrideRepository.deleteAll();
        assignmentRepository.deleteAll();
        versionRepository.deleteAll();
        emailDeliveryLogRepository.deleteAll();
        cycleRepository.deleteAll();
        systemUpcomingMonday = schedulerService.calculateUpcomingWeekStart(LocalDate.now());
    }

    @Test
    @DisplayName("Batch 43 [1]: Upcoming-Week-Only Automatic Generation Rule")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test1_UpcomingWeekOnlyAutomaticGeneration() {
        LocalDate calculatedUpcomingMonday = schedulerService.calculateUpcomingWeekStart(LocalDate.now());
        assertNotNull(calculatedUpcomingMonday);

        RosterCycleResponse resp = schedulerService.executeAutoGeneration(calculatedUpcomingMonday);
        assertNotNull(resp);
        assertEquals(calculatedUpcomingMonday, resp.startDate());
        assertEquals(calculatedUpcomingMonday.plusDays(6), resp.endDate());
        assertEquals(RosterStatus.TENTATIVE, resp.status());
    }

    @Test
    @DisplayName("Batch 43 [2]: Sunday 4:00 PM Automatic Finalization Workflow")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test2_SundayAutoFinalizationWorkflow() {
        // Step 1: Execute auto generation -> creates TENTATIVE roster
        RosterCycleResponse gen = schedulerService.executeAutoGeneration(systemUpcomingMonday);
        assertNotNull(gen);
        assertEquals(RosterStatus.TENTATIVE, gen.status());

        // Step 2: Run 4:00 PM finalization
        RosterCycleResponse finalized = schedulerService.executeAutoFinalization(systemUpcomingMonday);
        assertNotNull(finalized);
        assertEquals(RosterStatus.FINAL, finalized.status());

        // Step 3: Verify cycle is locked in database
        RosterCycle cycle = cycleRepository.findById(finalized.id()).orElseThrow();
        assertEquals(RosterStatus.FINAL, cycle.getStatus());
        assertNotNull(cycle.getLockedAt());

        // Step 4: Verify idempotency (subsequent run does nothing harmful)
        RosterCycleResponse secondRun = schedulerService.executeAutoFinalization(systemUpcomingMonday);
        assertNotNull(secondRun);
        assertEquals(RosterStatus.FINAL, secondRun.status());
    }

    @Test
    @DisplayName("Batch 43 [3]: Roster Core Safety & Constraint Integrity")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test3_RosterAllocationConstraintIntegrity() {
        LocalDate futureMonday = LocalDate.of(2027, 2, 1);
        RosterCycleResponse cycleResp = rosterService.generateWeeklyRoster(futureMonday, GenerationMode.MANUAL);
        List<RosterAssignment> assignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycleResp.id());

        assertFalse(assignments.isEmpty(), "Assignments should be generated");

        for (LocalDate d = futureMonday; !d.isAfter(futureMonday.plusDays(6)); d = d.plusDays(1)) {
            final LocalDate curDate = d;
            long morning = assignments.stream().filter(a -> a.getRosterDate().equals(curDate) && a.getShift() != null && a.getShift().getShiftType() == ShiftType.MORNING && !a.isWeeklyOff() && !a.isOnLeave()).count();
            long general = assignments.stream().filter(a -> a.getRosterDate().equals(curDate) && a.getShift() != null && a.getShift().getShiftType() == ShiftType.GENERAL && !a.isWeeklyOff() && !a.isOnLeave()).count();
            long evening = assignments.stream().filter(a -> a.getRosterDate().equals(curDate) && a.getShift() != null && a.getShift().getShiftType() == ShiftType.EVENING && !a.isWeeklyOff() && !a.isOnLeave()).count();
            long night = assignments.stream().filter(a -> a.getRosterDate().equals(curDate) && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT && !a.isWeeklyOff() && !a.isOnLeave()).count();

            assertTrue(morning >= 1, "Morning coverage >= 1 on " + curDate);
            assertTrue(general >= 1, "General coverage >= 1 on " + curDate);
            assertTrue(evening >= 1, "Evening coverage >= 1 on " + curDate);
            assertEquals(1, night, "Night coverage exactly 1 on " + curDate);
        }

        // Female safety check: Female employees must NEVER have Evening or Night shift
        for (RosterAssignment a : assignments) {
            if (a.getEmployee().getGender() == Gender.FEMALE) {
                if (a.getShift() != null && !a.isWeeklyOff() && !a.isOnLeave()) {
                    assertNotEquals(ShiftType.EVENING, a.getShift().getShiftType(), "Female staff cannot be assigned Evening shift");
                    assertNotEquals(ShiftType.NIGHT, a.getShift().getShiftType(), "Female staff cannot be assigned Night shift");
                }
            }
        }
    }

    @Test
    @DisplayName("Batch 43 [4]: Enterprise Export Center Formats (PDF, Excel, CSV, Image)")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test4_ExportCenterAllFormats() {
        LocalDate futureMonday = LocalDate.of(2027, 2, 1);
        RosterCycleResponse cycleResp = rosterService.generateWeeklyRoster(futureMonday, GenerationMode.MANUAL);

        // 1. PDF Export
        ExportReportRequest pdfReq = new ExportReportRequest("WEEKLY_ROSTER", "pdf", futureMonday, futureMonday.plusDays(6), cycleResp.id(), null, null);
        byte[] pdfBytes = exportCenterService.generateExport(pdfReq);
        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 100, "PDF export should not be 0 KB or empty");
        String pdfHeader = new String(pdfBytes, 0, Math.min(10, pdfBytes.length));
        assertTrue(pdfHeader.startsWith("%PDF-"), "Must have valid PDF header");

        // 2. Excel (.xlsx) Export
        ExportReportRequest xlsxReq = new ExportReportRequest("WEEKLY_ROSTER", "xlsx", futureMonday, futureMonday.plusDays(6), cycleResp.id(), null, null);
        byte[] xlsxBytes = exportCenterService.generateExport(xlsxReq);
        assertNotNull(xlsxBytes);
        assertTrue(xlsxBytes.length > 500, "Excel export should be valid zip package");

        // 3. CSV Export
        ExportReportRequest csvReq = new ExportReportRequest("WEEKLY_ROSTER", "csv", futureMonday, futureMonday.plusDays(6), cycleResp.id(), null, null);
        byte[] csvBytes = exportCenterService.generateExport(csvReq);
        assertNotNull(csvBytes);
        String csvContent = new String(csvBytes);
        assertTrue(csvContent.contains("WRMS Weekly Roster Export"), "CSV must have header");
        assertTrue(csvContent.contains("Employee Code"), "CSV must have column headers");

        // 4. PNG Image Export
        ExportReportRequest pngReq = new ExportReportRequest("WEEKLY_ROSTER", "png", futureMonday, futureMonday.plusDays(6), cycleResp.id(), null, null);
        byte[] pngBytes = exportCenterService.generateExport(pngReq);
        assertNotNull(pngBytes);
        assertTrue(pngBytes.length > 1000, "PNG image export should be non-empty image file");
    }

    @Test
    @DisplayName("Batch 43 [5]: Dynamic Today's Duty Resolution")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void test5_DynamicTodayDutyResolution() {
        Employee emp = employeeRepository.findByUserUsername("emp001").orElseThrow();
        LocalDate futureMonday = LocalDate.of(2027, 2, 1);

        // Generate roster covering futureMonday
        rosterService.generateWeeklyRoster(futureMonday, GenerationMode.MANUAL);

        // Query duty on futureMonday
        TodayDutyResponse duty = rosterService.getTodayEffectiveDuty(emp.getId(), futureMonday, LocalDateTime.of(2027, 2, 1, 10, 0));
        assertNotNull(duty);
        assertNotNull(duty.queryDate());
        assertNotNull(duty.status());
    }

    @Test
    @DisplayName("Batch 43 [6]: Roster Version Control Snapshot & Rollback Safety")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test6_VersionControlAndRollbackSafety() {
        LocalDate futureMonday = LocalDate.of(2027, 2, 1);
        RosterCycleResponse cycleResp = rosterService.generateWeeklyRoster(futureMonday, GenerationMode.MANUAL);
        Long cid = cycleResp.id();

        // 1. Initial snapshot V1
        List<RosterVersionResponse> versions = versionService.getCycleVersions(cid);
        assertFalse(versions.isEmpty());
        assertEquals(1, versions.get(0).versionNumber());

        // 2. Safe rollback preview
        RollbackPreviewResponse preview = versionService.previewRollback(cid, 1);
        assertNotNull(preview);
        assertTrue(preview.canRollback());

        // 3. Rollback creates new version V2 without deleting V1
        RosterVersionResponse rollbackResult = versionService.rollbackVersion(cid, 1, "Rollback to V1", "admin");
        assertNotNull(rollbackResult);
        assertEquals(2, rollbackResult.versionNumber());
        assertEquals("ROLLBACK", rollbackResult.action());

        List<RosterVersionResponse> finalVersions = versionService.getCycleVersions(cid);
        assertEquals(2, finalVersions.size());
    }
}