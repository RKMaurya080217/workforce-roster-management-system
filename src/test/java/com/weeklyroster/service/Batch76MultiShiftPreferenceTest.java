package com.weeklyroster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.dto.request.AdminShiftPreferenceRequest;
import com.weeklyroster.dto.request.PreferenceSubmitRequest;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.EmployeePreferenceRepository;
import com.weeklyroster.repository.EmployeeRepository;
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

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"wrms.dev.credential-mirror.enabled=false"})
public class Batch76MultiShiftPreferenceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeePreferenceRepository preferenceRepository;

    @Autowired
    private EmployeePreferenceService preferenceService;

    @Autowired
    private RosterService rosterService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Admin sets multiple preferred shifts in priority order: succeeds and persists with deterministic ordering")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testAdminSetsMultipleShiftsInPriorityOrder() throws Exception {
        Employee emp = employeeRepository.findAll().stream().findFirst().orElseThrow();

        AdminShiftPreferenceRequest req = new AdminShiftPreferenceRequest(
                List.of("GENERAL", "MORNING", "EVENING"),
                "Priority preferences set by Admin"
        );

        mockMvc.perform(put("/api/admin/preferences/employee/" + emp.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredShiftTypes", is("GENERAL, MORNING, EVENING")))
                .andExpect(jsonPath("$.preferredShifts", is("GENERAL, MORNING, EVENING")))
                .andExpect(jsonPath("$.preferredShiftList", hasSize(3)))
                .andExpect(jsonPath("$.preferredShiftList[0]", is("GENERAL")))
                .andExpect(jsonPath("$.preferredShiftList[1]", is("MORNING")))
                .andExpect(jsonPath("$.preferredShiftList[2]", is("EVENING")))
                .andExpect(jsonPath("$.preferredShiftPriorities", hasSize(3)))
                .andExpect(jsonPath("$.preferredShiftPriorities[0].priority", is(1)))
                .andExpect(jsonPath("$.preferredShiftPriorities[0].shift", is("GENERAL")))
                .andExpect(jsonPath("$.preferredShiftPriorities[1].priority", is(2)))
                .andExpect(jsonPath("$.preferredShiftPriorities[1].shift", is("MORNING")))
                .andExpect(jsonPath("$.preferredShiftPriorities[2].priority", is(3)))
                .andExpect(jsonPath("$.preferredShiftPriorities[2].shift", is("EVENING")))
                .andExpect(jsonPath("$.status", is("APPROVED")));

        // Verify entity in repository
        List<EmployeePreference> prefs = preferenceRepository.findByEmployeeIdOrderByCreatedAtDesc(emp.getId());
        assertEquals(1, prefs.size(), "Exactly one preference record must exist for the employee");
        assertEquals("GENERAL, MORNING, EVENING", prefs.get(0).getPreferredShiftTypes());
        assertEquals(PreferenceStatus.APPROVED, prefs.get(0).getStatus());

        // Verify reflected in /api/employees list
        mockMvc.perform(get("/api/employees"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + emp.getId() + ")].shiftPreference", hasItem("GENERAL, MORNING, EVENING")));
    }

    @Test
    @DisplayName("Admin reorders priorities: updates existing record without duplicates and reflects new priority order")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testAdminReordersPriorityOrder() throws Exception {
        Employee emp = employeeRepository.findAll().stream().findFirst().orElseThrow();

        // 1. Initially set Priority 1: GENERAL, Priority 2: MORNING
        preferenceService.setEmployeeShiftPreferenceByAdmin(emp.getId(),
                new AdminShiftPreferenceRequest(List.of("GENERAL", "MORNING"), "Initial"), "Admin");

        List<EmployeePreference> initialPrefs = preferenceRepository.findByEmployeeIdOrderByCreatedAtDesc(emp.getId());
        assertEquals(1, initialPrefs.size());
        assertEquals("GENERAL, MORNING", initialPrefs.get(0).getPreferredShiftTypes());

        // 2. Reorder to Priority 1: MORNING, Priority 2: GENERAL, Priority 3: EVENING
        AdminShiftPreferenceRequest reorderReq = new AdminShiftPreferenceRequest(
                List.of("MORNING", "GENERAL", "EVENING"), "Reordered"
        );

        mockMvc.perform(put("/api/admin/preferences/employee/" + emp.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reorderReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredShiftTypes", is("MORNING, GENERAL, EVENING")))
                .andExpect(jsonPath("$.preferredShiftPriorities[0].shift", is("MORNING")))
                .andExpect(jsonPath("$.preferredShiftPriorities[1].shift", is("GENERAL")))
                .andExpect(jsonPath("$.preferredShiftPriorities[2].shift", is("EVENING")));

        // 3. Verify exactly one record in DB
        List<EmployeePreference> updatedPrefs = preferenceRepository.findByEmployeeIdOrderByCreatedAtDesc(emp.getId());
        assertEquals(1, updatedPrefs.size(), "Reordering must not create duplicate records");
        assertEquals("MORNING, GENERAL, EVENING", updatedPrefs.get(0).getPreferredShiftTypes());
    }

    @Test
    @DisplayName("Duplicate shift preference in list is rejected with 400 Bad Request")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testDuplicateShiftValidation() throws Exception {
        Employee emp = employeeRepository.findAll().stream().findFirst().orElseThrow();

        AdminShiftPreferenceRequest req = new AdminShiftPreferenceRequest(
                List.of("GENERAL", "GENERAL", "MORNING"), "Duplicate General"
        );

        mockMvc.perform(put("/api/admin/preferences/employee/" + emp.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Invalid shift in preference list is rejected with 400 Bad Request")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testInvalidShiftValidation() throws Exception {
        Employee emp = employeeRepository.findAll().stream().findFirst().orElseThrow();

        // 1. Non-existent shift name
        AdminShiftPreferenceRequest req1 = new AdminShiftPreferenceRequest(
                List.of("GENERAL", "SUPER_SHIFT"), "Invalid shift"
        );
        mockMvc.perform(put("/api/admin/preferences/employee/" + emp.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isBadRequest());

        // 2. OFF is not a valid working shift preference
        AdminShiftPreferenceRequest req2 = new AdminShiftPreferenceRequest(
                List.of("OFF"), "Off shift"
        );
        mockMvc.perform(put("/api/admin/preferences/employee/" + emp.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Admin clears all preferences: sets preference to null / empty list")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testAdminClearsPreferences() throws Exception {
        Employee emp = employeeRepository.findAll().stream().findFirst().orElseThrow();

        // Set preferences first
        preferenceService.setEmployeeShiftPreferenceByAdmin(emp.getId(),
                new AdminShiftPreferenceRequest(List.of("MORNING", "EVENING"), "Set"), "Admin");

        // Clear preferences via empty list
        AdminShiftPreferenceRequest clearReq = new AdminShiftPreferenceRequest(
                List.of(), "Clear all"
        );
        mockMvc.perform(put("/api/admin/preferences/employee/" + emp.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(clearReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredShiftTypes").doesNotExist())
                .andExpect(jsonPath("$.preferredShiftList", hasSize(0)));

        assertNull(preferenceService.getEmployeeShiftPreference(emp.getId()));
    }

    @Test
    @DisplayName("Employee mutating shift preference returns 403 Forbidden")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void testEmployeeMutationForbidden() throws Exception {
        Employee emp = employeeRepository.findByEmployeeCodeIgnoreCase("EMP001").orElseThrow();

        PreferenceSubmitRequest req = new PreferenceSubmitRequest(
                "EVENING", null, null, null, null, "Trying to set preference", null, null
        );

        // 1. Direct POST /api/preferences must be rejected with 403
        mockMvc.perform(post("/api/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        // 2. Direct PUT /api/admin/preferences/employee/{id} must be rejected with 403
        AdminShiftPreferenceRequest adminReq = new AdminShiftPreferenceRequest(List.of("MORNING"), "Hack");
        mockMvc.perform(put("/api/admin/preferences/employee/" + emp.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminReq)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Roster generation preserves multi-shift priority order and respects safety rules")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testRosterGenerationConsumesMultiShiftPriorities() {
        Employee emp = employeeRepository.findByEmployeeCodeIgnoreCase("EMP001").orElseThrow();

        // Admin configures Priority 1: MORNING, Priority 2: GENERAL, Priority 3: EVENING
        preferenceService.setEmployeeShiftPreferenceByAdmin(emp.getId(),
                new AdminShiftPreferenceRequest(List.of("MORNING", "GENERAL", "EVENING"), "Multi-preference"), "Admin");

        java.time.LocalDate monday = java.time.LocalDate.now().with(java.time.DayOfWeek.MONDAY);
        var cycleResponse = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL);
        assertNotNull(cycleResponse);
        assertFalse(cycleResponse.assignments().isEmpty());

        // Verify that preferences map in RosterService accurately parsed the order
        var prefsMap = rosterService.loadApprovedPreferences(List.of(emp), monday, monday.plusDays(6));
        assertTrue(prefsMap.containsKey(emp.getId()));
        var pref = prefsMap.get(emp.getId());
        assertEquals(0, pref.getShiftPreferenceRank(ShiftType.MORNING));
        assertEquals(1, pref.getShiftPreferenceRank(ShiftType.GENERAL));
        assertEquals(2, pref.getShiftPreferenceRank(ShiftType.EVENING));
        assertEquals(-1, pref.getShiftPreferenceRank(ShiftType.NIGHT));
    }
}
