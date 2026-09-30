package com.weeklyroster.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.weeklyroster.dto.request.EmployeeRequest;
import com.weeklyroster.dto.request.HolidayRequest;
import com.weeklyroster.dto.response.WorkDayReportResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import com.weeklyroster.service.EmployeeService;
import com.weeklyroster.service.HolidayService;
import com.weeklyroster.service.WorkDayReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"wrms.dev.credential-mirror.enabled=false"})
public class Batch66AuditAutoIdHolidayWorkReportTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private HolidayRepository holidayRepository;

    @Autowired
    private HolidayService holidayService;

    @Autowired
    private WorkDayReportService workDayReportService;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

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
    @DisplayName("Auto Employee ID: generateNextEmployeeCode increments correctly and preserves leading zeros")
    void testAutoEmployeeIdGenerationSequential() {
        String nextCode = employeeService.generateNextEmployeeCode();
        assertNotNull(nextCode);
        assertTrue(nextCode.startsWith("EMP"));

        // If highest in DB is EMP008, nextCode should be EMP009
        // Test pattern handling
        List<String> existing = employeeRepository.findAllEmployeeCodes();
        int maxSuffix = 0;
        for (String c : existing) {
            if (c != null && c.matches("^[A-Za-z]+(\\d+)$")) {
                int num = Integer.parseInt(c.replaceAll("^[A-Za-z]+", ""));
                if (num > maxSuffix) maxSuffix = num;
            }
        }
        int nextNum = Integer.parseInt(nextCode.replaceAll("^[A-Za-z]+", ""));
        assertEquals(maxSuffix + 1, nextNum);
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("Auto Employee ID: Admin can access /api/admin/employees/next-id")
    void testNextEmployeeIdEndpointAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/employees/next-id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").exists())
                .andExpect(jsonPath("$.employeeId").value(org.hamcrest.Matchers.startsWith("EMP")));
    }

    @Test
    @WithMockUser(username = "employee", authorities = {"ROLE_EMPLOYEE"})
    @DisplayName("Auto Employee ID: Employee cannot access /api/admin/employees/next-id (Forbidden 403)")
    void testNextEmployeeIdEndpointEmployeeForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/employees/next-id"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("Auto Employee ID: Add employee with empty employeeCode auto-generates ID")
    void testAddEmployeeAutoGeneratesCodeWhenBlank() {
        long suffix = System.currentTimeMillis();
        EmployeeRequest req = new EmployeeRequest(
                "", // Blank code -> should auto generate
                "AutoFirst",
                "AutoLast",
                "autofirst." + suffix + "@example.com",
                Gender.MALE,
                "9876543210",
                "autouser" + suffix,
                "Password@123"
        );

        var response = employeeService.create(req);
        assertNotNull(response);
        assertNotNull(response.employeeCode());
        assertTrue(response.employeeCode().startsWith("EMP"));
        assertFalse(response.employeeCode().isBlank());
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("Holiday Management: Admin can create, update, toggle active and delete holiday")
    void testHolidayCrudFlow() throws Exception {
        LocalDate holidayDate = LocalDate.of(2026, 8, 15);
        HolidayRequest req = new HolidayRequest(
                "Independence Day 2026",
                holidayDate,
                "National Gazetted Holiday",
                true
        );

        // 1. Create Holiday
        String respStr = mockMvc.perform(post("/api/admin/holidays")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Independence Day 2026"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString();

        var jsonNode = objectMapper.readTree(respStr);
        long holidayId = jsonNode.get("id").asLong();

        // 2. Fetch Active Holidays
        mockMvc.perform(get("/api/holidays"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + holidayId + ")].name").value("Independence Day 2026"));

        // 3. Toggle Status to Inactive
        mockMvc.perform(patch("/api/admin/holidays/" + holidayId + "/toggle-active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        // 4. Delete Holiday
        mockMvc.perform(delete("/api/admin/holidays/" + holidayId))
                .andExpect(status().isNoContent());

        assertFalse(holidayRepository.existsById(holidayId));
    }

    @Test
    @WithMockUser(username = "employee", authorities = {"ROLE_EMPLOYEE"})
    @DisplayName("Holiday Management: Employee cannot create or modify holidays (Forbidden 403)")
    void testHolidayEmployeeForbidden() throws Exception {
        HolidayRequest req = new HolidayRequest(
                "Gandhi Jayanti",
                LocalDate.of(2026, 10, 2),
                "National Holiday",
                true
        );

        mockMvc.perform(post("/api/admin/holidays")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("Admin Work-Day Report: Correct calculation and strict priority (Worked vs Holiday vs Leave vs Off)")
    void testWorkDayReportCalculation() throws Exception {
        // Setup employee
        long suffix = System.currentTimeMillis();
        Employee emp = new Employee();
        emp.setEmployeeCode("T66" + (suffix % 100000));
        emp.setFirstName("Audit");
        emp.setLastName("Worker");
        emp.setEmail("audit.worker" + suffix + "@example.com");
        emp.setGender(Gender.MALE);
        emp.setActive(true);
        emp = employeeRepository.save(emp);

        LocalDate d1 = LocalDate.of(2026, 9, 1); // Worked
        LocalDate d2 = LocalDate.of(2026, 9, 2); // Holiday
        LocalDate d3 = LocalDate.of(2026, 9, 3); // Leave
        LocalDate d4 = LocalDate.of(2026, 9, 4); // Weekly Off

        // 1. Create Holiday on d2
        Holiday holiday = new Holiday("Test Holiday", d2, "Official Test Holiday", true);
        holidayRepository.save(holiday);

        // 2. Create Approved Leave on d3
        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(emp);
        leave.setStartDate(d3);
        leave.setEndDate(d3);
        leave.setReason("Medical Checkup");
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setRequestedAt(java.time.LocalDateTime.now());
        leaveRequestRepository.save(leave);

        // Fetch Morning shift
        Shift morningShift = shiftRepository.findByShiftType(ShiftType.MORNING).orElse(null);

        // 3. Create Roster cycle & assignments
        RosterCycle cycle = new RosterCycle();
        cycle.setStartDate(d1);
        cycle.setEndDate(d4);
        cycle.setStatus(RosterStatus.ACTIVE);
        cycle.setGeneratedAt(java.time.LocalDateTime.now());
        cycle = cycleRepository.save(cycle);

        // Assignment for d1: Worked
        RosterAssignment a1 = new RosterAssignment();
        a1.setCycle(cycle);
        a1.setEmployee(emp);
        a1.setRosterDate(d1);
        a1.setShift(morningShift);
        a1.setWeeklyOff(false);
        a1.setOnLeave(false);
        assignmentRepository.save(a1);

        // Assignment for d4: Weekly Off
        RosterAssignment a4 = new RosterAssignment();
        a4.setCycle(cycle);
        a4.setEmployee(emp);
        a4.setRosterDate(d4);
        Shift offShift = shiftRepository.findByShiftType(ShiftType.OFF).orElse(morningShift);
        a4.setShift(offShift);
        a4.setWeeklyOff(true);
        a4.setOnLeave(false);
        assignmentRepository.save(a4);

        // Execute report service
        WorkDayReportResponse report = workDayReportService.generateReport(d1, d4, emp.getId(), null);
        assertNotNull(report);
        assertEquals(1, report.employeeSummaries().size());

        var empSummary = report.employeeSummaries().get(0);
        assertEquals(emp.getId(), empSummary.employeeId());
        assertEquals(4, empSummary.totalPeriodDays());
        assertEquals(1, empSummary.workedDays(), "d1 must be counted as 1 worked day");
        assertEquals(1, empSummary.holidayDays(), "d2 must be counted as 1 holiday day, not worked");
        assertEquals(1, empSummary.leaveDays(), "d3 must be counted as 1 leave day, not worked");
        assertEquals(1, empSummary.weeklyOffDays(), "d4 must be counted as 1 weekly off day, not worked");

        // Verify API endpoint
        mockMvc.perform(get("/api/admin/reports/work-days")
                        .param("startDate", d1.toString())
                        .param("endDate", d4.toString())
                        .param("employeeId", emp.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPeriodDays").value(4))
                .andExpect(jsonPath("$.employeeSummaries[0].workedDays").value(1))
                .andExpect(jsonPath("$.employeeSummaries[0].holidayDays").value(1))
                .andExpect(jsonPath("$.employeeSummaries[0].leaveDays").value(1))
                .andExpect(jsonPath("$.employeeSummaries[0].weeklyOffDays").value(1));
    }

    @Test
    @WithMockUser(username = "employee", authorities = {"ROLE_EMPLOYEE"})
    @DisplayName("Admin Work-Day Report: Employee cannot access /api/admin/reports/work-days (Forbidden 403)")
    void testWorkDayReportEmployeeForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/reports/work-days")
                        .param("startDate", "2026-09-01")
                        .param("endDate", "2026-09-07"))
                .andExpect(status().isForbidden());
    }
}
