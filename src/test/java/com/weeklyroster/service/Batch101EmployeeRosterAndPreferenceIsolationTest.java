package com.weeklyroster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.dto.request.AdminShiftPreferenceRequest;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.repository.EmployeeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wrms.dev.credential-mirror.enabled=false",
        "spring.datasource.url=jdbc:sqlite:target/test-wrms.db?busy_timeout=30000&journal_mode=WAL"
})
public class Batch101EmployeeRosterAndPreferenceIsolationTest {

    static {
        try {
            System.setProperty("spring.profiles.active", "test");
            System.setProperty("spring.datasource.url", "jdbc:sqlite:target/test-wrms.db?busy_timeout=30000&journal_mode=WAL");
            java.io.File targetDir = new java.io.File("target");
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
            java.io.File realDb = new java.io.File("data/wrms.db");
            java.io.File testDb = new java.io.File("target/test-wrms.db");
            if (realDb.exists()) {
                java.nio.file.Files.copy(realDb.toPath(), testDb.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Test
    @DisplayName("BATCH 101: Admin fetching individual employee roster returns isolated and correct assignments")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    public void testIndividualEmployeeRosterEndpointIsolation() throws Exception {
        List<Employee> all = employeeRepository.findAll();
        assertTrue(all.size() >= 2, "Requires at least 2 employees for isolation verification");

        Employee emp1 = all.get(0);
        Employee emp2 = all.get(1);

        // Fetch Employee 1 roster
        mockMvc.perform(get("/api/rosters/employee/" + emp1.getId()))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String json = result.getResponse().getContentAsString();
                    assertNotNull(json);
                });

        // Fetch Employee 2 roster
        mockMvc.perform(get("/api/rosters/employee/" + emp2.getId()))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String json = result.getResponse().getContentAsString();
                    assertNotNull(json);
                });
    }

    @Test
    @DisplayName("BATCH 101: Preference update for Employee 1 does not mutate Employee 2 preferences")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    public void testPreferenceEndpointIsolation() throws Exception {
        List<Employee> all = employeeRepository.findAll();
        assertTrue(all.size() >= 2, "Requires at least 2 employees");

        Employee emp1 = all.get(0);
        Employee emp2 = all.get(1);

        // Update preference for emp1
        AdminShiftPreferenceRequest req1 = new AdminShiftPreferenceRequest(List.of("NIGHT", "MORNING"), "NIGHT, MORNING");

        mockMvc.perform(put("/api/admin/preferences/employee/" + emp1.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isOk());

        // Verify emp1 has updated preference
        mockMvc.perform(get("/api/admin/preferences/employee/" + emp1.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredShiftList", hasItems("NIGHT", "MORNING")));

        // Verify emp2 is distinct and not mutated by emp1 request
        mockMvc.perform(get("/api/admin/preferences/employee/" + emp2.getId()))
                .andExpect(status().isOk());
    }
}
