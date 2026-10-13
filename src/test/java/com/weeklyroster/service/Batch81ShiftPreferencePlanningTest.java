package com.weeklyroster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.dto.request.DateShiftPreferenceRequest;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.DateShiftPreferenceRepository;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.LeaveRequestRepository;
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
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"wrms.dev.credential-mirror.enabled=false"})
public class Batch81ShiftPreferencePlanningTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private DateShiftPreferenceRepository dateShiftPreferenceRepository;

    @Autowired
    private DateShiftPreferenceService dateShiftPreferenceService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Admin previews valid date-specific preference: returns VALID with canApply=true")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testPreviewValidPreference() throws Exception {
        Employee emp = employeeRepository.findAll().stream()
                .filter(e -> e.getGender() == Gender.MALE)
                .findFirst().orElseThrow();
        LocalDate futureDate = LocalDate.now().plusDays(20);

        mockMvc.perform(get("/api/admin/preferences/date-specific/preview")
                        .param("employeeId", String.valueOf(emp.getId()))
                        .param("date", futureDate.toString())
                        .param("shiftType", "MORNING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("VALID")))
                .andExpect(jsonPath("$.canApply", is(true)))
                .andExpect(jsonPath("$.expectedFinalShift", is("MORNING")))
                .andExpect(jsonPath("$.reason", containsString("Preference can be applied cleanly")));
    }

    @Test
    @DisplayName("Admin previews preference with approved leave conflict: returns CONFLICT with canApply=false")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testPreviewLeaveConflict() throws Exception {
        Employee emp = employeeRepository.findAll().stream().findFirst().orElseThrow();
        LocalDate leaveDate = LocalDate.now().plusDays(25);

        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(emp);
        leave.setStartDate(leaveDate);
        leave.setEndDate(leaveDate);
        leave.setReason("Annual Vacation");
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setRequestedAt(LocalDateTime.now());
        leave.setReviewedAt(LocalDateTime.now());
        leaveRequestRepository.save(leave);

        mockMvc.perform(get("/api/admin/preferences/date-specific/preview")
                        .param("employeeId", String.valueOf(emp.getId()))
                        .param("date", leaveDate.toString())
                        .param("shiftType", "MORNING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CONFLICT")))
                .andExpect(jsonPath("$.canApply", is(false)))
                .andExpect(jsonPath("$.reason", containsString("approved leave")));
    }

    @Test
    @DisplayName("Admin previews female night shift request: returns CONFLICT due to workplace safety rule")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testPreviewFemaleNightRestriction() throws Exception {
        Employee femaleEmp = employeeRepository.findAll().stream()
                .filter(e -> e.getGender() == Gender.FEMALE)
                .findFirst().orElseThrow();
        LocalDate futureDate = LocalDate.now().plusDays(15);

        mockMvc.perform(get("/api/admin/preferences/date-specific/preview")
                        .param("employeeId", String.valueOf(femaleEmp.getId()))
                        .param("date", futureDate.toString())
                        .param("shiftType", "NIGHT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CONFLICT")))
                .andExpect(jsonPath("$.canApply", is(false)))
                .andExpect(jsonPath("$.reason", containsString("Female employees cannot be assigned to night shifts")));
    }

    @Test
    @DisplayName("DateShiftPreferenceResponse includes status and conflict reason")
    @WithMockUser(username = "Admin", authorities = {"ROLE_ADMIN"})
    void testDateShiftPreferenceResponseIncludesStatus() throws Exception {
        Employee emp = employeeRepository.findAll().stream().findFirst().orElseThrow();
        LocalDate targetDate = LocalDate.now().plusDays(18);

        DateShiftPreferenceRequest req = new DateShiftPreferenceRequest(
                emp.getId(), targetDate, ShiftType.GENERAL, "Standard General Shift"
        );

        mockMvc.perform(post("/api/admin/preferences/date-specific")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("VALID")));
    }
}
