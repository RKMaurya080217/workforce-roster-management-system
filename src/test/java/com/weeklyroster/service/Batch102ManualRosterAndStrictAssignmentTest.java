package com.weeklyroster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.dto.request.BulkManualAssignmentRequest;
import com.weeklyroster.dto.request.CopyPreviousWeekRequest;
import com.weeklyroster.dto.request.DateShiftPreferenceRequest;
import com.weeklyroster.dto.request.ManualAssignmentRequest;
import com.weeklyroster.dto.request.RecurringShiftPreferenceRequest;
import com.weeklyroster.dto.response.ManualRosterMatrixResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.GenerationMode;
import com.weeklyroster.entity.RosterAssignment;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.LeaveRequestRepository;
import com.weeklyroster.repository.RecurringShiftPreferenceRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.ShiftRepository;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"wrms.dev.credential-mirror.enabled=false"})
public class Batch102ManualRosterAndStrictAssignmentTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RecurringShiftPreferenceService recurringShiftPreferenceService;

    @Autowired
    private RecurringShiftPreferenceRepository recurringShiftPreferenceRepository;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private LeaveRequestRepository leaveRepository;

    private Employee testEmployee;
    private LocalDate targetMonday;

    @BeforeEach
    public void setup() {
        List<Employee> employees = employeeRepository.findByActiveTrueOrderByIdAsc();
        assertTrue(employees.size() >= 2, "Requires active employees for testing");
        testEmployee = employees.get(0);

        // Monday of next week
        targetMonday = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }

    @Test
    @DisplayName("BATCH 102 - TEST 1: Strict Employee Shift Assignment: Explicit Manual Assignment (Level 1) MUST be honored")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    public void testExplicitShiftAssignmentEnforced() {
        LocalDate saturday = targetMonday.plusDays(5); // Saturday
        ShiftType assignedShift = ShiftType.MORNING;

        // Save explicit manual assignment for test employee on Saturday
        ManualAssignmentRequest req = new ManualAssignmentRequest(
                null,
                targetMonday,
                testEmployee.getId(),
                saturday,
                assignedShift,
                false,
                "Admin explicit directive"
        );
        ManualRosterMatrixResponse matrix = rosterService.saveManualAssignment(req, "admin");
        assertNotNull(matrix);

        // Verify assignment saved in database
        List<RosterAssignment> assignments = assignmentRepository.findByEmployeeIdAndRosterDate(testEmployee.getId(), saturday);
        assertFalse(assignments.isEmpty());
        RosterAssignment a = assignments.get(0);
        assertEquals(assignedShift, a.getShift().getShiftType());
        assertTrue(a.isManualAssignment(), "Must be marked as manual assignment");
        assertTrue(a.isOverridden(), "Must be marked as overridden");
        assertEquals("MANUAL", a.getAssignmentSource());
    }

    @Test
    @DisplayName("BATCH 102 - TEST 2: Regeneration MUST NOT overwrite explicit manual shift assignment")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    public void testRegenerationDoesNotOverrideManualAssignment() {
        LocalDate saturday = targetMonday.plusDays(5);
        ShiftType assignedShift = ShiftType.MORNING;

        // 1. Explicitly assign Morning to test employee on Saturday
        ManualAssignmentRequest req = new ManualAssignmentRequest(
                null,
                targetMonday,
                testEmployee.getId(),
                saturday,
                assignedShift,
                false,
                "Strict Admin Directive"
        );
        rosterService.saveManualAssignment(req, "admin");

        // 2. Trigger roster generation (even multiple times with different seeds)
        RosterCycleResponse genResponse = rosterService.generateWeeklyRoster(targetMonday, GenerationMode.MANUAL);
        assertNotNull(genResponse);

        // 3. Verify that test employee on Saturday is STILL Morning
        List<RosterAssignment> afterGen = assignmentRepository.findByEmployeeIdAndRosterDate(testEmployee.getId(), saturday);
        assertFalse(afterGen.isEmpty());
        RosterAssignment finalAssignment = afterGen.get(0);
        assertEquals(assignedShift, finalAssignment.getShift().getShiftType(),
                "Regeneration MUST NOT replace manual assignment with another shift!");
        assertTrue(finalAssignment.isManualAssignment());
    }

    @Test
    @DisplayName("BATCH 102 - TEST 3: Priority Hierarchy: Level 1 Manual Assignment overrides Level 2 Date Preference and Level 3 Recurring Preference")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    public void testPriorityHierarchyManualOverDateAndRecurring() {
        LocalDate saturday = targetMonday.plusDays(5);

        // 1. Create Level 3 Recurring Preference: Saturday -> GENERAL
        recurringShiftPreferenceService.create(new RecurringShiftPreferenceRequest(
                testEmployee.getId(),
                DayOfWeek.SATURDAY,
                ShiftType.GENERAL,
                targetMonday.minusWeeks(2),
                null,
                true,
                "Recurring General"
        ), "admin");

        // 2. Create Level 1 Explicit Manual Assignment: Saturday -> MORNING
        rosterService.saveManualAssignment(new ManualAssignmentRequest(
                null,
                targetMonday,
                testEmployee.getId(),
                saturday,
                ShiftType.MORNING,
                false,
                "Explicit manual rule"
        ), "admin");

        // 3. Generate roster
        rosterService.generateWeeklyRoster(targetMonday, GenerationMode.MANUAL);

        // 4. Assert Level 1 Manual (MORNING) wins over Level 3 Recurring (GENERAL)
        List<RosterAssignment> list = assignmentRepository.findByEmployeeIdAndRosterDate(testEmployee.getId(), saturday);
        assertFalse(list.isEmpty());
        assertEquals(ShiftType.MORNING, list.get(0).getShift().getShiftType());
        assertTrue(list.get(0).isManualAssignment());
    }

    @Test
    @DisplayName("BATCH 102 - TEST 4: Recurring Weekly Preference CRUD and Overlap Conflict Prevention")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    public void testRecurringShiftPreferenceCRUDAndOverlapConflict() throws Exception {
        // 1. Create Recurring Preference via REST
        RecurringShiftPreferenceRequest createReq = new RecurringShiftPreferenceRequest(
                testEmployee.getId(),
                DayOfWeek.SATURDAY,
                ShiftType.MORNING,
                targetMonday,
                null,
                true,
                "Weekend study schedule"
        );

        String responseStr = mockMvc.perform(post("/api/admin/preferences/recurring")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.dayOfWeek").value("SATURDAY"))
                .andExpect(jsonPath("$.shiftType").value("MORNING"))
                .andReturn().getResponse().getContentAsString();

        Long createdId = objectMapper.readTree(responseStr).get("id").asLong();

        // 2. Attempt to create conflicting active rule on same DayOfWeek for same employee
        RecurringShiftPreferenceRequest conflictReq = new RecurringShiftPreferenceRequest(
                testEmployee.getId(),
                DayOfWeek.SATURDAY,
                ShiftType.EVENING,
                targetMonday,
                null,
                true,
                "Conflicting rule"
        );

        mockMvc.perform(post("/api/admin/preferences/recurring")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conflictReq)))
                .andExpect(status().is4xxClientError()); // BusinessException conflict

        // 3. Toggle Active Status
        mockMvc.perform(put("/api/admin/preferences/recurring/" + createdId + "/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        // 4. Delete Rule
        mockMvc.perform(delete("/api/admin/preferences/recurring/" + createdId))
                .andExpect(status().isNoContent());

        assertFalse(recurringShiftPreferenceRepository.findById(createdId).isPresent());
    }

    @Test
    @DisplayName("BATCH 102 - TEST 5: Manual Roster Matrix API returns 7-day grid and employee rows")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    public void testManualRosterMatrixApi() throws Exception {
        mockMvc.perform(get("/api/rosters/manual/matrix")
                        .param("startDate", targetMonday.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dates").isArray())
                .andExpect(jsonPath("$.dates", hasSize(7)))
                .andExpect(jsonPath("$.rows").isArray())
                .andExpect(jsonPath("$.dailyShiftCounts").isMap())
                .andExpect(jsonPath("$.status").exists());
    }

    @Test
    @DisplayName("BATCH 102 - TEST 6: Single-Cell Manual Assignment API updates cell accurately")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    public void testSingleCellManualAssignmentApi() throws Exception {
        LocalDate tuesday = targetMonday.plusDays(1);
        ManualAssignmentRequest req = new ManualAssignmentRequest(
                null,
                targetMonday,
                testEmployee.getId(),
                tuesday,
                ShiftType.EVENING,
                false,
                "Special evening task"
        );

        mockMvc.perform(post("/api/rosters/manual/assignment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows").isArray());

        List<RosterAssignment> list = assignmentRepository.findByEmployeeIdAndRosterDate(testEmployee.getId(), tuesday);
        assertFalse(list.isEmpty());
        assertEquals(ShiftType.EVENING, list.get(0).getShift().getShiftType());
        assertTrue(list.get(0).isManualAssignment());
    }

    @Test
    @DisplayName("BATCH 102 - TEST 7: Bulk Manual Assignment API updates multiple employees across multiple dates")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    public void testBulkManualAssignmentApi() throws Exception {
        List<Employee> all = employeeRepository.findByActiveTrueOrderByIdAsc();
        Employee emp1 = all.get(0);
        Employee emp2 = all.get(1);

        List<LocalDate> testDates = List.of(targetMonday, targetMonday.plusDays(1));

        BulkManualAssignmentRequest req = new BulkManualAssignmentRequest(
                null,
                targetMonday,
                List.of(emp1.getId(), emp2.getId()),
                testDates,
                ShiftType.GENERAL,
                false,
                "Sprint Alignment"
        );

        mockMvc.perform(post("/api/rosters/manual/bulk-assignment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        for (LocalDate d : testDates) {
            assertEquals(ShiftType.GENERAL, assignmentRepository.findByEmployeeIdAndRosterDate(emp1.getId(), d).get(0).getShift().getShiftType());
            assertEquals(ShiftType.GENERAL, assignmentRepository.findByEmployeeIdAndRosterDate(emp2.getId(), d).get(0).getShift().getShiftType());
        }
    }

    @Test
    @DisplayName("BATCH 102 - TEST 8: Clear Manual Assignment resets cell to automatic")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    public void testClearManualAssignmentApi() throws Exception {
        LocalDate wednesday = targetMonday.plusDays(2);
        rosterService.saveManualAssignment(new ManualAssignmentRequest(
                null,
                targetMonday,
                testEmployee.getId(),
                wednesday,
                ShiftType.NIGHT,
                false,
                "Night test"
        ), "admin");

        mockMvc.perform(delete("/api/rosters/manual/assignment")
                        .param("employeeId", testEmployee.getId().toString())
                        .param("date", wednesday.toString()))
                .andExpect(status().isOk());

        RosterAssignment a = assignmentRepository.findByEmployeeIdAndRosterDate(testEmployee.getId(), wednesday).get(0);
        assertFalse(a.isManualAssignment());
    }

    @Test
    @DisplayName("BATCH 102 - TEST 9: Copy Previous Week duplicates shift pattern with COPIED source")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    public void testCopyPreviousWeek() throws Exception {
        LocalDate prevMonday = targetMonday.minusWeeks(1);
        rosterService.generateWeeklyRoster(prevMonday, GenerationMode.MANUAL);

        CopyPreviousWeekRequest req = new CopyPreviousWeekRequest(null, targetMonday);

        mockMvc.perform(post("/api/rosters/manual/copy-week")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        List<RosterAssignment> copied = assignmentRepository.findByEmployeeIdAndRosterDate(testEmployee.getId(), targetMonday);
        assertFalse(copied.isEmpty());
        assertTrue(copied.get(0).isManualAssignment());
        assertEquals("COPIED", copied.get(0).getAssignmentSource());
    }

    @Test
    @DisplayName("BATCH 102 - TEST 10: Fill Unassigned Shifts strictly retains existing manual locks")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    public void testFillUnassignedPreservesManualAssignments() {
        LocalDate thursday = targetMonday.plusDays(3);
        rosterService.saveManualAssignment(new ManualAssignmentRequest(
                null,
                targetMonday,
                testEmployee.getId(),
                thursday,
                ShiftType.MORNING,
                false,
                "Strict lock"
        ), "admin");

        ManualRosterMatrixResponse response = rosterService.generateUnassignedOnly(null, targetMonday, "admin");
        assertNotNull(response);

        RosterAssignment a = assignmentRepository.findByEmployeeIdAndRosterDate(testEmployee.getId(), thursday).get(0);
        assertEquals(ShiftType.MORNING, a.getShift().getShiftType(), "Manual lock must be strictly preserved by generateUnassignedOnly");
        assertTrue(a.isManualAssignment());
    }
}
