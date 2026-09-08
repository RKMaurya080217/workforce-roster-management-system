package com.weeklyroster.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.weeklyroster.dto.request.CreateHandoverRequest;
import com.weeklyroster.dto.request.UpdateHandoverRequest;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.HandoverPriority;
import com.weeklyroster.entity.HandoverStatus;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.ShiftHandoverRepository;
import com.weeklyroster.repository.ShiftRepository;
import com.weeklyroster.service.ShiftHandoverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public class Batch54ShiftHandoverSaveE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private ShiftHandoverRepository handoverRepository;

    @Autowired
    private ShiftHandoverService handoverService;

    private Long shiftId;
    private Long emp1Id;
    private Long emp2Id;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        List<Employee> emps = employeeRepository.findAll();
        assertTrue(emps.size() >= 2, "At least 2 employees must be seeded");
        emp1Id = emps.get(0).getId();
        emp2Id = emps.get(1).getId();

        Shift shift = shiftRepository.findByShiftType(ShiftType.MORNING)
                .or(() -> shiftRepository.findAll().stream().findFirst())
                .orElseThrow();
        shiftId = shift.getId();
    }

    @Test
    @DisplayName("Test 1: Jackson deserializes payload with duplicate alias keys (summary & shiftSummary, notes & importantNotes)")
    void test1_JacksonDuplicateAliasesDeserialization() throws Exception {
        String payload = """
        {
          "handoverDate": "2026-09-08",
          "shiftId": 1,
          "toEmployeeId": 2,
          "priority": "HIGH",
          "summary": "Primary summary text",
          "shiftSummary": "Primary summary text",
          "pendingTasks": "Monitor queue",
          "completedTasks": "Server upgrade complete",
          "importantNotes": "Cooling alert check",
          "notes": "Cooling alert check"
        }
        """;

        CreateHandoverRequest req = objectMapper.readValue(payload, CreateHandoverRequest.class);
        assertNotNull(req);
        assertEquals(LocalDate.of(2026, 9, 8), req.handoverDate());
        assertEquals(1L, req.shiftId());
        assertEquals(2L, req.toEmployeeId());
        assertEquals("Primary summary text", req.summary());
        assertEquals("Monitor queue", req.pendingTasks());
        assertEquals("Server upgrade complete", req.completedTasks());
        assertEquals("Cooling alert check", req.importantNotes());
        assertEquals(HandoverPriority.HIGH, req.priority());
    }

    @Test
    @DisplayName("Test 2: Admin creates handover via POST /api/handovers with clean payload")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test2_AdminCreateHandoverCleanPayload() throws Exception {
        String payload = String.format("""
        {
          "handoverDate": "2026-09-08",
          "shiftId": %d,
          "fromEmployeeId": %d,
          "toEmployeeId": %d,
          "priority": "MEDIUM",
          "summary": "Admin initiated handover note",
          "pendingTasks": "Verify night logs",
          "completedTasks": "All morning checks pass",
          "importantNotes": "None"
        }
        """, shiftId, emp1Id, emp2Id);

        mockMvc.perform(post("/api/handovers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.summary").value("Admin initiated handover note"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.fromEmployeeId").value(emp1Id))
                .andExpect(jsonPath("$.toEmployeeId").value(emp2Id));
    }

    @Test
    @DisplayName("Test 3: Admin creates handover via POST /api/handovers with legacy duplicate aliases payload (No HttpMessageConversionException)")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test3_AdminCreateHandoverWithLegacyDuplicateAliases() throws Exception {
        String payload = String.format("""
        {
          "handoverDate": "2026-09-08",
          "shiftId": %d,
          "toEmployeeId": %d,
          "priority": "HIGH",
          "summary": "Shift summary via duplicate keys",
          "shiftSummary": "Shift summary via duplicate keys",
          "pendingTasks": "Resolve ticket #404",
          "completedTasks": "Database maintenance",
          "importantNotes": "High memory warning",
          "notes": "High memory warning"
        }
        """, shiftId, emp2Id);

        mockMvc.perform(post("/api/handovers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.summary").value("Shift summary via duplicate keys"))
                .andExpect(jsonPath("$.importantNotes").value("High memory warning"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    @DisplayName("Test 4: Admin creates handover via POST /api/admin/handovers")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test4_AdminDirectEndpointCreateHandover() throws Exception {
        String payload = String.format("""
        {
          "handoverDate": "2026-09-09",
          "shiftId": %d,
          "fromEmployeeId": %d,
          "toEmployeeId": %d,
          "priority": "CRITICAL",
          "summary": "Direct admin handover creation",
          "pendingTasks": "Investigate network latency",
          "completedTasks": "Firewall patched",
          "importantNotes": "Escalated to ISP"
        }
        """, shiftId, emp1Id, emp2Id);

        mockMvc.perform(post("/api/admin/handovers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.summary").value("Direct admin handover creation"))
                .andExpect(jsonPath("$.priority").value("CRITICAL"))
                .andExpect(jsonPath("$.fromEmployeeId").value(emp1Id));
    }

    @Test
    @DisplayName("Test 5: Employee creates handover via POST /api/handovers scoped to own profile")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void test5_EmployeeCreateHandoverScopedToOwnProfile() throws Exception {
        Employee emp = employeeRepository.findByUserUsernameIgnoreCase("emp001")
                .or(() -> employeeRepository.findByEmployeeCodeIgnoreCase("emp001"))
                .orElseGet(() -> employeeRepository.findAll().get(0));

        String payload = String.format("""
        {
          "handoverDate": "2026-09-08",
          "shiftId": %d,
          "toEmployeeId": %d,
          "priority": "LOW",
          "summary": "Routine shift changeover note",
          "pendingTasks": "Clean workstation",
          "completedTasks": "Logs archived",
          "importantNotes": "None"
        }
        """, shiftId, emp2Id.equals(emp.getId()) ? emp1Id : emp2Id);

        mockMvc.perform(post("/api/handovers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fromEmployeeId").value(emp.getId()))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    @DisplayName("Test 6: Handover acknowledgement transitions status to ACKNOWLEDGED")
    @WithMockUser(username = "emp002", authorities = {"ROLE_EMPLOYEE"})
    void test6_HandoverAcknowledgementFlow() throws Exception {
        Employee emp2 = employeeRepository.findById(emp2Id).orElseThrow();

        // 1. Create a handover targeted to emp2
        CreateHandoverRequest req = new CreateHandoverRequest(
                LocalDate.now(), shiftId, emp1Id, emp2Id,
                "Handover awaiting ack", "Task A", "Task B", "Important details", HandoverPriority.MEDIUM
        );
        var created = handoverService.createHandover(emp1Id, req, "emp001");
        Long createdId = created.id();

        // 2. Acknowledge as emp2
        mockMvc.perform(post("/api/handovers/" + createdId + "/acknowledge")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"remarks\":\"Acknowledged and reviewed thoroughly\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.importantNotes").value(org.hamcrest.Matchers.containsString("Acknowledged and reviewed thoroughly")));
    }

    @Test
    @DisplayName("Test 7: Admin retrieves all handovers via GET /api/admin/handovers")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test7_AdminGetAllHandovers() throws Exception {
        mockMvc.perform(get("/api/admin/handovers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Test 8: Frontend code contains Outgoing Staff selector, auto-population, and correct view refresh")
    void test8_FrontendIntegrationVerification() throws Exception {
        String indexHtml = Files.readString(new File("src/main/resources/static/index.html").toPath());
        String enterpriseJs = Files.readString(new File("src/main/resources/static/enterprise-app.js").toPath());

        assertTrue(indexHtml.contains("id=\"handoverFromStaffRow\""), "index.html must have handoverFromStaffRow for admin outgoing staff selection");
        assertTrue(indexHtml.contains("id=\"handoverFromEmployee\""), "index.html must have handoverFromEmployee select element");

        assertTrue(enterpriseJs.contains("openHandoverCreateModal()"), "enterprise-app.js must provide openHandoverCreateModal");
        assertTrue(enterpriseJs.contains("populateHandoverDropdowns()"), "enterprise-app.js must provide populateHandoverDropdowns");
        assertTrue(enterpriseJs.contains("renderAdminHandoversView()"), "enterprise-app.js must provide renderAdminHandoversView");
        assertTrue(enterpriseJs.contains("fromEmployeeId"), "enterprise-app.js must handle fromEmployeeId parameter");
    }
}
