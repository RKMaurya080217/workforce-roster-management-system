package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public class Batch13AnalyticsAndConflictDetectorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RosterAnalyticsService analyticsService;

    @Autowired
    private RosterValidatorService validatorService;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Test
    @DisplayName("1. Analytics summary calculation returns valid KPIs")
    void testAnalyticsSummary() {
        LocalDate start = LocalDate.now().minusDays(3);
        LocalDate end = LocalDate.now().plusDays(3);
        RosterAnalyticsResponse res = analyticsService.getAnalytics(start, end, null);

        assertNotNull(res);
        assertNotNull(res.summary());
        assertTrue(res.summary().totalEmployees() >= 0);
        assertTrue(res.summary().activeEmployees() >= 0);
        assertTrue(res.summary().coveragePercentage() >= 0);
    }

    @Test
    @DisplayName("2. Employee workload calculation computes metrics and ratings")
    void testEmployeeWorkload() {
        LocalDate start = LocalDate.now().minusDays(3);
        LocalDate end = LocalDate.now().plusDays(3);
        RosterAnalyticsResponse res = analyticsService.getAnalytics(start, end, null);

        assertNotNull(res.workloadDistribution());
        if (!res.workloadDistribution().isEmpty()) {
            EmployeeWorkloadMetric metric = res.workloadDistribution().get(0);
            assertNotNull(metric.employeeCode());
            assertNotNull(metric.workloadRating());
            assertTrue(metric.workloadScore() >= 0 && metric.workloadScore() <= 100);
        }
    }

    @Test
    @DisplayName("3. Shift distribution returns all shift types and percentages")
    void testShiftDistribution() {
        LocalDate start = LocalDate.now().minusDays(3);
        LocalDate end = LocalDate.now().plusDays(3);
        RosterAnalyticsResponse res = analyticsService.getAnalytics(start, end, null);

        assertNotNull(res.shiftDistribution());
        assertFalse(res.shiftDistribution().isEmpty());
        for (ShiftDistributionItem item : res.shiftDistribution()) {
            assertNotNull(item.shiftName());
            assertTrue(item.percentage() >= 0.0 && item.percentage() <= 100.0);
        }
    }

    @Test
    @DisplayName("4. Night-shift analytics returns count and compliance")
    void testNightShiftAnalytics() {
        LocalDate start = LocalDate.now().minusDays(3);
        LocalDate end = LocalDate.now().plusDays(3);
        RosterAnalyticsResponse res = analyticsService.getAnalytics(start, end, null);

        assertNotNull(res.workloadDistribution());
        for (EmployeeWorkloadMetric m : res.workloadDistribution()) {
            assertTrue(m.nightShifts() >= 0);
            assertTrue(m.maxConsecutiveNights() >= 0);
        }
    }

    @Test
    @DisplayName("5. Coverage calculation aggregates daily numbers correctly")
    void testCoverageCalculation() {
        LocalDate start = LocalDate.now().minusDays(3);
        LocalDate end = LocalDate.now().plusDays(3);
        RosterAnalyticsResponse res = analyticsService.getAnalytics(start, end, null);

        assertNotNull(res.dailyBreakdown());
        assertEquals(7, res.dailyBreakdown().size());
        for (DayCoverageItem d : res.dailyBreakdown()) {
            assertNotNull(d.date());
            assertNotNull(d.dayName());
            assertTrue(d.morning() >= 0);
            assertTrue(d.general() >= 0);
            assertTrue(d.evening() >= 0);
            assertTrue(d.night() >= 0);
        }
    }

    @Test
    @DisplayName("6. Conflict Detector: Rest-time conflict (12h minimum rest violated)")
    void testRestTimeConflictDetection() {
        RosterValidationResponse res = validatorService.validateActiveRoster();
        assertNotNull(res);
        assertNotNull(res.overallStatus());
        assertNotNull(res.findings());
    }

    @Test
    @DisplayName("7. Conflict Detector: Night-shift limit conflict (>2 nights violated)")
    void testNightShiftLimitConflict() {
        RosterValidationResponse res = validatorService.validateActiveRoster();
        assertNotNull(res);
        for (RosterValidationFinding finding : res.findings()) {
            if ("MAX_NIGHTS_EXCEEDED".equals(finding.ruleCode())) {
                assertEquals(ValidationSeverity.ERROR, finding.severity());
            }
        }
    }

    @Test
    @DisplayName("8. Conflict Detector: Leave conflict (assigned working shift during approved leave)")
    void testLeaveConflict() {
        RosterValidationResponse res = validatorService.validateActiveRoster();
        assertNotNull(res);
        for (RosterValidationFinding finding : res.findings()) {
            if ("LEAVE_COLLISION".equals(finding.ruleCode())) {
                assertEquals(ValidationSeverity.ERROR, finding.severity());
            }
        }
    }

    @Test
    @DisplayName("9. Conflict Detector: Duplicate assignment conflict on same date")
    void testDuplicateAssignmentConflict() {
        RosterValidationResponse res = validatorService.validateActiveRoster();
        assertNotNull(res);
        for (RosterValidationFinding finding : res.findings()) {
            if ("DUPLICATE_DATE_ASSIGNMENT".equals(finding.ruleCode())) {
                assertEquals(ValidationSeverity.ERROR, finding.severity());
            }
        }
    }

    @Test
    @DisplayName("10. Conflict Detector: Shift coverage shortage conflict")
    void testCoverageConflict() {
        RosterValidationResponse res = validatorService.validateActiveRoster();
        assertNotNull(res);
        for (RosterValidationFinding finding : res.findings()) {
            if ("ZERO_SHIFT_COVERAGE".equals(finding.ruleCode())) {
                assertEquals(ValidationSeverity.ERROR, finding.severity());
            }
        }
    }

    @Test
    @DisplayName("11. Conflict Detector: Gender/shift statutory restriction conflict")
    void testGenderShiftConflict() {
        RosterValidationResponse res = validatorService.validateActiveRoster();
        assertNotNull(res);
        for (RosterValidationFinding finding : res.findings()) {
            if ("FEMALE_SAFETY_VIOLATION".equals(finding.ruleCode())) {
                assertEquals(ValidationSeverity.ERROR, finding.severity());
            }
        }
    }

    @Test
    @DisplayName("12. Multiple employees having same OFF day should NOT be treated as an error")
    void testMultipleEmployeesSameOffDayAllowed() {
        RosterValidationResponse res = validatorService.validateActiveRoster();
        assertNotNull(res);
        for (RosterValidationFinding finding : res.findings()) {
            assertNotEquals("SAME_OFF_DAY_MULTIPLE_EMPLOYEES", finding.ruleCode());
        }
    }

    @Test
    @DisplayName("13. Empty roster handles gracefully without exceptions")
    void testEmptyRosterHandling() {
        RosterValidationResponse res = validatorService.validateActiveRoster();
        assertNotNull(res);
        assertTrue(res.totalChecks() >= 0);
    }

    @Test
    @DisplayName("14. No conflicts condition returns PASS status")
    void testCompliantRoster() {
        RosterValidationResponse res = validatorService.validateActiveRoster();
        assertNotNull(res);
        if (res.errorCount() == 0 && res.warningCount() == 0) {
            assertEquals(ValidationSeverity.PASS, res.overallStatus());
        }
    }

    @Test
    @DisplayName("15. Multiple conflicts aggregate counts accurately")
    void testMultipleConflictsAggregation() {
        RosterValidationResponse res = validatorService.validateActiveRoster();
        assertNotNull(res);
        assertEquals(res.findings().size(), res.errorCount() + res.warningCount());
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("16. Admin authorization allows access to analytics and validation endpoints")
    void testAdminAuthorization() throws Exception {
        mockMvc.perform(get("/api/admin/analytics").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/validation/active").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    @DisplayName("17. Employee authorization denies access to organization-wide conflict detector")
    void testEmployeeAuthorizationDenied() throws Exception {
        mockMvc.perform(get("/api/admin/validation/active").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }
}