package com.weeklyroster.service;

import com.weeklyroster.dto.request.ExportReportRequest;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Batch62ModernizationAndSmsRemovalTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExportCenterService exportCenterService;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private SkillRepository skillRepository;

    @Autowired
    private EmployeeSkillRepository employeeSkillRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private HolidayRepository holidayRepository;

    private Long testCycleId;
    private Long testEmpId;

    @BeforeEach
    void setupTestData() {
        // 1. Ensure test employee exists
        List<Employee> emps = employeeRepository.findAll();
        Employee emp;
        if (emps.isEmpty()) {
            emp = new Employee();
            emp.setEmployeeCode("EMP-B62");
            emp.setFirstName("Batch62");
            emp.setLastName("Tester");
            emp.setEmail("batch62_tester@example.com");
            emp.setGender(Gender.MALE);
            emp.setActive(true);
            emp = employeeRepository.save(emp);
        } else {
            emp = emps.get(0);
        }
        testEmpId = emp.getId();

        // 2. Ensure test shift exists
        Shift shift = shiftRepository.findByShiftType(ShiftType.MORNING).orElseGet(() -> {
            Shift s = new Shift();
            s.setShiftType(ShiftType.MORNING);
            s.setCapacity(2);
            s.setActive(true);
            return shiftRepository.save(s);
        });

        // 3. Ensure published RosterCycle with assignment
        LocalDate monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate sunday = monday.plusDays(6);
        RosterCycle cycle = cycleRepository.findByStartDateAndEndDate(monday, sunday).orElseGet(() -> {
            RosterCycle c = new RosterCycle();
            c.setStartDate(monday);
            c.setEndDate(sunday);
            c.setStatus(RosterStatus.PUBLISHED);
            c.setGenerationMode(GenerationMode.AUTOMATIC);
            c.setGeneratedAt(LocalDateTime.now());
            return cycleRepository.save(c);
        });
        testCycleId = cycle.getId();

        if (assignmentRepository.findByCycleIdOrderByRosterDateAsc(testCycleId).isEmpty()) {
            RosterAssignment assignment = new RosterAssignment();
            assignment.setCycle(cycle);
            assignment.setEmployee(emp);
            assignment.setShift(shift);
            assignment.setRosterDate(monday);
            assignmentRepository.save(assignment);
        }

        // 4. Ensure leave request exists
        if (leaveRequestRepository.count() == 0) {
            LeaveRequest lr = new LeaveRequest();
            lr.setEmployee(emp);
            lr.setStartDate(monday);
            lr.setEndDate(monday.plusDays(1));
            lr.setStatus(LeaveStatus.APPROVED);
            lr.setReason("Batch 62 Annual Leave");
            lr.setRequestedAt(LocalDateTime.now());
            leaveRequestRepository.save(lr);
        }

        // 5. Ensure skill & employee_skill exists
        if (skillRepository.count() == 0) {
            Skill s = new Skill("Java", "Backend", "Core Java Programming");
            s = skillRepository.save(s);
            EmployeeSkill es = new EmployeeSkill();
            es.setEmployee(emp);
            es.setSkill(s);
            es.setProficiencyLevel(ProficiencyLevel.EXPERT);
            employeeSkillRepository.save(es);
        } else if (employeeSkillRepository.count() == 0) {
            Skill s = skillRepository.findAll().get(0);
            EmployeeSkill es = new EmployeeSkill();
            es.setEmployee(emp);
            es.setSkill(s);
            es.setProficiencyLevel(ProficiencyLevel.EXPERT);
            employeeSkillRepository.save(es);
        }

        // 6. Ensure holiday exists
        if (holidayRepository.count() == 0) {
            Holiday h = new Holiday();
            h.setName("Batch 62 Holiday");
            h.setHolidayDate(monday.plusDays(2));
            h.setDescription("Testing Holiday Export");
            h.setActive(true);
            holidayRepository.save(h);
        }

        // 7. Ensure audit log exists
        if (auditLogRepository.count() == 0) {
            AuditLog al = new AuditLog();
            al.setActor("admin");
            al.setAction(AuditAction.REPORT_EXPORTED);
            al.setEntityType("RosterCycle");
            al.setEntityId(testCycleId);
            al.setEmployeeName("Batch62 Tester");
            al.setOldValue("-");
            al.setNewValue("EXPORTED");
            al.setReason("Batch 62 Audit Verification");
            al.setSource("SYSTEM");
            al.setTimestamp(LocalDateTime.now());
            auditLogRepository.save(al);
        }
    }

    @Test
    @Order(1)
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("Batch 62: SMS beans and endpoints are completely removed from ApplicationContext")
    void testSmsFeatureCompleteRemoval() throws Exception {
        assertFalse(applicationContext.containsBean("smsController"), "SmsController bean must NOT exist");
        assertFalse(applicationContext.containsBean("smsService"), "SmsService bean must NOT exist");
        assertFalse(applicationContext.containsBean("smsServiceImpl"), "SmsServiceImpl bean must NOT exist");
        assertFalse(applicationContext.containsBean("smsDeliveryLogRepository"), "SmsDeliveryLogRepository bean must NOT exist");

        // Verify that SMS endpoint returns 404
        mockMvc.perform(get("/api/sms/diagnostics"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(2)
    @DisplayName("Batch 62: Export Center generates non-empty populated bytes (>50 B) for Weekly Roster")
    void testExportWeeklyRosterAllFormats() {
        for (String format : List.of("csv", "excel", "pdf", "png")) {
            ExportReportRequest req = new ExportReportRequest(
                    "WEEKLY_ROSTER", format, null, null, testCycleId, null, null
            );
            byte[] bytes = exportCenterService.generateExport(req);
            assertNotNull(bytes, "Bytes must not be null for WEEKLY_ROSTER " + format);
            assertTrue(bytes.length > 50, "Export for " + format + " must contain actual data, bytes=" + bytes.length);
        }
    }

    @Test
    @Order(3)
    @DisplayName("Batch 62: Export Center generates non-empty populated bytes for Workforce & Operational reports")
    void testExportOtherReports() {
        // Employee Master
        byte[] empMaster = exportCenterService.generateExport(
                new ExportReportRequest("EMPLOYEE_MASTER", "excel", null, null, null, null, null)
        );
        assertNotNull(empMaster);
        assertTrue(empMaster.length > 100);

        // Leave Register
        byte[] leaveReg = exportCenterService.generateExport(
                new ExportReportRequest("LEAVE_REGISTER", "csv", null, null, null, null, null)
        );
        assertNotNull(leaveReg);
        assertTrue(leaveReg.length > 50);

        // Skill Matrix
        byte[] skillMatrix = exportCenterService.generateExport(
                new ExportReportRequest("SKILL_MATRIX", "excel", null, null, null, null, null)
        );
        assertNotNull(skillMatrix);
        assertTrue(skillMatrix.length > 100);

        // Shift Capacity
        byte[] shiftCap = exportCenterService.generateExport(
                new ExportReportRequest("SHIFT_CAPACITY", "pdf", null, null, null, null, null)
        );
        assertNotNull(shiftCap);
        assertTrue(shiftCap.length > 100);

        // Holiday Calendar
        byte[] holidays = exportCenterService.generateExport(
                new ExportReportRequest("HOLIDAY_CALENDAR", "csv", null, null, null, null, null)
        );
        assertNotNull(holidays);
        assertTrue(holidays.length > 30);

        // Audit Report
        byte[] audits = exportCenterService.generateExport(
                new ExportReportRequest("AUDIT_REPORT", "excel", null, null, null, null, null)
        );
        assertNotNull(audits);
        assertTrue(audits.length > 100);
    }

    @Test
    @Order(4)
    @DisplayName("Batch 62: Blank Export Guard rejects download with BusinessException when no data exists")
    void testBlankExportGuardThrowsBusinessException() {
        // Query for a non-existent cycle and date range where no records can be found
        ExportReportRequest emptyReq = new ExportReportRequest(
                "WEEKLY_ROSTER", "excel", LocalDate.of(2010, 1, 1), LocalDate.of(2010, 1, 7), 999999L, null, null
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> {
            exportCenterService.generateExport(emptyReq);
        });

        assertTrue(ex.getMessage().contains("No data available for the selected criteria"),
                "Exception message must indicate no data available, but got: " + ex.getMessage());
    }

    @Test
    @Order(5)
    @DisplayName("Batch 62: Frontend UI integrity checks - Single auth footer, mobile drawer, and modern CSS")
    void testFrontendUIIntegrity() throws Exception {
        File indexHtml = new File("src/main/resources/static/index.html");
        assertTrue(indexHtml.exists(), "index.html must exist");
        String htmlContent = Files.readString(indexHtml.toPath());

        // Exactly one footer in auth view
        assertFalse(htmlContent.contains("<footer class=\"auth-footer\">"),
                "Nested auth-footer inside auth card must be removed");
        assertTrue(htmlContent.contains("auth-page-footer"),
                "Single auth-page-footer must exist");

        // Mobile drawer close button and backdrop
        assertTrue(htmlContent.contains("sidebarMobileCloseBtn"),
                "Mobile sidebar close button must be present in sidebar header");
        assertTrue(htmlContent.contains("sidebarMobileBackdrop"),
                "Sidebar mobile backdrop must be present for mobile drawer");

        // SMS diagnostics button removed
        assertFalse(htmlContent.contains("smsDiagnosticsBtn"),
                "smsDiagnosticsBtn must NOT exist in index.html");

        // styles.css checks
        File stylesCss = new File("src/main/resources/static/styles.css");
        assertTrue(stylesCss.exists(), "styles.css must exist");
        String cssContent = Files.readString(stylesCss.toPath());

        assertTrue(cssContent.contains(".app-sidebar.collapsed"),
                "Collapsed sidebar styles must be defined");
        assertTrue(cssContent.contains("sidebar-mobile-close-btn"),
                "Mobile close button styling must be defined");
        assertTrue(cssContent.contains("sidebar-mobile-backdrop"),
                "Sidebar mobile backdrop styling must be defined");
    }
}
