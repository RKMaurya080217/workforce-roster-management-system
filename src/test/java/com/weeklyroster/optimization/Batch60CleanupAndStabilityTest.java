package com.weeklyroster.optimization;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.service.email.BrevoEmailService;
import com.weeklyroster.service.email.EmailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Batch60CleanupAndStabilityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmailService emailService;

    @Autowired
    private BrevoEmailService brevoEmailService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @Order(1)
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("Batch 60: Consecutive Multi-Week Roster Generation (Sep 28 -> Oct 05 -> Oct 12) without Rest Violations")
    void testConsecutiveMultiWeekRoster_Sep28_Oct05_Oct12() throws Exception {
        // Week 1: 2026-09-28 to 2026-10-04
        MvcResult res1 = mockMvc.perform(post("/api/rosters/generate?startDate=2026-09-28")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").value("2026-09-28"))
                .andExpect(jsonPath("$.endDate").value("2026-10-04"))
                .andReturn();

        JsonNode cycle1Node = objectMapper.readTree(res1.getResponse().getContentAsString());
        JsonNode assignments1 = cycle1Node.get("assignments");
        assertNotNull(assignments1);
        assertEquals(49, assignments1.size(), "Week 1 must produce exactly 49 assignments (7x7)");

        // Find employee who worked Night on Sunday (2026-10-04)
        Long sundayNightEmpId = null;
        for (JsonNode a : assignments1) {
            if ("2026-10-04".equals(a.path("rosterDate").asText()) && "NIGHT".equals(a.path("shiftType").asText())) {
                sundayNightEmpId = a.path("employeeId").asLong();
                break;
            }
        }
        assertNotNull(sundayNightEmpId, "Week 1 must have an employee working Sunday Night (2026-10-04)");

        // Week 2: 2026-10-05 to 2026-10-11
        MvcResult res2 = mockMvc.perform(post("/api/rosters/generate?startDate=2026-10-05")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").value("2026-10-05"))
                .andExpect(jsonPath("$.endDate").value("2026-10-11"))
                .andReturn();

        JsonNode cycle2Node = objectMapper.readTree(res2.getResponse().getContentAsString());
        JsonNode assignments2 = cycle2Node.get("assignments");
        assertNotNull(assignments2);
        assertEquals(49, assignments2.size(), "Week 2 must produce exactly 49 assignments (7x7)");

        // Verify the Sunday night worker has recovery Weekly OFF on Monday (2026-10-05) or safe transition
        for (JsonNode a : assignments2) {
            if (a.path("employeeId").asLong() == sundayNightEmpId && "2026-10-05".equals(a.path("rosterDate").asText())) {
                boolean isOff = a.path("weeklyOff").asBoolean();
                String shiftType = a.path("shiftType").asText();
                assertTrue(isOff || "OFF".equals(shiftType) || "NIGHT".equals(shiftType) || "EVENING".equals(shiftType),
                        "Employee working Sunday night must not be assigned morning/general on Monday without 12h rest");
            }
        }

        // Find employee who worked Night on Sunday (2026-10-11) of Week 2
        Long week2SundayNightEmpId = null;
        for (JsonNode a : assignments2) {
            if ("2026-10-11".equals(a.path("rosterDate").asText()) && "NIGHT".equals(a.path("shiftType").asText())) {
                week2SundayNightEmpId = a.path("employeeId").asLong();
                break;
            }
        }
        assertNotNull(week2SundayNightEmpId, "Week 2 must have an employee working Sunday Night (2026-10-11)");

        // Week 3: 2026-10-12 to 2026-10-18
        MvcResult res3 = mockMvc.perform(post("/api/rosters/generate?startDate=2026-10-12")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").value("2026-10-12"))
                .andExpect(jsonPath("$.endDate").value("2026-10-18"))
                .andReturn();

        JsonNode cycle3Node = objectMapper.readTree(res3.getResponse().getContentAsString());
        JsonNode assignments3 = cycle3Node.get("assignments");
        assertNotNull(assignments3);
        assertEquals(49, assignments3.size(), "Week 3 must produce exactly 49 assignments (7x7)");

        // Verify Week 2 Sunday night worker has safe recovery on Monday (2026-10-12)
        for (JsonNode a : assignments3) {
            if (a.path("employeeId").asLong() == week2SundayNightEmpId && "2026-10-12".equals(a.path("rosterDate").asText())) {
                boolean isOff = a.path("weeklyOff").asBoolean();
                String shiftType = a.path("shiftType").asText();
                assertTrue(isOff || "OFF".equals(shiftType) || "NIGHT".equals(shiftType) || "EVENING".equals(shiftType),
                        "Employee working Week 2 Sunday night must not be assigned morning/general on Week 3 Monday without 12h rest");
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("Batch 60: Brevo REST API Email Service is Primary and Wired Correctly")
    void testBrevoEmailConfigurationIntegrity() {
        assertNotNull(emailService, "EmailService must be present");
        assertNotNull(brevoEmailService, "BrevoEmailService must be present");
        assertEquals("BREVO", emailService.getActiveProviderName(),
                "Primary active email provider must be BREVO");
        assertSame(brevoEmailService, emailService.getActiveProvider(),
                "Active email provider must be BrevoEmailService");
    }

    @Test
    @Order(3)
    @DisplayName("Batch 60: CSS Sidebar Flex-Basis and Mobile Responsive Rules")
    void testSidebarAndMobileCssContainment() throws Exception {
        File cssFile = new File("src/main/resources/static/styles.css");
        assertTrue(cssFile.exists(), "styles.css must exist");

        String cssContent = Files.readString(cssFile.toPath());

        // Verify .app-sidebar.collapsed specifies flex: 0 0 var(--sidebar-collapsed-width)
        assertTrue(cssContent.contains(".app-sidebar.collapsed {"), "Must contain .app-sidebar.collapsed rule");
        assertTrue(cssContent.contains("flex: 0 0 var(--sidebar-collapsed-width) !important;"),
                "Collapsed sidebar must specify flex-basis override to prevent flex container stretching");
        assertTrue(cssContent.contains("width: var(--sidebar-collapsed-width) !important;"),
                "Collapsed sidebar must enforce width");

        // Verify mobile responsive rules exist for max-width: 768px
        assertTrue(cssContent.contains("@media (max-width: 768px)"), "Must have mobile media query rules");
    }

    @Test
    @Order(4)
    @DisplayName("Batch 60: All 14 Database Tables DDL & Schema Consistency")
    void testAll14DatabaseTablesIntegrity() throws Exception {
        File schemaFile = new File("src/main/resources/schema.sql");
        assertTrue(schemaFile.exists(), "schema.sql must exist");

        String schemaContent = Files.readString(schemaFile.toPath());

        List<String> expectedTables = Arrays.asList(
                "users",
                "employees",
                "shifts",
                "roster_cycles",
                "roster_assignments",
                "master_reference_data",
                "leave_requests",
                "shift_handovers",
                "notifications",
                "employee_requests",
                "system_audit_logs",
                "sms_delivery_logs",
                "device_tokens"
        );

        assertFalse(schemaContent.toLowerCase().contains("create table if not exists employee_skills"),
                "schema.sql must NOT contain DDL for retired table: employee_skills");

        for (String table : expectedTables) {
            assertTrue(schemaContent.toLowerCase().contains("create table if not exists " + table),
                    "schema.sql must contain DDL for table: " + table);
        }

        // Verify tables exist in running H2 / test database
        for (String table : expectedTables) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = ?",
                    Integer.class,
                    table.toLowerCase()
            );
            assertNotNull(count);
            assertTrue(count > 0, "Table '" + table + "' must exist in database schema");
        }
    }

    @Test
    @Order(5)
    @DisplayName("Batch 60: Application Properties Cleanliness and Balanced Syntax")
    void testApplicationPropertiesClean() throws Exception {
        File propsFile = new File("src/main/resources/application.properties");
        assertTrue(propsFile.exists(), "application.properties must exist");

        List<String> lines = Files.readAllLines(propsFile.toPath());

        long fcmEnabledCount = lines.stream()
                .filter(l -> l.trim().startsWith("fcm.enabled="))
                .count();
        assertEquals(1, fcmEnabledCount, "fcm.enabled must appear exactly once in application.properties");

        // Check fcm.web.vapid-key brace balance
        for (String line : lines) {
            if (line.trim().startsWith("fcm.web.vapid-key=")) {
                int opens = 0;
                int closes = 0;
                for (char c : line.toCharArray()) {
                    if (c == '{') opens++;
                    if (c == '}') closes++;
                }
                assertEquals(opens, closes, "fcm.web.vapid-key must have matching open and close braces: " + line);
            }
        }
    }

    @Test
    @Order(6)
    @DisplayName("Batch 60: app.js Topbar Title Fallback Prevents 'undefined'")
    void testAppJsTopbarTitleFallback() throws Exception {
        File appJsFile = new File("src/main/resources/static/app.js");
        assertTrue(appJsFile.exists(), "app.js must exist");

        String appJsContent = Files.readString(appJsFile.toPath());
        assertTrue(appJsContent.contains("${state.inspectedEmployeeName || 'Employee'} - Schedule"),
                "app.js must include fallback for state.inspectedEmployeeName");
    }
}
