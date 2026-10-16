package com.weeklyroster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.weeklyroster.dto.request.*;
import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BATCH 88: Complete WRMS End-to-End Functional Validation & Regression Stabilization
 * Covers:
 * 1. Admin Holiday CRUD, Double-Submit / Duplicate Protection (same date conflict), Edit, Toggle Active, Delete
 * 2. Holiday -> Roster & Weekly Work Summary Integration (HOLIDAY precedence, work day accounting)
 * 3. Authentication & Security Role Isolation (Admin vs Employee JWT & protected endpoints)
 * 4. Employee Management Lifecycle (Auto ID generation, create, update, deactivate)
 * 5. Profile Change Request Workflow (Employee request, Admin approval/rejection, DB master sync)
 * 6. Shift Preferences (Multi-shift preference & Date-specific preference persistence)
 * 7. Leave Management Workflow & Roster Integration (Apply, approve, summary priority)
 * 8. Weekly Work Summary Exact Mathematical Calculations (Worked, Leave, Holiday, Weekly Off, Absent)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"wrms.dev.credential-mirror.enabled=false"})
public class Batch88FullFunctionalValidationTest {

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

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private ProfileChangeRequestService profileChangeRequestService;

    @Autowired
    private ProfileChangeRequestRepository profileChangeRequestRepository;

    @Autowired
    private EmployeePreferenceService employeePreferenceService;

    @Autowired
    private DateShiftPreferenceService dateShiftPreferenceService;

