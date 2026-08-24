package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.dto.request.PreferenceSubmitRequest;
import com.weeklyroster.dto.response.PreferenceResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.PreferenceStatus;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.EmployeeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public class Batch27ShiftPreferencesMalformedRequestBodyFixTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmployeePreferenceService preferenceService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Test 1: Submit single shift and single day preferences with temporary notes")
    @WithMockUser(username = "emp001", roles = {"EMPLOYEE"})
    void test1_SingleShiftAndDayPreferences() throws Exception {
        Employee emp = employeeRepository.findAll().get(0);

        String jsonPayload = """
        {
          "preferredShiftTypes": ["GENERAL"],
          "avoidShiftTypes": ["EVENING"],
          "preferredOffDays": ["SUNDAY"],
          "preferredWorkingDays": ["MONDAY"],
          "temporaryConstraints": "For checking purpose"
        }
        """;

        mockMvc.perform(post("/api/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.preferredShiftTypes").value("GENERAL"))
                .andExpect(jsonPath("$.preferredShifts").value("GENERAL"))
                .andExpect(jsonPath("$.avoidShiftTypes").value("EVENING"))
                .andExpect(jsonPath("$.avoidShifts").value("EVENING"))
                .andExpect(jsonPath("$.preferredOffDays").value("SUNDAY"))
                .andExpect(jsonPath("$.preferredWorkingDays").value("MONDAY"))
                .andExpect(jsonPath("$.temporaryRestrictions").value("For checking purpose"))
                .andExpect(jsonPath("$.temporaryConstraints").value("For checking purpose"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("Test 2: Submit multiple shift types as collection")
    @WithMockUser(username = "emp001", roles = {"EMPLOYEE"})
    void test2_MultipleShiftTypes() throws Exception {
        String jsonPayload = """
        {
          "preferredShiftTypes": ["GENERAL", "MORNING"],
          "avoidShiftTypes": ["NIGHT"],
          "preferredOffDays": ["SUNDAY"],
          "preferredWorkingDays": ["MONDAY", "TUESDAY"]
        }
        """;

        mockMvc.perform(post("/api/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.preferredShiftTypes").value("GENERAL, MORNING"))
                .andExpect(jsonPath("$.preferredWorkingDays").value("MONDAY, TUESDAY"));
    }

    @Test
    @DisplayName("Test 3: Submit multiple OFF days as collection")
    @WithMockUser(username = "emp001", roles = {"EMPLOYEE"})
    void test3_MultipleOffDays() throws Exception {
        String jsonPayload = """
        {
          "preferredShiftTypes": ["MORNING"],
          "avoidShiftTypes": ["NIGHT"],
          "preferredOffDays": ["SUNDAY", "SATURDAY"],
          "preferredWorkingDays": ["MONDAY", "WEDNESDAY"]
        }
        """;

        mockMvc.perform(post("/api/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.preferredOffDays").value("SUNDAY, SATURDAY"));
    }

    @Test
    @DisplayName("Test 4: Leave optional fields empty (null or empty arrays)")
    @WithMockUser(username = "emp001", roles = {"EMPLOYEE"})
    void test4_EmptyOptionalFields() throws Exception {
        String jsonPayload = """
        {
          "preferredShiftTypes": ["GENERAL"],
          "avoidShiftTypes": [],
          "preferredOffDays": [],
          "preferredWorkingDays": null,
          "temporaryRestrictions": null
        }
        """;

        mockMvc.perform(post("/api/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.preferredShiftTypes").value("GENERAL"));
    }

    @Test
    @DisplayName("Test 5: Conflicting shifts and days produce clear BusinessException")
    void test5_ConflictValidation() {
        Employee emp = employeeRepository.findAll().get(0);

        // Conflicting shifts: GENERAL in both preferred and avoided
        PreferenceSubmitRequest reqShiftConflict = new PreferenceSubmitRequest(
                "GENERAL, MORNING", "SUNDAY", "MONDAY", "GENERAL", null, null, null, null
        );

        BusinessException shiftEx = assertThrows(BusinessException.class, () -> {
            preferenceService.submitPreference(emp.getId(), reqShiftConflict, emp.getUser().getUsername());
        });
        assertTrue(shiftEx.getMessage().contains("cannot be both preferred and avoided"));

        // Conflicting days: SUNDAY in both OFF and working
        PreferenceSubmitRequest reqDayConflict = new PreferenceSubmitRequest(
                "MORNING", "SUNDAY", "SUNDAY, MONDAY", "EVENING", null, null, null, null
        );

        BusinessException dayEx = assertThrows(BusinessException.class, () -> {
            preferenceService.submitPreference(emp.getId(), reqDayConflict, emp.getUser().getUsername());
        });
        assertTrue(dayEx.getMessage().contains("cannot be selected as both a preferred OFF day and preferred working day"));
    }

    @Test
    @DisplayName("Test 6: Full backward compatibility for string inputs and alternate field aliases")
    @WithMockUser(username = "emp001", roles = {"EMPLOYEE"})
    void test6_StringAndAliasCompatibility() throws Exception {
        String jsonPayload = """
        {
          "preferredShifts": "General",
          "avoidShifts": "Night",
          "preferredOFFDays": "Sunday",
          "workingDays": "Monday",
          "notes": "Testing string and alias support"
        }
        """;

        mockMvc.perform(post("/api/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.preferredShiftTypes").value("GENERAL"))
                .andExpect(jsonPath("$.avoidShiftTypes").value("NIGHT"))
                .andExpect(jsonPath("$.preferredOffDays").value("SUNDAY"))
                .andExpect(jsonPath("$.preferredWorkingDays").value("MONDAY"))
                .andExpect(jsonPath("$.temporaryRestrictions").value("Testing string and alias support"));
    }
}