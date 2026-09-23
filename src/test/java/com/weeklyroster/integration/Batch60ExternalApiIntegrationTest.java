package com.weeklyroster.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.dto.external.v1.ExternalClientCreateRequest;
import com.weeklyroster.dto.external.v1.ExternalClientResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.service.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Batch60ExternalApiIntegrationTest {

    private static final String DEFAULT_TEST_KEY = "wrms_live_dev_test_secret_key_change_in_prod";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private com.weeklyroster.repository.RosterCycleRepository cycleRepository;

    @Autowired
    private com.weeklyroster.repository.ShiftRepository shiftRepository;

    @Autowired
    private com.weeklyroster.repository.RosterAssignmentRepository assignmentRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Employee seededEmployee;

    @BeforeEach
    void setUp() {
        seededEmployee = employeeRepository.findByEmployeeCode("EMP001")
                .or(() -> employeeRepository.findAll().stream().findFirst())
                .orElseThrow(() -> new IllegalStateException("No employee seeded in database"));

        if (cycleRepository.findTopByOrderByStartDateDesc().isEmpty()) {
            com.weeklyroster.entity.RosterCycle cycle = new com.weeklyroster.entity.RosterCycle();
            cycle.setStartDate(LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)));
            cycle.setEndDate(cycle.getStartDate().plusDays(6));
            cycle.setStatus(com.weeklyroster.entity.RosterStatus.PUBLISHED);
            cycle.setGenerationMode(com.weeklyroster.entity.GenerationMode.AUTOMATIC);
            cycle.setGeneratedAt(java.time.LocalDateTime.now());
            cycle = cycleRepository.save(cycle);

            com.weeklyroster.entity.Shift morning = shiftRepository.findByShiftType(com.weeklyroster.entity.ShiftType.MORNING).orElse(null);
            if (morning != null && seededEmployee != null) {
                com.weeklyroster.entity.RosterAssignment a = new com.weeklyroster.entity.RosterAssignment();
                a.setCycle(cycle);
                a.setEmployee(seededEmployee);
                a.setShift(morning);
                a.setRosterDate(cycle.getStartDate());
                assignmentRepository.save(a);
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("Test 1: Ping with valid X-API-Key header succeeds (200 OK)")
    void test1_PingWithValidApiKeyHeader() throws Exception {
        mockMvc.perform(get("/api/external/v1/ping")
                        .header("X-API-Key", DEFAULT_TEST_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.meta.version").value("v1"))
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty());
    }

    @Test
    @Order(2)
    @DisplayName("Test 2: Ping with valid Authorization Bearer key succeeds (200 OK)")
    void test2_PingWithBearerToken() throws Exception {
        mockMvc.perform(get("/api/external/v1/ping")
                        .header("Authorization", "Bearer " + DEFAULT_TEST_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    @Order(3)
    @DisplayName("Test 3: Ping without API key returns 401 Unauthorized with standard error envelope")
    void test3_PingWithMissingApiKeyReturns401() throws Exception {
        mockMvc.perform(get("/api/external/v1/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty());
    }

    @Test
    @Order(4)
    @DisplayName("Test 4: Ping with invalid API key returns 401 Unauthorized")
    void test4_PingWithInvalidApiKeyReturns401() throws Exception {
        mockMvc.perform(get("/api/external/v1/ping")
                        .header("X-API-Key", "wrms_invalid_key_1234567890"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    @Order(5)
    @DisplayName("Test 5: Correlation ID (X-Request-ID) is generated and returned in header and meta")
    void test5_CorrelationIdGeneratedAndPropagated() throws Exception {
        mockMvc.perform(get("/api/external/v1/ping")
                        .header("X-API-Key", DEFAULT_TEST_KEY))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty());
    }

    @Test
    @Order(6)
    @DisplayName("Test 6: Custom correlation ID provided by client is preserved")
    void test6_CustomCorrelationIdPreserved() throws Exception {
        String customReqId = "custom-test-req-999";
        mockMvc.perform(get("/api/external/v1/ping")
                        .header("X-API-Key", DEFAULT_TEST_KEY)
                        .header("X-Request-ID", customReqId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", customReqId))
                .andExpect(jsonPath("$.meta.requestId").value(customReqId));
    }

    @Test
    @Order(7)
    @DisplayName("Test 7: Get Current Weekly Roster returns cycle and assignments with DTO envelope")
    void test7_GetCurrentRosterWithRosterReadScope() throws Exception {
        mockMvc.perform(get("/api/external/v1/rosters/current")
                        .header("X-API-Key", DEFAULT_TEST_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.startDate").isNotEmpty())
                .andExpect(jsonPath("$.data.endDate").isNotEmpty())
                .andExpect(jsonPath("$.data.assignments").isArray());
    }

    @Test
    @Order(8)
    @DisplayName("Test 8: Get Roster by specific calendar date returns assignment list")
    void test8_GetRosterByDate() throws Exception {
        LocalDate today = LocalDate.now();
        mockMvc.perform(get("/api/external/v1/rosters/by-date")
                        .param("date", today.toString())
                        .header("X-API-Key", DEFAULT_TEST_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @Order(9)
    @DisplayName("Test 9: Get Roster for specific employee code returns duty assignments")
    void test9_GetRosterForEmployee() throws Exception {
        mockMvc.perform(get("/api/external/v1/rosters/employee/" + seededEmployee.getEmployeeCode())
                        .header("X-API-Key", DEFAULT_TEST_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @Order(10)
    @DisplayName("Test 10: Get active employees returns sanitized DTOs without sensitive fields")
    void test10_GetActiveEmployeesExcludesSensitiveFields() throws Exception {
        mockMvc.perform(get("/api/external/v1/employees")
                        .param("activeOnly", "true")
                        .header("X-API-Key", DEFAULT_TEST_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.data[0].employeeCode").isNotEmpty())
                .andExpect(jsonPath("$.data[0].email").isNotEmpty())
                // Ensure sensitive internal fields are absent
                .andExpect(jsonPath("$.data[0].password").doesNotExist())
                .andExpect(jsonPath("$.data[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data[0].user").doesNotExist())
                .andExpect(jsonPath("$.data[0].userId").doesNotExist());
    }

    @Test
    @Order(11)
    @DisplayName("Test 11: Get single employee by code returns employee DTO")
    void test11_GetEmployeeByCode() throws Exception {
        mockMvc.perform(get("/api/external/v1/employees/" + seededEmployee.getEmployeeCode())
                        .header("X-API-Key", DEFAULT_TEST_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.employeeCode").value(seededEmployee.getEmployeeCode()))
                .andExpect(jsonPath("$.data.email").value(seededEmployee.getEmail()));
    }

    @Test
    @Order(12)
    @DisplayName("Test 12: Get active shifts returns shift list and capacities")
    void test12_GetActiveShifts() throws Exception {
        mockMvc.perform(get("/api/external/v1/shifts")
                        .header("X-API-Key", DEFAULT_TEST_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(4))));
    }

    @Test
    @Order(13)
    @DisplayName("Test 13: Get approved leaves returns approved leaves list")
    void test13_GetApprovedLeaves() throws Exception {
        mockMvc.perform(get("/api/external/v1/leaves")
                        .header("X-API-Key", DEFAULT_TEST_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @Order(14)
    @DisplayName("Test 14: Scope enforcement blocks access when required scope is missing (403 Forbidden)")
    void test14_ScopeEnforcementBlocksUnauthorizedScope() throws Exception {
        // Create client with ONLY EMPLOYEE_READ scope (no ROSTER_READ)
        ExternalClientResponse client = apiKeyService.createClient(
                new ExternalClientCreateRequest("EmployeeOnlyClient", "EMPLOYEE_READ", 60)
        );
        String employeeOnlyKey = client.plainApiKey();

        // 1. Employee endpoint should succeed
        mockMvc.perform(get("/api/external/v1/employees")
                        .header("X-API-Key", employeeOnlyKey))
                .andExpect(status().isOk());

        // 2. Roster endpoint should fail with 403 Forbidden
        mockMvc.perform(get("/api/external/v1/rosters/current")
                        .header("X-API-Key", employeeOnlyKey))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @Order(15)
    @DisplayName("Test 15: Admin can manage API clients lifecycle (Create, List, Toggle, Delete)")
    @WithMockUser(authorities = "ROLE_ADMIN")
    void test15_AdminCanManageApiClients() throws Exception {
        ExternalClientCreateRequest createReq = new ExternalClientCreateRequest(
                "PartnerCorp Integration",
                "ROSTER_READ,SHIFT_READ",
                100
        );

        // 1. Create client
        String createResponse = mockMvc.perform(post("/api/admin/external-clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientName").value("PartnerCorp Integration"))
                .andExpect(jsonPath("$.plainApiKey", startsWith("wrms_live_")))
                .andExpect(jsonPath("$.scopes").value("ROSTER_READ,SHIFT_READ"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString();

        ExternalClientResponse created = objectMapper.readValue(createResponse, ExternalClientResponse.class);
        assertNotNull(created.id());

        // 2. List clients
        mockMvc.perform(get("/api/admin/external-clients"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))));

        // 3. Toggle status to disabled
        mockMvc.perform(put("/api/admin/external-clients/" + created.id() + "/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        // 4. Verify disabled key cannot authenticate (401)
        mockMvc.perform(get("/api/external/v1/ping")
                        .header("X-API-Key", created.plainApiKey()))
                .andExpect(status().isUnauthorized());

        // 5. Delete client
        mockMvc.perform(delete("/api/admin/external-clients/" + created.id()))
                .andExpect(status().isNoContent());
    }

    @Test
    @Order(16)
    @DisplayName("Test 16: Exact 12 core database tables strictly preserved (zero new tables)")
    void test16_Exact12CoreTablesPreserved() {
        List<String> actualTables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' ORDER BY table_name",
                String.class);

        Set<String> expected13 = Set.of(
                "device_tokens",
                "employee_requests",
                "employees",
                "leave_requests",
                "master_reference_data",
                "notifications",
                "roster_assignments",
                "roster_cycles",
                "shift_handovers",
                "shifts",
                "sms_delivery_logs",
                "system_audit_logs",
                "users"
        );

        assertEquals(13, actualTables.size(), "Database must strictly contain EXACTLY 13 tables");
        assertEquals(expected13, Set.copyOf(actualTables), "13 production table names must match exact consolidated schema");
    }

    @Test
    @Order(17)
    @DisplayName("Test 17: In-memory rate limiting returns 429 Too Many Requests when threshold exceeded")
    void test17_RateLimitingExceededReturns429() throws Exception {
        // Create a unique client key for rate limit testing
        ExternalClientResponse client = apiKeyService.createClient(
                new ExternalClientCreateRequest("RateLimitTestClient", "ROSTER_READ", 60)
        );
        String testKey = client.plainApiKey();

        // Fire 60 requests within the limit
        for (int i = 0; i < 60; i++) {
            mockMvc.perform(get("/api/external/v1/ping")
                            .header("X-API-Key", testKey))
                    .andExpect(status().isOk());
        }

        // The 61st request must trigger 429 Too Many Requests
        mockMvc.perform(get("/api/external/v1/ping")
                        .header("X-API-Key", testKey))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMIT_EXCEEDED"));
    }
}
