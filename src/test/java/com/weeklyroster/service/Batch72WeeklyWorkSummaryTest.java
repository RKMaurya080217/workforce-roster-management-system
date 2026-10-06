package com.weeklyroster.service;

import com.weeklyroster.dto.response.WeeklyEmployeeWorkSummary;
import com.weeklyroster.dto.response.WeeklyWorkSummaryResponse;
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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"wrms.dev.credential-mirror.enabled=false"})
public class Batch72WeeklyWorkSummaryTest {

    @Autowired
    private MockMvc mockMvc;

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
            if (code != null && code.startsWith("B72")) {
                User u = emp.getUser();
                employeeRepository.delete(emp);
                if (u != null) {
                    userRepository.delete(u);
                }
            }
        }
    }

    private Employee createTestEmployee(String suffix) {
        Employee emp = new Employee();
        emp.setEmployeeCode("B72E" + suffix);
        emp.setFirstName("Weekly");
        emp.setLastName("Worker" + suffix);
        emp.setEmail("weekly.worker" + suffix + "@example.com");
        emp.setGender(Gender.MALE);
        emp.setActive(true);
        return employeeRepository.save(emp);
    }

    @Test
    @DisplayName("Normal Week: 5 worked days, 2 weekly offs equals 7 total accounted days")
    void testNormalWeekCalculation() {
        Employee emp = createTestEmployee("001");

        // Next week Monday to Sunday
        LocalDate nextMon = LocalDate.now().plusWeeks(2).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate nextSun = nextMon.plusDays(6);

        Shift morning = shiftRepository.findByShiftType(ShiftType.MORNING).orElse(null);
        Shift off = shiftRepository.findByShiftType(ShiftType.OFF).orElse(morning);

        RosterCycle cycle = new RosterCycle();
        cycle.setStartDate(nextMon);
        cycle.setEndDate(nextSun);
        cycle.setStatus(RosterStatus.PUBLISHED);
        cycle.setGeneratedAt(LocalDateTime.now());
        cycle = cycleRepository.save(cycle);

        // Mon-Fri worked, Sat-Sun off
        for (int i = 0; i < 5; i++) {
            RosterAssignment a = new RosterAssignment();
            a.setCycle(cycle);
            a.setEmployee(emp);
            a.setRosterDate(nextMon.plusDays(i));
            a.setShift(morning);
            a.setWeeklyOff(false);
            a.setOnLeave(false);
            assignmentRepository.save(a);
        }
        for (int i = 5; i < 7; i++) {
            RosterAssignment a = new RosterAssignment();
            a.setCycle(cycle);
            a.setEmployee(emp);
            a.setRosterDate(nextMon.plusDays(i));
            a.setShift(off);
            a.setWeeklyOff(true);
            a.setOnLeave(false);
            assignmentRepository.save(a);
        }

        WeeklyWorkSummaryResponse response = workDayReportService.generateWeeklyWorkSummary(nextMon, emp.getId());
        assertNotNull(response);
        assertEquals(7, response.totalPeriodDays());
        assertEquals(1, response.totalEmployees());
        assertEquals(5, response.totalWorkedDays());
        assertEquals(0, response.totalLeaveDays());
        assertEquals(0, response.totalHolidayDays());
        assertEquals(2, response.totalWeeklyOffDays());
        assertEquals(0, response.totalAbsentDays());
        assertEquals(7, response.totalDaysAccounted());

        WeeklyEmployeeWorkSummary empSummary = response.employeeSummaries().get(0);
        assertEquals(5, empSummary.workedDays());
        assertEquals(2, empSummary.weeklyOffDays());
        assertEquals(7, empSummary.totalDays());
        assertEquals(7, empSummary.dailyRecords().size());
    }

    @Test
    @DisplayName("Priority Hierarchy: Holiday (P1) > Leave (P2) > Weekly Off (P3) > Worked (P4)")
    void testPriorityHierarchy() {
        Employee emp = createTestEmployee("002");

        LocalDate mon = LocalDate.now().plusWeeks(3).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate tue = mon.plusDays(1);
        LocalDate wed = mon.plusDays(2);
        LocalDate thu = mon.plusDays(3);
        LocalDate fri = mon.plusDays(4);
        LocalDate sat = mon.plusDays(5);
        LocalDate sun = mon.plusDays(6);

        // 1. Holiday on Monday
        Holiday hol = new Holiday("Test National Holiday", mon, "National", true);
        holidayRepository.save(hol);

        // 2. Approved leave on Tuesday
        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(emp);
        leave.setStartDate(tue);
        leave.setEndDate(tue);
        leave.setReason("Doctor visit");
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setRequestedAt(LocalDateTime.now());
        leaveRequestRepository.save(leave);

        // 3. Roster assignments: Even if Monday and Tuesday have worked shifts assigned, Holiday & Leave take priority
        Shift morning = shiftRepository.findByShiftType(ShiftType.MORNING).orElse(null);
        Shift off = shiftRepository.findByShiftType(ShiftType.OFF).orElse(morning);

        RosterCycle cycle = new RosterCycle();
        cycle.setStartDate(mon);
        cycle.setEndDate(sun);
        cycle.setStatus(RosterStatus.PUBLISHED);
        cycle.setGeneratedAt(LocalDateTime.now());
        cycle = cycleRepository.save(cycle);

        // Assign morning shift to Mon, Tue, Wed, Thu, Fri
        for (LocalDate d : List.of(mon, tue, wed, thu, fri)) {
            RosterAssignment a = new RosterAssignment();
            a.setCycle(cycle);
            a.setEmployee(emp);
            a.setRosterDate(d);
            a.setShift(morning);
            a.setWeeklyOff(false);
            a.setOnLeave(false);
            assignmentRepository.save(a);
        }

        // Sat: weekly off
        RosterAssignment aSat = new RosterAssignment();
        aSat.setCycle(cycle);
        aSat.setEmployee(emp);
        aSat.setRosterDate(sat);
        aSat.setShift(off);
        aSat.setWeeklyOff(true);
        aSat.setOnLeave(false);
        assignmentRepository.save(aSat);

        // Sun: No assignment in DB (Unassigned / Absent)

        WeeklyWorkSummaryResponse response = workDayReportService.generateWeeklyWorkSummary(mon, emp.getId());
        assertNotNull(response);

        WeeklyEmployeeWorkSummary summary = response.employeeSummaries().get(0);
        // Monday = Holiday (1)
        assertEquals(1, summary.holidayDays(), "Monday must be counted as Holiday");
        // Tuesday = Leave (1)
        assertEquals(1, summary.leaveDays(), "Tuesday must be counted as Approved Leave");
        // Wed, Thu, Fri = Worked (3)
        assertEquals(3, summary.workedDays(), "Wed, Thu, Fri must be counted as Worked");
        // Sat = Weekly Off (1)
        assertEquals(1, summary.weeklyOffDays(), "Sat must be counted as Weekly Off");
        // Sun = Absent / Unassigned (1)
        assertEquals(1, summary.absentDays(), "Sun must be counted as Absent / Unassigned");
        // Total must be exactly 7
        assertEquals(7, summary.totalDays(), "Total accounted days for employee must strictly equal 7");
    }

    @Test
    @DisplayName("Duplicate Protection: In-memory service deduplication prevents multiple assignments on same date from inflating count")
    void testDuplicateProtection() {
        Employee emp = createTestEmployee("003");

        LocalDate mon = LocalDate.now().plusWeeks(4).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Shift morning = shiftRepository.findByShiftType(ShiftType.MORNING).orElse(null);
        Shift general = shiftRepository.findByShiftType(ShiftType.GENERAL).orElse(morning);

        RosterAssignment a1 = new RosterAssignment();
        a1.setEmployee(emp);
        a1.setRosterDate(mon);
        a1.setShift(morning);
        a1.setWeeklyOff(false);

        RosterAssignment a2 = new RosterAssignment();
        a2.setEmployee(emp);
        a2.setRosterDate(mon);
        a2.setShift(general);
        a2.setWeeklyOff(false);

        EmployeeRepository mockEmpRepo = org.mockito.Mockito.mock(EmployeeRepository.class);
        org.mockito.Mockito.when(mockEmpRepo.findById(emp.getId())).thenReturn(java.util.Optional.of(emp));

        HolidayRepository mockHolRepo = org.mockito.Mockito.mock(HolidayRepository.class);
        org.mockito.Mockito.when(mockHolRepo.findByHolidayDateBetweenOrderByHolidayDateAsc(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        LeaveRequestRepository mockLeaveRepo = org.mockito.Mockito.mock(LeaveRequestRepository.class);
        org.mockito.Mockito.when(mockLeaveRepo.findAll()).thenReturn(List.of());

        RosterAssignmentRepository mockAssignRepo = org.mockito.Mockito.mock(RosterAssignmentRepository.class);
        org.mockito.Mockito.when(mockAssignRepo.findByRosterDateBetweenOrderByRosterDateAsc(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(a1, a2));

        WorkDayReportService unitService = new WorkDayReportService(mockEmpRepo, mockAssignRepo, mockLeaveRepo, mockHolRepo);
        WeeklyWorkSummaryResponse response = unitService.generateWeeklyWorkSummary(mon, emp.getId());
        WeeklyEmployeeWorkSummary summary = response.employeeSummaries().get(0);

        // Monday should count ONCE as worked, and the other 6 days are unassigned
        assertEquals(1, summary.workedDays(), "Duplicate assignment must not inflate worked days count");
        assertEquals(6, summary.absentDays(), "Remaining 6 days should be unassigned");
        assertEquals(7, summary.totalDays(), "Total days must strictly equal 7");
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("Security: Admin can access /api/admin/reports/weekly-work-summary")
    void testAdminAccessWeeklyWorkSummary() throws Exception {
        LocalDate mon = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        mockMvc.perform(get("/api/admin/reports/weekly-work-summary")
                        .param("weekStart", mon.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weekStartDate").value(mon.toString()))
                .andExpect(jsonPath("$.totalPeriodDays").value(7))
                .andExpect(jsonPath("$.employeeSummaries").isArray());
    }

    @Test
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    @DisplayName("Security: Employee is forbidden from accessing /api/admin/reports/weekly-work-summary (403)")
    void testEmployeeForbiddenWeeklyWorkSummary() throws Exception {
        LocalDate mon = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        mockMvc.perform(get("/api/admin/reports/weekly-work-summary")
                        .param("weekStart", mon.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Frontend Integrity: Admin navigation and enterprise app logic verified")
    void testFrontendWiringIntegrity() throws Exception {
        assertTrue(Files.exists(APP_JS), "app.js must exist");
        assertTrue(Files.exists(ENTERPRISE_APP_JS), "enterprise-app.js must exist");

        String appJsContent = Files.readString(APP_JS, StandardCharsets.UTF_8);
        String entJsContent = Files.readString(ENTERPRISE_APP_JS, StandardCharsets.UTF_8);

        // 1. app.js contains view and routes
        assertTrue(appJsContent.contains("adminWeeklyWorkSummary"), "app.js must register adminWeeklyWorkSummary");
        assertTrue(appJsContent.contains("weekly-work-summary"), "app.js must contain weekly-work-summary route");
        assertTrue(appJsContent.contains("renderAdminWeeklyWorkSummaryView"), "app.js must call renderAdminWeeklyWorkSummaryView");

        // 2. EMPLOYEE_NAV does NOT contain weekly-work-summary
        int empNavIndex = appJsContent.indexOf("const EMPLOYEE_NAV = [");
        assertTrue(empNavIndex > 0, "EMPLOYEE_NAV must be defined");
        int empNavEnd = appJsContent.indexOf("];", empNavIndex);
        String empNavBlock = appJsContent.substring(empNavIndex, empNavEnd);
        assertFalse(empNavBlock.contains("weekly-work-summary"), "EMPLOYEE_NAV must not expose weekly-work-summary");
        assertFalse(empNavBlock.contains("adminWeeklyWorkSummary"), "EMPLOYEE_NAV must not expose adminWeeklyWorkSummary");

        // 3. enterprise-app.js contains view rendering and modal drilldown functions
        assertTrue(entJsContent.contains("renderAdminWeeklyWorkSummaryView"), "enterprise-app.js must implement renderAdminWeeklyWorkSummaryView");
        assertTrue(entJsContent.contains("openWeeklyWorkSummaryDetailModal"), "enterprise-app.js must implement openWeeklyWorkSummaryDetailModal");
        assertTrue(entJsContent.contains("exportWeeklySummaryData"), "enterprise-app.js must implement exportWeeklySummaryData");
    }
}
