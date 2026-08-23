package com.weeklyroster.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.annotation.Transactional;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterOverrideRepository;
import com.weeklyroster.repository.RosterVersionRepository;
import java.time.LocalDate;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RosterGenerationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private RosterOverrideRepository overrideRepository;

    @Autowired(required = false)
    private RosterVersionRepository versionRepository;

    @BeforeEach
    void cleanCycles() {
        LocalDate d = LocalDate.of(2026, 8, 17);
        cycleRepository.findByStartDateAndEndDate(d, d.plusDays(6)).ifPresent(c -> {
            if (versionRepository != null) {
                versionRepository.deleteByCycleIdNative(c.getId());
            }
            overrideRepository.deleteByCycleIdNative(c.getId());
            assignmentRepository.deleteByCycleIdNative(c.getId());
            cycleRepository.deleteCycleByIdNative(c.getId());
        });
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("Generate Roster with ISO Date (2026-08-17) returns 200 OK JSON")
    void testGenerate_WithIsoDate_Success() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/rosters/generate?startDate=2026-08-17")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.startDate").value("2026-08-17"))
                .andExpect(jsonPath("$.endDate").value("2026-08-23"))
                .andExpect(jsonPath("$.assignments").isArray())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertNotNull(body);
        assertTrue(body.trim().startsWith("{"), "Response must be valid JSON object");
        assertFalse(body.trim().startsWith("<"), "Response must not contain HTML");
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("Generate Roster with European/Indian Date (17-08-2026) returns 200 OK JSON")
    void testGenerate_WithDdMmYyyyDate_Success() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/rosters/generate?startDate=17-08-2026")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.startDate").value("2026-08-17"))
                .andExpect(jsonPath("$.endDate").value("2026-08-23"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.trim().startsWith("{"));
        assertFalse(body.trim().startsWith("<"));
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("Generate Roster with Slash Date (17/08/2026) returns 200 OK JSON")
    void testGenerate_WithSlashDate_Success() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/rosters/generate?startDate=17/08/2026")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.startDate").value("2026-08-17"))
                .andExpect(jsonPath("$.endDate").value("2026-08-23"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.trim().startsWith("{"));
        assertFalse(body.trim().startsWith("<"));
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("Generate Roster without startDate parameter defaults to next day and returns 200 OK JSON")
    void testGenerate_WithoutStartDateParam_Success() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/rosters/generate")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.startDate").isNotEmpty())
                .andExpect(jsonPath("$.endDate").isNotEmpty())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.trim().startsWith("{"));
        assertFalse(body.trim().startsWith("<"));
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("Generate Roster with invalid date format returns 400 Bad Request JSON (never HTML)")
    void testGenerate_WithInvalidDate_Returns400Json() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/rosters/generate?startDate=not-a-date")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.trim().startsWith("{"), "Error response must be valid JSON object");
        assertFalse(body.trim().startsWith("<"), "Error response must not be HTML");
    }

    @Test
    @DisplayName("Unauthenticated request to Generate Roster returns 401 Unauthorized JSON (never HTML)")
    void testGenerate_Unauthenticated_Returns401Json() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/rosters/generate?startDate=2026-08-17")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.trim().startsWith("{"), "Unauthorized response must be valid JSON object");
        assertFalse(body.trim().startsWith("<"), "Unauthorized response must not be HTML");
    }

    @Test
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    @DisplayName("Employee user trying to Generate Roster returns 403 Forbidden JSON (never HTML)")
    void testGenerate_EmployeeUser_Returns403Json() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/rosters/generate?startDate=2026-08-17")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.trim().startsWith("{"), "Forbidden response must be valid JSON object");
        assertFalse(body.trim().startsWith("<"), "Forbidden response must not be HTML");
    }

    @Test
    @DisplayName("Direct /error dispatch returns JSON ApiErrorResponse (never HTML)")
    void testErrorEndpoint_ReturnsJson() throws Exception {
        MvcResult result = mockMvc.perform(get("/error")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").exists())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.trim().startsWith("{"), "Error endpoint must return JSON object");
        assertFalse(body.trim().startsWith("<"), "Error endpoint must never return HTML");
    }
}
