package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.weeklyroster.dto.request.*;
import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
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
public class Batch15CombinedFeaturesTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShiftHandoverService handoverService;

    @Autowired
    private WorkloadAnalyticsService workloadService;

    @Autowired
    private ExportCenterService exportCenterService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @org.junit.jupiter.api.BeforeEach
    void setUpTestData() {
        if (cycleRepository.count() == 0) {
            LocalDate monday = LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
            RosterCycle cycle = new RosterCycle();
            cycle.setStartDate(monday);
            cycle.setEndDate(monday.plusDays(6));
            cycle.setStatus(RosterStatus.PUBLISHED);
            cycle.setGenerationMode(GenerationMode.AUTOMATIC);
            cycle.setGeneratedAt(java.time.LocalDateTime.now());
            cycle = cycleRepository.save(cycle);

            List<Employee> emps = employeeRepository.findAll();
            Shift morning = shiftRepository.findByShiftType(ShiftType.MORNING).orElse(null);
            if (!emps.isEmpty() && morning != null) {
                RosterAssignment a = new RosterAssignment();
                a.setCycle(cycle);
                a.setEmployee(emps.get(0));
                a.setShift(morning);
                a.setRosterDate(monday);
                assignmentRepository.save(a);
            }
        }

        if (leaveRequestRepository.count() == 0) {
            List<Employee> emps = employeeRepository.findAll();
            if (!emps.isEmpty()) {
                LeaveRequest lr = new LeaveRequest();
                lr.setEmployee(emps.get(0));
                lr.setStartDate(LocalDate.now());
                lr.setEndDate(LocalDate.now().plusDays(1));
                lr.setReason("Annual Leave");
                lr.setStatus(LeaveStatus.APPROVED);
                lr.setRequestedAt(java.time.LocalDateTime.now());
                leaveRequestRepository.save(lr);
            }
        }
    }

    @Test
    @DisplayName("1. Shift Handover: Create and list handover note")
    void testCreateHandover() {
        List<Employee> emps = employeeRepository.findAll();
        List<Shift> shifts = shiftRepository.findAll();
        assertFalse(emps.isEmpty());
        assertFalse(shifts.isEmpty());

        Employee fromEmp = emps.get(0);
        Employee toEmp = emps.size() > 1 ? emps.get(1) : null;
        Shift shift = shifts.get(0);

        CreateHandoverRequest req = new CreateHandoverRequest(
                LocalDate.now(),
                shift.getId(),
                toEmp != null ? toEmp.getId() : null,
                "Operations running normally. Router 4 rebooted.",
                "Verify database disk space at 18:00",
                "Log rotation completed",
                "Keep temperature monitor checked",
                HandoverPriority.HIGH
        );

        HandoverResponse res = handoverService.createHandover(fromEmp.getId(), req, fromEmp.getUser().getUsername());
        assertNotNull(res);
        assertNotNull(res.id());
        assertEquals(HandoverStatus.OPEN, res.status());
        assertEquals(HandoverPriority.HIGH, res.priority());

        List<HandoverResponse> myHandovers = handoverService.getMyHandovers(fromEmp.getId());
        assertTrue(myHandovers.stream().anyMatch(h -> h.id().equals(res.id())));
    }

    @Test
    @DisplayName("2. Shift Handover: Prevent self-handover")
    void testPreventSelfHandover() {
        Employee emp = employeeRepository.findAll().get(0);
        Shift shift = shiftRepository.findAll().get(0);

        CreateHandoverRequest req = new CreateHandoverRequest(
                LocalDate.now(),
                shift.getId(),
                emp.getId(),
                "Self handover attempt",
                null, null, null, HandoverPriority.MEDIUM
        );

        assertThrows(BusinessException.class, () -> {
            handoverService.createHandover(emp.getId(), req, emp.getUser().getUsername());
        });
    }

    @Test
    @DisplayName("3. Shift Handover: Incoming employee acknowledge handover")
    void testAcknowledgeHandover() {
        List<Employee> emps = employeeRepository.findAll();
        if (emps.size() >= 2) {
            Employee fromEmp = emps.get(0);
            Employee toEmp = emps.get(1);
            Shift shift = shiftRepository.findAll().get(0);

            CreateHandoverRequest req = new CreateHandoverRequest(
                    LocalDate.now(),
                    shift.getId(),
                    toEmp.getId(),
                    "Handover to ack",
                    "Task 1", null, null, HandoverPriority.MEDIUM
            );
            HandoverResponse created = handoverService.createHandover(fromEmp.getId(), req, fromEmp.getUser().getUsername());

            HandoverResponse ack = handoverService.acknowledgeHandover(created.id(), toEmp.getId(), "Received and acknowledged.", toEmp.getUser().getUsername());
            assertEquals(HandoverStatus.ACKNOWLEDGED, ack.status());
        }
    }

    @Test
    @DisplayName("4. Workload Analytics: Calculate workload metrics and ratings")
    void testWorkloadCalculation() {
        LocalDate start = LocalDate.now().minusWeeks(1);
        LocalDate end = LocalDate.now().plusWeeks(1);

        WorkloadReportResponse res = workloadService.calculateWorkload(start, end, null);
        assertNotNull(res);
        assertTrue(res.totalEmployees() >= 0);
        assertNotNull(res.employeeWorkloads());

        for (EmployeeWorkloadMetric m : res.employeeWorkloads()) {
            assertNotNull(m.employeeCode());
            assertNotNull(m.workloadRating());
            assertTrue(m.workloadScore() >= 0);
            assertTrue(m.workingDays() >= 0);
            assertTrue(m.offDays() >= 0);
        }
    }

    @Test
    @DisplayName("5. Workload Analytics: Single employee filtering")
    void testWorkloadEmployeeFilter() {
        Employee emp = employeeRepository.findAll().get(0);
        WorkloadReportResponse res = workloadService.calculateWorkload(LocalDate.now().minusWeeks(1), LocalDate.now().plusWeeks(1), emp.getId());
        assertNotNull(res);
        assertEquals(1, res.totalEmployees());
        assertEquals(emp.getEmployeeCode(), res.employeeWorkloads().get(0).employeeCode());
    }

    @Test
    @DisplayName("6. Export Center: Generate Roster, Employee, and Leave XLSX/CSV exports")
    void testExportGeneration() {
        LocalDate start = LocalDate.now().minusWeeks(1);
        LocalDate end = LocalDate.now().plusWeeks(1);

        // Weekly Roster XLSX
        byte[] rosterXlsx = exportCenterService.generateExport(new ExportReportRequest("WEEKLY_ROSTER", "xlsx", start, end, null, null, null));
        assertNotNull(rosterXlsx);
        assertTrue(rosterXlsx.length > 0);

        // Employee Master CSV
        byte[] empCsv = exportCenterService.generateExport(new ExportReportRequest("EMPLOYEE_MASTER", "csv", start, end, null, null, null));
        assertNotNull(empCsv);
        assertTrue(empCsv.length > 0);

        // Leave Register PDF
        byte[] leavePdf = exportCenterService.generateExport(new ExportReportRequest("LEAVE_REGISTER", "pdf", start, end, null, null, null));
        assertNotNull(leavePdf);
        assertTrue(leavePdf.length > 0);

        // Workload Report XLSX
        byte[] workloadXlsx = exportCenterService.generateExport(new ExportReportRequest("WORKLOAD_REPORT", "xlsx", start, end, null, null, null));
        assertNotNull(workloadXlsx);
        assertTrue(workloadXlsx.length > 0);
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("7. Security: Admin can access export center and workload management")
    void testAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/exports/download?reportType=WEEKLY_ROSTER&format=xlsx").accept(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/workload").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    @DisplayName("8. Security: Employee blocked from Admin Export Center (403 Forbidden)")
    void testEmployeeBlockedFromAdminExports() throws Exception {
        mockMvc.perform(get("/api/admin/exports/download?reportType=WEEKLY_ROSTER&format=xlsx"))
                .andExpect(status().isForbidden());
    }
}