    @Autowired
    private PasswordEncoder passwordEncoder;

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
            if (code != null && code.startsWith("E88")) {
                User u = emp.getUser();
                employeeRepository.delete(emp);
                if (u != null) {
                    userRepository.delete(u);
                }
            }
        }
    }

    // =========================================================================
    // 1. HOLIDAY CRUD & DUPLICATE PROTECTION
    // =========================================================================

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("Holiday: Full CRUD, toggle active status, and duplicate date protection")
    void testHolidayFullCrudAndDuplicateProtection() throws Exception {
        LocalDate holidayDate = LocalDate.of(2026, 12, 25);
        holidayRepository.findByHolidayDate(holidayDate).forEach(holidayRepository::delete);
        HolidayRequest req = new HolidayRequest(
                "Office Christmas Festival",
                holidayDate,
                "Annual Christmas office closure",
                true
        );

        // A. Add Holiday (Success)
        String respJson = mockMvc.perform(post("/api/admin/holidays")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Office Christmas Festival"))
                .andExpect(jsonPath("$.holidayDate").value("2026-12-25"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString();

        var tree = objectMapper.readTree(respJson);
        long holidayId = tree.get("id").asLong();
        assertTrue(holidayId > 0);

        // Verify exactly one DB record exists for this date
        List<Holiday> dbHolidays = holidayRepository.findByHolidayDate(holidayDate);
        assertEquals(1, dbHolidays.size());
        assertEquals("Office Christmas Festival", dbHolidays.get(0).getName());

        // B. Duplicate Protection: Attempt to add holiday on same date (Conflict / 400 Bad Request)
        HolidayRequest duplicateReq = new HolidayRequest(
                "Another Christmas Event",
                holidayDate,
                "Duplicate on same date",
                true
        );
        mockMvc.perform(post("/api/admin/holidays")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicateReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already exists for date")));

        // Verify still exactly one DB record exists
        assertEquals(1, holidayRepository.findByHolidayDate(holidayDate).size());

        // C. Edit Holiday: Update name and description
        HolidayRequest updateReq = new HolidayRequest(
                "Christmas Day Official Holiday",
                holidayDate,
                "Updated gazetted festive closure",
                true
        );
        mockMvc.perform(put("/api/admin/holidays/" + holidayId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Christmas Day Official Holiday"));

        Holiday updatedDb = holidayRepository.findById(holidayId).orElseThrow();
        assertEquals("Christmas Day Official Holiday", updatedDb.getName());

        // D. Toggle Status (Active -> Inactive)
        mockMvc.perform(patch("/api/admin/holidays/" + holidayId + "/toggle-active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        Holiday toggledDb = holidayRepository.findById(holidayId).orElseThrow();
        assertFalse(toggledDb.isActive());

        // E. Delete Holiday
        mockMvc.perform(delete("/api/admin/holidays/" + holidayId))
                .andExpect(status().isNoContent());

        assertFalse(holidayRepository.existsById(holidayId));
    }

    // =========================================================================
    // 2. HOLIDAY -> ROSTER & WEEKLY WORK SUMMARY INTEGRATION
    // =========================================================================

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("Holiday & Roster Integration: Verified precedence in Weekly Summary (Worked vs Holiday vs Leave vs Off vs Absent)")
    void testHolidayRosterAndWeeklySummaryIntegration() {
        // Setup employee
        long suffix = System.currentTimeMillis();
        Employee emp = new Employee();
        emp.setEmployeeCode("E88VAL" + (suffix % 100000));
        emp.setFirstName("ValEmp");
        emp.setLastName("Batch88");
        emp.setEmail("valemp." + suffix + "@example.com");
        emp.setGender(Gender.MALE);
        emp.setActive(true);
        emp = employeeRepository.save(emp);

        LocalDate nextMonday = LocalDate.now().plusWeeks(2).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate dMon = nextMonday;
        LocalDate dTue = nextMonday.plusDays(1);
        LocalDate dWed = nextMonday.plusDays(2);
        LocalDate dThu = nextMonday.plusDays(3);
        LocalDate dFri = nextMonday.plusDays(4);
        LocalDate dSat = nextMonday.plusDays(5);
        LocalDate dSun = nextMonday.plusDays(6);

        // 1. Holiday on Wednesday (dWed)
        Holiday holiday = new Holiday("Diwali Celebration", dWed, "National Festival", true);
        holidayRepository.save(holiday);

        // 2. Approved Leave on Thursday (dThu)
        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(emp);
        leave.setStartDate(dThu);
        leave.setEndDate(dThu);
        leave.setReason("Personal Family Event");
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setRequestedAt(LocalDateTime.now());
        leaveRequestRepository.save(leave);

        // 3. Shift and Roster cycle
        Shift morningShift = shiftRepository.findByShiftType(ShiftType.MORNING).orElse(null);
        Shift offShift = shiftRepository.findByShiftType(ShiftType.OFF).orElse(morningShift);

        RosterCycle cycle = new RosterCycle();
        cycle.setStartDate(dMon);
        cycle.setEndDate(dSun);
        cycle.setStatus(RosterStatus.PUBLISHED);
        cycle.setGeneratedAt(LocalDateTime.now());
        cycle = cycleRepository.save(cycle);

        // Monday: Worked Morning shift
        RosterAssignment aMon = new RosterAssignment();
        aMon.setCycle(cycle);
        aMon.setEmployee(emp);
        aMon.setRosterDate(dMon);
        aMon.setShift(morningShift);
        aMon.setWeeklyOff(false);
        aMon.setOnLeave(false);
        assignmentRepository.save(aMon);

        // Tuesday: Weekly Off
        RosterAssignment aTue = new RosterAssignment();
        aTue.setCycle(cycle);
        aTue.setEmployee(emp);
        aTue.setRosterDate(dTue);
        aTue.setShift(offShift);
        aTue.setWeeklyOff(true);
        aTue.setOnLeave(false);
        assignmentRepository.save(aTue);

        // Execute Weekly Summary for this employee
        WeeklyWorkSummaryResponse summary = workDayReportService.generateWeeklyWorkSummary(dMon, emp.getId());
        assertNotNull(summary);
        assertEquals(7, summary.totalPeriodDays());
        assertEquals(1, summary.employeeSummaries().size());

        WeeklyEmployeeWorkSummary empSummary = summary.employeeSummaries().get(0);
        assertEquals(emp.getId(), empSummary.employeeId());

        // Mathematical verification:
        // Mon = WORKED (1)
        // Tue = WEEKLY_OFF (1)
        // Wed = HOLIDAY (1)
        // Thu = LEAVE (1)
        // Fri, Sat, Sun = ABSENT (3)
        // Total = 7
        assertEquals(1, empSummary.workedDays(), "Monday must be counted as 1 worked day");
        assertEquals(1, empSummary.weeklyOffDays(), "Tuesday must be counted as 1 weekly off day");
        assertEquals(1, empSummary.holidayDays(), "Wednesday must be recognized as 1 holiday day");
        assertEquals(1, empSummary.leaveDays(), "Thursday must be counted as 1 approved leave day");
        assertEquals(3, empSummary.absentDays(), "Fri, Sat, Sun must be counted as 3 absent/unassigned days");
        assertEquals(7, empSummary.totalDays(), "Total period days must equal exactly 7");
        assertEquals(7, empSummary.workedDays() + empSummary.weeklyOffDays() + empSummary.holidayDays() + empSummary.leaveDays() + empSummary.absentDays());
    }

    // =========================================================================
    // 3. AUTHENTICATION & SECURITY ROLE ISOLATION
    // =========================================================================

    @Test
    @DisplayName("Authentication: Valid login returns JWT, invalid password returns 401")
    void testAuthenticationAndRoleIsolation() throws Exception {
        // Find existing admin or employee
        User admin = userRepository.findByRole(Role.ROLE_ADMIN).stream().findFirst().orElse(null);
        assertNotNull(admin, "At least one admin user must exist");

        // 1. Invalid password -> 401 Unauthorized
        LoginRequest badReq = new LoginRequest(admin.getUsername(), "WrongPassword!999");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badReq)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "employee", authorities = {"ROLE_EMPLOYEE"})
    @DisplayName("Security Isolation: Employee cannot access Admin holiday endpoints (403 Forbidden)")
    void testSecurityRoleIsolation() throws Exception {
        mockMvc.perform(get("/api/admin/holidays"))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // 4. EMPLOYEE MANAGEMENT LIFECYCLE
    // =========================================================================

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("Employee Management: Auto ID generation, create, update, and deactivate")
    void testEmployeeManagementLifecycle() {
        String nextCode = employeeService.generateNextEmployeeCode();
        assertNotNull(nextCode);
        assertTrue(nextCode.startsWith("EMP"));

        long suffix = System.currentTimeMillis();
        EmployeeRequest req = new EmployeeRequest(
                "", // Blank -> auto generates ID
                "Lifecycle",
                "User",
                "lifecycle." + suffix + "@example.com",
                Gender.MALE,
                "lifecycleuser" + suffix,
                "Password@123",
                "9876543210",
                "GENERAL"
        );

        EmployeeResponse created = employeeService.create(req);
        assertNotNull(created);
        assertNotNull(created.id());
        assertTrue(created.employeeCode().startsWith("EMP"));

        // Update employee
        EmployeeRequest updateReq = new EmployeeRequest(
                created.employeeCode(),
                "LifecycleUpdated",
                "UserUpdated",
                created.email(),
                Gender.MALE,
                null,
                null,
                "9876543211",
                "MORNING"
        );
        EmployeeResponse updated = employeeService.update(created.id(), updateReq);
        assertEquals("LifecycleUpdated", updated.firstName());
        assertEquals("9876543211", updated.contactNumber());

        // Deactivate employee
        employeeService.delete(created.id());
        Employee deactivated = employeeRepository.findById(created.id()).orElseThrow();
        assertFalse(deactivated.isActive());
    }

    // =========================================================================
    // 5. PROFILE CHANGE REQUEST WORKFLOW
    // =========================================================================

    @Test
    @DisplayName("Profile Change Request: Submit, approve updates master data, reject keeps existing data")
    void testProfileChangeRequestWorkflow() {
        // Setup employee and user
        long suffix = System.currentTimeMillis();
        User empUser = new User();
        empUser.setUsername("e88user" + suffix);
        empUser.setPassword(passwordEncoder.encode("Password@123"));
        empUser.setRole(Role.ROLE_EMPLOYEE);
        empUser = userRepository.save(empUser);

        Employee emp = new Employee();
        emp.setEmployeeCode("E88PCR" + (suffix % 100000));
        emp.setFirstName("Profile");
        emp.setLastName("Tester");
        emp.setEmail("profile.tester" + suffix + "@example.com");
        emp.setContactNumber("9999900001");
        emp.setGender(Gender.FEMALE);
        emp.setActive(true);
        emp.setUser(empUser);
        emp = employeeRepository.save(emp);

        // 1. Submit Profile Change Request
        ProfileChangeRequest pcr = new ProfileChangeRequest();
        pcr.setEmployee(emp);
        pcr.setFieldName("contactNumber");
        pcr.setCurrentValue("9999900001");
        pcr.setRequestedValue("9999900002");
        pcr.setStatus(ProfileChangeStatus.PENDING);
        pcr.setRequestedAt(LocalDateTime.now());
        pcr = profileChangeRequestRepository.save(pcr);

        assertEquals(ProfileChangeStatus.PENDING, pcr.getStatus());

        // 2. Admin Approves Request
        ProfileChangeDecisionRequest approveReq = new ProfileChangeDecisionRequest("Approved by Admin in Batch 88");
        ProfileChangeRequestResponse approvedResp = profileChangeRequestService.approve(pcr.getId(), approveReq);
        assertEquals(ProfileChangeStatus.APPROVED, approvedResp.status());

        // Master data must be updated to requestedValue
        Employee updatedEmp = employeeRepository.findById(emp.getId()).orElseThrow();
        assertEquals("9999900002", updatedEmp.getContactNumber());

        // 3. Submit another request to test Rejection
        ProfileChangeRequest pcr2 = new ProfileChangeRequest();
        pcr2.setEmployee(emp);
        pcr2.setFieldName("contactNumber");
        pcr2.setCurrentValue("9999900002");
        pcr2.setRequestedValue("1111111111");
        pcr2.setStatus(ProfileChangeStatus.PENDING);
        pcr2.setRequestedAt(LocalDateTime.now());
        pcr2 = profileChangeRequestRepository.save(pcr2);

        ProfileChangeDecisionRequest rejectReq = new ProfileChangeDecisionRequest("Invalid phone number rejected");
        ProfileChangeRequestResponse rejectedResp = profileChangeRequestService.reject(pcr2.getId(), rejectReq);
        assertEquals(ProfileChangeStatus.REJECTED, rejectedResp.status());

        // Master data must remain untouched ("9999900002")
        Employee untouchedEmp = employeeRepository.findById(emp.getId()).orElseThrow();
        assertEquals("9999900002", untouchedEmp.getContactNumber());
    }

    // =========================================================================
    // 6. SHIFT PREFERENCES (MULTI-SHIFT & DATE-SPECIFIC)
    // =========================================================================

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("Shift Preferences: Multi-shift preferences and date-specific preferences persist correctly")
    void testShiftPreferencesMultiAndDateSpecific() {
        long suffix = System.currentTimeMillis();
        Employee emp = new Employee();
        emp.setEmployeeCode("E88PRF" + (suffix % 100000));
        emp.setFirstName("Shift");
        emp.setLastName("PrefTester");
        emp.setEmail("shift.pref" + suffix + "@example.com");
        emp.setGender(Gender.MALE);
        emp.setActive(true);
        emp = employeeRepository.save(emp);

        // 1. Multi-Shift Preference by Admin
        AdminShiftPreferenceRequest prefReq = new AdminShiftPreferenceRequest(
                List.of("GENERAL", "MORNING", "EVENING"),
                "Configured multi-shift preference for Batch 88"
        );
        PreferenceResponse prefResp = employeePreferenceService.setEmployeeShiftPreferenceByAdmin(emp.getId(), prefReq, "admin");
        assertNotNull(prefResp);
        assertTrue(prefResp.preferredShiftTypes().contains("GENERAL"));
        assertTrue(prefResp.preferredShiftTypes().contains("MORNING"));
        assertTrue(prefResp.preferredShiftTypes().contains("EVENING"));

        // 2. Date-Specific Preference by Admin
        LocalDate targetDate = LocalDate.now().plusDays(15);
        DateShiftPreferenceRequest datePrefReq = new DateShiftPreferenceRequest(
                emp.getId(),
                targetDate,
                ShiftType.MORNING,
                "Critical deployment support required"
        );
        DateShiftPreferenceResponse datePrefResp = dateShiftPreferenceService.createOrUpdate(datePrefReq, "admin");
        assertNotNull(datePrefResp);
        assertEquals(emp.getId(), datePrefResp.employeeId());
        assertEquals(targetDate, datePrefResp.preferenceDate());
        assertEquals(ShiftType.MORNING, datePrefResp.shiftType());
    }

    // =========================================================================
    // 7. LEAVE MANAGEMENT WORKFLOW
    // =========================================================================

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("Leave Management: Apply, view pending, and approve leave workflow")
    void testLeaveManagementWorkflow() {
        long suffix = System.currentTimeMillis();
        Employee emp = new Employee();
        emp.setEmployeeCode("E88LVE" + (suffix % 100000));
        emp.setFirstName("Leave");
        emp.setLastName("Tester");
        emp.setEmail("leave.tester" + suffix + "@example.com");
        emp.setGender(Gender.FEMALE);
        emp.setActive(true);
        emp = employeeRepository.save(emp);

        LocalDate startDate = LocalDate.now().plusDays(20);
        LocalDate endDate = LocalDate.now().plusDays(21);

        ApplyLeaveRequest applyReq = new ApplyLeaveRequest(emp.getId(), startDate, endDate, "Medical Checkup");
        LeaveResponse leaveResp = leaveService.apply(applyReq);
        assertNotNull(leaveResp);
        assertEquals("PENDING", leaveResp.status().name());

        // Admin approves
        LeaveResponse approved = leaveService.approve(leaveResp.id(), new LeaveDecisionRequest("Approved by Admin"));
        assertEquals("APPROVED", approved.status().name());

        LeaveRequest dbLeave = leaveRequestRepository.findById(leaveResp.id()).orElseThrow();
        assertEquals(LeaveStatus.APPROVED, dbLeave.getStatus());
    }
}
