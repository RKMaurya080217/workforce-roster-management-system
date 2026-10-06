package com.weeklyroster.service;

import com.weeklyroster.dto.request.EmployeeRequest;
import com.weeklyroster.dto.response.EmployeeResponse;
import com.weeklyroster.dto.response.WorkDayReportResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"wrms.dev.credential-mirror.enabled=false"})
public class Batch70AutoEmployeeIdAndWorkReportTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private HolidayRepository holidayRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private WorkDayReportService workDayReportService;

    private static final Path APP_JS = Path.of("src/main/resources/static/app.js");
    private static final Path ENTERPRISE_APP_JS = Path.of("src/main/resources/static/enterprise-app.js");

    @AfterEach
    void cleanup() {
        List<Employee> allEmployees = employeeRepository.findAll();
        for (Employee emp : allEmployees) {
            String code = emp.getEmployeeCode();
            if (code != null && !code.matches("^EMP00[1-7]$")) {
                User u = emp.getUser();
                employeeRepository.delete(emp);
                if (u != null) {
                    userRepository.delete(u);
                }
            }
        }
    }

    @Test
    @DisplayName("Auto Employee ID: Sequential generation correctly calculates max + 1 (not row count) and handles leading zeros")
    void testAutoEmployeeIdSequenceAndLeadingZeros() {
        String nextId = employeeService.generateNextEmployeeCode();
        assertNotNull(nextId);
        assertTrue(nextId.startsWith("EMP"), "ID must start with EMP");

        // Inspect existing DB codes
        List<String> codes = employeeRepository.findAllEmployeeCodes();
        int max = 0;
        for (String c : codes) {
            if (c != null && c.matches("^EMP(\\d+)$")) {
                int n = Integer.parseInt(c.substring(3));
                if (n > max) max = n;
            }
        }

        int expectedNum = max + 1;
        int nextNum = Integer.parseInt(nextId.substring(3));
        assertEquals(expectedNum, nextNum, "Next ID must be highest numeric + 1");

        // Verify leading zeros: e.g., 8 -> EMP008, 10 -> EMP010
        if (expectedNum < 10) {
            assertEquals("EMP00" + expectedNum, nextId);
        } else if (expectedNum < 100) {
            assertEquals("EMP0" + expectedNum, nextId);
        } else {
            assertEquals("EMP" + expectedNum, nextId);
        }
    }

    @Test
    @DisplayName("Auto Employee ID: Creating employee with blank code persists generated code and increments next")
    void testAutoEmployeeIdCreationAndSequenceIncrement() {
        String code1 = employeeService.generateNextEmployeeCode();
        long ts = System.currentTimeMillis();

        EmployeeRequest req1 = new EmployeeRequest(
                "", // Blank -> auto generate
                "Batch70",
                "Alpha",
                "b70alpha." + ts + "@example.com",
                Gender.MALE,
                "9876543210",
                "b70alpha" + ts,
                "Password@123"
        );

        EmployeeResponse res1 = employeeService.create(req1);
        assertNotNull(res1);
        assertEquals(code1, res1.employeeCode(), "Backend should persist generated code");

        // Next code must now be code1's number + 1
        String code2 = employeeService.generateNextEmployeeCode();
        int num1 = Integer.parseInt(code1.substring(3));
        int num2 = Integer.parseInt(code2.substring(3));
        assertEquals(num1 + 1, num2, "Next generated ID must increment after creation");
    }

    @Test
    @DisplayName("Auto Employee ID: Deactivated employee does not cause duplicate ID reuse")
    void testDeactivatedEmployeeDoesNotCauseDuplicateReuse() {
        long ts = System.currentTimeMillis();
        String code = employeeService.generateNextEmployeeCode();

        EmployeeRequest req = new EmployeeRequest(
                code,
                "Deactivated",
                "Worker",
                "deact." + ts + "@example.com",
                Gender.FEMALE,
                "9876543211",
                "deact" + ts,
                "Password@123"
        );

        EmployeeResponse res = employeeService.create(req);
        Employee emp = employeeRepository.findById(res.id()).orElseThrow();
        emp.setActive(false);
        employeeRepository.save(emp);

        // Next code should still be higher, NOT reusing the deactivated code
        String nextCode = employeeService.generateNextEmployeeCode();
        assertNotEquals(code, nextCode, "Deactivated employee ID must not be reused");
        assertTrue(Integer.parseInt(nextCode.substring(3)) > Integer.parseInt(code.substring(3)));
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("Security: Admin can access /api/admin/employees/next-id")
    void testAdminAccessNextEmployeeId() throws Exception {
        mockMvc.perform(get("/api/admin/employees/next-id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").value(org.hamcrest.Matchers.startsWith("EMP")));
    }

    @Test
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    @DisplayName("Security: Employee is denied access to /api/admin/employees/next-id (403)")
    void testEmployeeForbiddenNextEmployeeId() throws Exception {
        mockMvc.perform(get("/api/admin/employees/next-id"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("Work-Day Report: Calculation distinguishes Worked vs Holiday vs Leave vs Weekly Off without double counting")
    void testWorkDayReportCompleteCalculation() throws Exception {
        long ts = System.currentTimeMillis();
        Employee emp = new Employee();
        emp.setEmployeeCode("B70W" + (ts % 10000));
        emp.setFirstName("Report");
        emp.setLastName("Tester");
        emp.setEmail("report.tester" + ts + "@example.com");
        emp.setGender(Gender.MALE);
        emp.setActive(true);
        emp = employeeRepository.save(emp);

        LocalDate d1 = LocalDate.of(2026, 10, 1); // Worked
        LocalDate d2 = LocalDate.of(2026, 10, 2); // Holiday (Gandhi Jayanti)
        LocalDate d3 = LocalDate.of(2026, 10, 3); // Approved Leave
        LocalDate d4 = LocalDate.of(2026, 10, 4); // Weekly Off

        // 1. Holiday on d2
        Holiday holiday = new Holiday("Gandhi Jayanti", d2, "Gazetted Holiday", true);
        holidayRepository.save(holiday);

        // 2. Approved Leave on d3
        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(emp);
        leave.setStartDate(d3);
        leave.setEndDate(d3);
        leave.setReason("Personal Leave");
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setRequestedAt(java.time.LocalDateTime.now());
        leaveRequestRepository.save(leave);

        // 3. Roster assignments
        Shift morningShift = shiftRepository.findByShiftType(ShiftType.MORNING).orElse(null);
        Shift offShift = shiftRepository.findByShiftType(ShiftType.OFF).orElse(morningShift);

        RosterCycle cycle = new RosterCycle();
        cycle.setStartDate(d1);
        cycle.setEndDate(d4);
        cycle.setStatus(RosterStatus.ACTIVE);
        cycle.setGeneratedAt(java.time.LocalDateTime.now());
        cycle = cycleRepository.save(cycle);

        // d1: Morning worked
        RosterAssignment a1 = new RosterAssignment();
        a1.setCycle(cycle);
        a1.setEmployee(emp);
        a1.setRosterDate(d1);
        a1.setShift(morningShift);
        a1.setWeeklyOff(false);
        a1.setOnLeave(false);
        assignmentRepository.save(a1);

        // d4: Weekly off
        RosterAssignment a4 = new RosterAssignment();
        a4.setCycle(cycle);
        a4.setEmployee(emp);
        a4.setRosterDate(d4);
        a4.setShift(offShift);
        a4.setWeeklyOff(true);
        a4.setOnLeave(false);
        assignmentRepository.save(a4);

        // Generate report for d1 to d4
        WorkDayReportResponse report = workDayReportService.generateReport(d1, d4, emp.getId(), null);
        assertNotNull(report);
        assertEquals(4, report.totalPeriodDays());

        var summary = report.employeeSummaries().get(0);
        assertEquals(1, summary.workedDays(), "d1 must be counted as 1 worked day");
        assertEquals(1, summary.holidayDays(), "d2 must be counted as 1 holiday day, not worked");
        assertEquals(1, summary.leaveDays(), "d3 must be counted as 1 leave day, not worked");
        assertEquals(1, summary.weeklyOffDays(), "d4 must be counted as 1 weekly off day, not worked");

        // Verify API endpoint
        mockMvc.perform(get("/api/admin/reports/work-days")
                        .param("startDate", d1.toString())
                        .param("endDate", d4.toString())
                        .param("employeeId", emp.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeSummaries[0].workedDays").value(1))
                .andExpect(jsonPath("$.employeeSummaries[0].holidayDays").value(1))
                .andExpect(jsonPath("$.employeeSummaries[0].leaveDays").value(1))
                .andExpect(jsonPath("$.employeeSummaries[0].weeklyOffDays").value(1));
    }

    @Test
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    @DisplayName("Security: Employee is denied access to /api/admin/reports/work-days (403)")
    void testEmployeeForbiddenWorkDayReport() throws Exception {
        mockMvc.perform(get("/api/admin/reports/work-days")
                        .param("startDate", "2026-10-01")
                        .param("endDate", "2026-10-07"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Frontend UI: app.js & enterprise-app.js contain Auto ID logic and Work-Day Report markup")
    void testFrontendMarkupIntegrity() throws Exception {
        String appJs = Files.readString(APP_JS, StandardCharsets.UTF_8);
        String entJs = Files.readString(ENTERPRISE_APP_JS, StandardCharsets.UTF_8);

        // Auto ID in app.js
        assertTrue(appJs.contains("/api/admin/employees/next-id"),
                "app.js must call /api/admin/employees/next-id to populate next ID");
        assertTrue(appJs.contains("codeInput.readOnly = true;"),
                "Employee ID code input must be readOnly for automatic population");

        // Work-Day Report in enterprise-app.js
        assertTrue(entJs.contains("<h2>Employee Work-Day Report</h2>"),
                "enterprise-app.js must render Employee Work-Day Report header");
        assertTrue(entJs.contains("<th>Employee ID</th>"),
                "enterprise-app.js must render Employee ID column header");
        assertTrue(entJs.contains("No work records found for the selected period."),
                "enterprise-app.js must show exact empty result message");
        assertTrue(entJs.contains("/api/admin/reports/work-days"),
                "enterprise-app.js must query /api/admin/reports/work-days");
    }
}
