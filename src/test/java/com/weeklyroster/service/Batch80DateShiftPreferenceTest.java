package com.weeklyroster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.dto.request.AdminShiftPreferenceRequest;
import com.weeklyroster.dto.request.BulkDateShiftPreferenceRequest;
import com.weeklyroster.dto.request.DateShiftPreferenceRequest;
import com.weeklyroster.dto.response.DateShiftPreferenceResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
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
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"wrms.dev.credential-mirror.enabled=false"})
public class Batch80DateShiftPreferenceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private DateShiftPreferenceRepository dateShiftPreferenceRepository;

    @Autowired
    private DateShiftPreferenceService dateShiftPreferenceService;

    @Autowired
    private EmployeePreferenceService employeePreferenceService;

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterAssignmentRepository rosterAssignmentRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Admin sets date-specific preference: persists and returns 200 with correct fields")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testAdminSetsDateSpecificPreference() throws Exception {
        Employee emp = employeeRepository.findAll().stream().findFirst().orElseThrow();
        LocalDate targetDate = LocalDate.now().plusDays(10);

        DateShiftPreferenceRequest req = new DateShiftPreferenceRequest(
                emp.getId(),
                targetDate,
                ShiftType.MORNING,
                "Client morning shift mandate"
        );

        mockMvc.perform(post("/api/admin/preferences/date-specific")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId", is(emp.getId().intValue())))
                .andExpect(jsonPath("$.preferenceDate", is(targetDate.toString())))
                .andExpect(jsonPath("$.shiftType", is("MORNING")))
                .andExpect(jsonPath("$.reason", is("Client morning shift mandate")))
                .andExpect(jsonPath("$.createdBy", is("Admin")));

        Optional<DateShiftPreference> saved = dateShiftPreferenceRepository
                .findByEmployeeIdAndPreferenceDate(emp.getId(), targetDate);
        assertTrue(saved.isPresent(), "Date-specific preference must be saved in database");
        assertEquals(ShiftType.MORNING, saved.get().getShiftType());
    }

    @Test
    @DisplayName("Admin sets different date-specific shifts for different employees on same date")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testDifferentShiftsForDifferentEmployeesOnSameDate() throws Exception {
        List<Employee> employees = employeeRepository.findAll();
        assertTrue(employees.size() >= 2, "Need at least 2 employees in test dataset");
        Employee emp1 = employees.get(0);
        Employee emp2 = employees.get(1);

        LocalDate targetDate = LocalDate.now().plusDays(14);

        // Employee 1 -> MORNING
        dateShiftPreferenceService.createOrUpdate(
                new DateShiftPreferenceRequest(emp1.getId(), targetDate, ShiftType.MORNING, "Emp1 Morning"), "Admin");

        // Employee 2 -> EVENING
        dateShiftPreferenceService.createOrUpdate(
                new DateShiftPreferenceRequest(emp2.getId(), targetDate, ShiftType.EVENING, "Emp2 Evening"), "Admin");

        var pref1 = dateShiftPreferenceRepository.findByEmployeeIdAndPreferenceDate(emp1.getId(), targetDate);
        var pref2 = dateShiftPreferenceRepository.findByEmployeeIdAndPreferenceDate(emp2.getId(), targetDate);

        assertTrue(pref1.isPresent());
        assertTrue(pref2.isPresent());
        assertEquals(ShiftType.MORNING, pref1.get().getShiftType());
        assertEquals(ShiftType.EVENING, pref2.get().getShiftType());
    }

    @Test
    @DisplayName("Bulk date preference applies specified shift to multiple employees")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testBulkDateSpecificPreference() throws Exception {
        List<Employee> employees = employeeRepository.findAll();
        assertTrue(employees.size() >= 2);
        List<Long> empIds = List.of(employees.get(0).getId(), employees.get(1).getId());
        LocalDate targetDate = LocalDate.now().plusDays(7);

        BulkDateShiftPreferenceRequest req = new BulkDateShiftPreferenceRequest(
                empIds,
                targetDate,
                ShiftType.EVENING,
                "Team evening coverage"
        );

        mockMvc.perform(post("/api/admin/preferences/date-specific/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].shiftType", is("EVENING")))
                .andExpect(jsonPath("$[1].shiftType", is("EVENING")));

        for (Long id : empIds) {
            Optional<DateShiftPreference> saved = dateShiftPreferenceRepository
                    .findByEmployeeIdAndPreferenceDate(id, targetDate);
            assertTrue(saved.isPresent());
            assertEquals(ShiftType.EVENING, saved.get().getShiftType());
        }
    }

    @Test
    @DisplayName("Uniqueness and upsert: setting preference for same employee and date updates existing row")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testUniquenessAndUpsert() throws Exception {
        Employee emp = employeeRepository.findAll().stream().findFirst().orElseThrow();
        LocalDate targetDate = LocalDate.now().plusDays(5);

        // 1. Initial setting -> MORNING
        dateShiftPreferenceService.createOrUpdate(
                new DateShiftPreferenceRequest(emp.getId(), targetDate, ShiftType.MORNING, "Initial"), "Admin");

        long countBefore = dateShiftPreferenceRepository.findByEmployeeIdOrderByPreferenceDateDesc(emp.getId())
                .stream().filter(p -> p.getPreferenceDate().equals(targetDate)).count();
        assertEquals(1, countBefore);

        // 2. Update to GENERAL
        DateShiftPreferenceRequest updateReq = new DateShiftPreferenceRequest(
                emp.getId(), targetDate, ShiftType.GENERAL, "Updated to General"
        );

        mockMvc.perform(post("/api/admin/preferences/date-specific")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shiftType", is("GENERAL")))
                .andExpect(jsonPath("$.reason", is("Updated to General")));

        // 3. Confirm count is still 1 and shift is GENERAL
        long countAfter = dateShiftPreferenceRepository.findByEmployeeIdOrderByPreferenceDateDesc(emp.getId())
                .stream().filter(p -> p.getPreferenceDate().equals(targetDate)).count();
        assertEquals(1, countAfter, "Upsert must not create duplicate records for same employee + date");

        DateShiftPreference updated = dateShiftPreferenceRepository
                .findByEmployeeIdAndPreferenceDate(emp.getId(), targetDate).orElseThrow();
        assertEquals(ShiftType.GENERAL, updated.getShiftType());
    }

    @Test
    @DisplayName("Admin deletes date-specific preference")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testAdminDeletesDatePreference() throws Exception {
        Employee emp = employeeRepository.findAll().stream().findFirst().orElseThrow();
        LocalDate targetDate = LocalDate.now().plusDays(8);

        DateShiftPreferenceResponse created = dateShiftPreferenceService.createOrUpdate(
                new DateShiftPreferenceRequest(emp.getId(), targetDate, ShiftType.MORNING, "To delete"), "Admin");

        mockMvc.perform(delete("/api/admin/preferences/date-specific/" + created.id()))
                .andExpect(status().isNoContent());

        assertTrue(dateShiftPreferenceRepository.findById(created.id()).isEmpty(),
                "Preference record should be deleted");
    }

    @Test
    @DisplayName("Non-admin user cannot access admin date preference endpoints (403 Forbidden)")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void testNonAdminAccessForbidden() throws Exception {
        Employee emp = employeeRepository.findAll().stream().findFirst().orElseThrow();
        LocalDate targetDate = LocalDate.now().plusDays(6);

        DateShiftPreferenceRequest req = new DateShiftPreferenceRequest(
                emp.getId(), targetDate, ShiftType.MORNING, "Hack"
        );

        // GET
        mockMvc.perform(get("/api/admin/preferences/date-specific"))
                .andExpect(status().isForbidden());

        // POST
        mockMvc.perform(post("/api/admin/preferences/date-specific")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        // BULK POST
        BulkDateShiftPreferenceRequest bulkReq = new BulkDateShiftPreferenceRequest(
                List.of(emp.getId()), targetDate, ShiftType.MORNING, "Hack Bulk"
        );
        mockMvc.perform(post("/api/admin/preferences/date-specific/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bulkReq)))
                .andExpect(status().isForbidden());

        // DELETE
        mockMvc.perform(delete("/api/admin/preferences/date-specific/9999"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Automatic roster generation honors date-specific preference and avoids weekly off on that date")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testAutomaticRosterHonorsDateSpecificPreference() {
        Employee emp = employeeRepository.findAll().stream().findFirst().orElseThrow();

        // Target next Monday's cycle
        LocalDate nextMonday = LocalDate.now().plusWeeks(2).with(DayOfWeek.MONDAY);
        LocalDate targetWednesday = nextMonday.plusDays(2); // Wednesday

        // Set date-specific preference: Wednesday -> MORNING
        dateShiftPreferenceService.createOrUpdate(
                new DateShiftPreferenceRequest(emp.getId(), targetWednesday, ShiftType.MORNING, "Wed Morning Pref"), "Admin");

        // Generate weekly roster
        var cycleResponse = rosterService.generateWeeklyRoster(nextMonday, GenerationMode.MANUAL);
        assertNotNull(cycleResponse);

        // Verify assignment for Wednesday
        var assignments = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), targetWednesday);
        assertFalse(assignments.isEmpty(), "Assignment must exist for employee on Wednesday");
        RosterAssignment assignment = assignments.get(0);

        // It should match MORNING
        assertEquals(ShiftType.MORNING, assignment.getShift().getShiftType(),
                "Automatic roster generation must assign preferred MORNING shift on date-specific preference date");

        // Also verify Wednesday was NOT assigned as weekly off
        assertFalse(assignment.isWeeklyOff());
        assertNotEquals(ShiftType.OFF, assignment.getShift().getShiftType());
    }

    @Test
    @DisplayName("General and date-specific preferences coexist harmoniously")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testGeneralAndDateSpecificPreferencesCoexist() {
        Employee emp = employeeRepository.findAll().stream().findFirst().orElseThrow();

        LocalDate nextMonday = LocalDate.now().plusWeeks(3).with(DayOfWeek.MONDAY);
        LocalDate targetFriday = nextMonday.plusDays(4); // Friday

        // 1. General preference: 1. EVENING, 2. NIGHT
        employeePreferenceService.setEmployeeShiftPreferenceByAdmin(emp.getId(),
                new AdminShiftPreferenceRequest(List.of("EVENING", "NIGHT"), "General prefs"), "Admin");

        // 2. Date-specific preference for Friday: MORNING
        dateShiftPreferenceService.createOrUpdate(
                new DateShiftPreferenceRequest(emp.getId(), targetFriday, ShiftType.MORNING, "Friday Special Morning"), "Admin");

        // 3. Verify loadApprovedPreferences
        var prefsMap = rosterService.loadApprovedPreferences(List.of(emp), nextMonday, nextMonday.plusDays(6));
        assertTrue(prefsMap.containsKey(emp.getId()));

        var applicablePref = prefsMap.get(emp.getId());
        // General priorities intact
        assertEquals(0, applicablePref.getShiftPreferenceRank(ShiftType.EVENING));
        assertEquals(1, applicablePref.getShiftPreferenceRank(ShiftType.NIGHT));

        // Date specific present for Friday
        assertEquals(ShiftType.MORNING, applicablePref.getDateSpecificShift(targetFriday));
        assertNull(applicablePref.getDateSpecificShift(nextMonday)); // No date-specific pref on Monday
    }

    @Test
    @DisplayName("Hard constraints (e.g. Leave) override date-specific preference safely without error")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testHardConstraintsOverrideDateSpecificPreference() {
        Employee emp = employeeRepository.findAll().stream().findFirst().orElseThrow();

        LocalDate nextMonday = LocalDate.now().plusWeeks(4).with(DayOfWeek.MONDAY);
        LocalDate targetThursday = nextMonday.plusDays(3);

        // 1. Create an approved leave request on Thursday
        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(emp);
        leave.setStartDate(targetThursday);
        leave.setEndDate(targetThursday);
        leave.setReason("Medical Leave");
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setRequestedAt(java.time.LocalDateTime.now());
        leave.setReviewedAt(java.time.LocalDateTime.now());
        leaveRequestRepository.save(leave);

        // 2. Set date-specific preference for Thursday: MORNING
        dateShiftPreferenceService.createOrUpdate(
                new DateShiftPreferenceRequest(emp.getId(), targetThursday, ShiftType.MORNING, "Wants to work Thursday"), "Admin");

        // 3. Generate roster - should NOT crash and should respect Leave (OFF)
        var cycleResponse = rosterService.generateWeeklyRoster(nextMonday, GenerationMode.MANUAL);
        assertNotNull(cycleResponse);

        var assignments = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), targetThursday);
        assertFalse(assignments.isEmpty());
        RosterAssignment assignment = assignments.get(0);
        assertTrue(assignment.isOnLeave() || assignment.isWeeklyOff() || assignment.getShift().getShiftType() == ShiftType.OFF,
                "Approved Leave hard constraint must override date-specific shift preference");
    }
}
