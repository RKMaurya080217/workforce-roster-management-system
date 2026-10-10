package com.weeklyroster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.dto.request.AdminShiftPreferenceRequest;
import com.weeklyroster.dto.request.PreferenceSubmitRequest;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.EmployeePreferenceRepository;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.UserRepository;
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
public class Batch74ShiftPreferenceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeePreferenceRepository preferenceRepository;

    @Autowired
    private EmployeePreferenceService preferenceService;

    @Autowired
    private RosterService rosterService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Admin sets Morning preference: succeeds, persists as APPROVED, and appears in employee list")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testAdminSetsMorningPreference() throws Exception {
        Employee emp = employeeRepository.findAll().stream().findFirst().orElseThrow();

        AdminShiftPreferenceRequest req = new AdminShiftPreferenceRequest("MORNING", "Set by Admin test");

        mockMvc.perform(put("/api/admin/preferences/employee/" + emp.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredShiftTypes", is("MORNING")))
                .andExpect(jsonPath("$.status", is("APPROVED")));

        // Verify entity in repository
        List<EmployeePreference> prefs = preferenceRepository.findByEmployeeIdOrderByCreatedAtDesc(emp.getId());
        assertFalse(prefs.isEmpty());
        assertEquals("MORNING", prefs.get(0).getPreferredShiftTypes());
        assertEquals(PreferenceStatus.APPROVED, prefs.get(0).getStatus());

        // Verify reflected in /api/employees list
        mockMvc.perform(get("/api/employees"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + emp.getId() + ")].shiftPreference", hasItem("MORNING")));
    }

    @Test
    @DisplayName("Admin updates preference Morning -> General: updates existing record without duplicate")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testAdminUpdatesPreferenceWithoutDuplicate() throws Exception {
        Employee emp = employeeRepository.findAll().stream().findFirst().orElseThrow();

        // 1. Set MORNING
        preferenceService.setEmployeeShiftPreferenceByAdmin(emp.getId(),
                new AdminShiftPreferenceRequest("MORNING", "Morning shift"), "Admin");

        List<EmployeePreference> initialPrefs = preferenceRepository.findByEmployeeIdOrderByCreatedAtDesc(emp.getId());
        assertEquals(1, initialPrefs.size());
        assertEquals("MORNING", initialPrefs.get(0).getPreferredShiftTypes());

        // 2. Update to GENERAL via API
        AdminShiftPreferenceRequest req2 = new AdminShiftPreferenceRequest("GENERAL", "General shift");
        mockMvc.perform(put("/api/admin/preferences/employee/" + emp.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredShiftTypes", is("GENERAL")))
                .andExpect(jsonPath("$.status", is("APPROVED")));

        // 3. Verify exactly one record exists (no duplicate)
        List<EmployeePreference> updatedPrefs = preferenceRepository.findByEmployeeIdOrderByCreatedAtDesc(emp.getId());
        assertEquals(1, updatedPrefs.size(), "Duplicate preference records must not be created for the same employee");
        assertEquals("GENERAL", updatedPrefs.get(0).getPreferredShiftTypes());
        assertEquals(PreferenceStatus.APPROVED, updatedPrefs.get(0).getStatus());
    }

    @Test
    @DisplayName("Admin clears preference / sets Not Set: resets to null")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testAdminClearsPreference() throws Exception {
        Employee emp = employeeRepository.findAll().stream().findFirst().orElseThrow();

        // Set MORNING first
        preferenceService.setEmployeeShiftPreferenceByAdmin(emp.getId(),
                new AdminShiftPreferenceRequest("MORNING", "Morning shift"), "Admin");

        // Now set to NOT_SET
        AdminShiftPreferenceRequest req = new AdminShiftPreferenceRequest("NOT_SET", "Cleared");
        mockMvc.perform(put("/api/admin/preferences/employee/" + emp.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredShiftTypes").doesNotExist());

        // Verify cleared in service
        String pref = preferenceService.getEmployeeShiftPreference(emp.getId());
        assertNull(pref);
    }

    @Test
    @DisplayName("Admin validates shift names: invalid shift name returns 400 Bad Request")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testAdminInvalidShiftValidation() throws Exception {
        Employee emp = employeeRepository.findAll().stream().findFirst().orElseThrow();

        AdminShiftPreferenceRequest req = new AdminShiftPreferenceRequest("INVALID_SHIFT_NAME", "Test invalid");
        mockMvc.perform(put("/api/admin/preferences/employee/" + emp.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Employee can view own shift preference read-only")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void testEmployeeCanViewShiftPreference() throws Exception {
        Employee emp = employeeRepository.findByEmployeeCodeIgnoreCase("EMP001").orElseThrow();

        // Set preference by Admin first
        preferenceService.setEmployeeShiftPreferenceByAdmin(emp.getId(),
                new AdminShiftPreferenceRequest("NIGHT", "Night roster"), "Admin");

        // Employee GET /api/preferences/my returns 200 OK
        mockMvc.perform(get("/api/preferences/my"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", not(empty())))
                .andExpect(jsonPath("$[0].preferredShiftTypes", is("NIGHT")));

        // Employee GET /api/preferences/my/active returns 200 OK
        mockMvc.perform(get("/api/preferences/my/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredShiftTypes", is("NIGHT")));
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

        // 2. Direct PUT /api/preferences/{id} must be rejected with 403
        mockMvc.perform(put("/api/preferences/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        // 3. Direct DELETE /api/preferences/{id} must be rejected with 403
        mockMvc.perform(delete("/api/preferences/1"))
                .andExpect(status().isForbidden());

        // 4. Direct PUT /api/admin/preferences/employee/{id} must be rejected with 403
        AdminShiftPreferenceRequest adminReq = new AdminShiftPreferenceRequest("MORNING", "Hack");
        mockMvc.perform(put("/api/admin/preferences/employee/" + emp.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminReq)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Roster generation consumes Admin-configured preference while maintaining constraints")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testRosterGenerationConsumesAdminPreference() {
        Employee emp = employeeRepository.findByEmployeeCodeIgnoreCase("EMP001").orElseThrow();

        // Admin configures MORNING preference
        preferenceService.setEmployeeShiftPreferenceByAdmin(emp.getId(),
                new AdminShiftPreferenceRequest("MORNING", "Admin Morning Preference"), "Admin");

        java.time.LocalDate monday = java.time.LocalDate.now().with(java.time.DayOfWeek.MONDAY);
        var cycleResponse = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL);
        assertNotNull(cycleResponse);
        assertFalse(cycleResponse.assignments().isEmpty());

        // Verify assignments for emp
        var empAssignments = cycleResponse.assignments().stream()
                .filter(a -> a.employeeId().equals(emp.getId()))
                .toList();

        assertEquals(7, empAssignments.size());

        // Verify that preferences map in RosterService successfully read the Admin preference
        var prefsMap = rosterService.loadApprovedPreferences(List.of(emp), monday, monday.plusDays(6));
        assertTrue(prefsMap.containsKey(emp.getId()));
        assertTrue(prefsMap.get(emp.getId()).preferredShifts().contains(ShiftType.MORNING));
    }
}
