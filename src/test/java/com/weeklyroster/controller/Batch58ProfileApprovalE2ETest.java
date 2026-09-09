package com.weeklyroster.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.weeklyroster.dto.request.LeaveDecisionRequest;
import com.weeklyroster.dto.request.PreferenceDecisionRequest;
import com.weeklyroster.dto.request.ProfileChangeDecisionRequest;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.EmployeePreferenceRepository;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.LeaveRequestRepository;
import com.weeklyroster.repository.ProfileChangeRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public class Batch58ProfileApprovalE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ProfileChangeRequestRepository profileChangeRequestRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private EmployeePreferenceRepository preferenceRepository;

    private ObjectMapper objectMapper;
    private Employee emp001;

    @BeforeEach
    void setup() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        emp001 = employeeRepository.findByEmployeeCode("EMP001")
                .or(() -> employeeRepository.findAll().stream().findFirst())
                .orElseThrow(() -> new IllegalStateException("No employee found in database"));
    }

    @Test
    @DisplayName("Test 1: Jackson deserialization for ProfileChangeDecisionRequest supports decisionReason, adminRemarks, and unknown fields")
    void test1_JacksonProfileDecisionDeserialization() throws Exception {
        // Frontend Unified Approvals payload: { "decisionReason": "Approved by administrator" }
        String json1 = "{\"decisionReason\": \"Approved by administrator\"}";
        ProfileChangeDecisionRequest req1 = objectMapper.readValue(json1, ProfileChangeDecisionRequest.class);
        assertEquals("Approved by administrator", req1.adminRemarks());

        // Admin Modal payload: { "adminRemarks": "Approved via modal" }
        String json2 = "{\"adminRemarks\": \"Approved via modal\"}";
        ProfileChangeDecisionRequest req2 = objectMapper.readValue(json2, ProfileChangeDecisionRequest.class);
        assertEquals("Approved via modal", req2.adminRemarks());

        // Both keys present
        String json3 = "{\"adminRemarks\": \"Both keys\", \"decisionReason\": \"Both keys\"}";
        ProfileChangeDecisionRequest req3 = objectMapper.readValue(json3, ProfileChangeDecisionRequest.class);
        assertEquals("Both keys", req3.adminRemarks());

        // Unknown extra fields tolerated without Malformed Request Body error
        String json4 = "{\"decisionReason\": \"Tolerant\", \"extraField\": \"value\", \"nested\": 123}";
        ProfileChangeDecisionRequest req4 = objectMapper.readValue(json4, ProfileChangeDecisionRequest.class);
        assertEquals("Tolerant", req4.adminRemarks());
    }

    @Test
    @DisplayName("Test 2: Jackson deserialization for PreferenceDecisionRequest supports decision/reviewNote aliases")
    void test2_JacksonPreferenceDecisionDeserialization() throws Exception {
        // Unified approvals payload: { "decision": "APPROVE", "reviewNote": "Approved note" }
        String json1 = "{\"decision\": \"APPROVE\", \"reviewNote\": \"Approved note\"}";
        PreferenceDecisionRequest req1 = objectMapper.readValue(json1, PreferenceDecisionRequest.class);
        assertEquals(PreferenceStatus.APPROVED, req1.status());
        assertEquals("Approved note", req1.adminRemarks());

        // Unified approvals reject payload: { "decision": "REJECT", "reviewNote": "Rejected note" }
        String json2 = "{\"decision\": \"REJECT\", \"reviewNote\": \"Rejected note\"}";
        PreferenceDecisionRequest req2 = objectMapper.readValue(json2, PreferenceDecisionRequest.class);
        assertEquals(PreferenceStatus.REJECTED, req2.status());
        assertEquals("Rejected note", req2.adminRemarks());

        // Standard payload: { "status": "APPROVED", "adminRemarks": "Standard" }
        String json3 = "{\"status\": \"APPROVED\", \"adminRemarks\": \"Standard\"}";
        PreferenceDecisionRequest req3 = objectMapper.readValue(json3, PreferenceDecisionRequest.class);
        assertEquals(PreferenceStatus.APPROVED, req3.status());
        assertEquals("Standard", req3.adminRemarks());

        // Dual combined payload with unknown properties
        String json4 = "{\"status\": \"APPROVED\", \"decision\": \"APPROVE\", \"adminRemarks\": \"Combined\", \"reviewNote\": \"Combined\", \"unrecognizedField\": 999}";
        PreferenceDecisionRequest req4 = objectMapper.readValue(json4, PreferenceDecisionRequest.class);
        assertEquals(PreferenceStatus.APPROVED, req4.status());
        assertEquals("Combined", req4.adminRemarks());
    }

    @Test
    @DisplayName("Test 3: Jackson deserialization for LeaveDecisionRequest supports decisionReason and unknown fields")
    void test3_JacksonLeaveDecisionDeserialization() throws Exception {
        String json1 = "{\"decisionReason\": \"Granted leave\"}";
        LeaveDecisionRequest req1 = objectMapper.readValue(json1, LeaveDecisionRequest.class);
        assertEquals("Granted leave", req1.remarks());

        String json2 = "{\"adminRemarks\": \"Granted leave\"}";
        LeaveDecisionRequest req2 = objectMapper.readValue(json2, LeaveDecisionRequest.class);
        assertEquals("Granted leave", req2.remarks());

        String json3 = "{\"remarks\": \"Granted leave\", \"extraProp\": true}";
        LeaveDecisionRequest req3 = objectMapper.readValue(json3, LeaveDecisionRequest.class);
        assertEquals("Granted leave", req3.remarks());
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("CASE 1: EMP001 -> Approve Profile Change -> Approval successful and employee profile updated")
    void test4_Case1_ApproveProfileChange_UpdatesEmployeeProfile() throws Exception {
        String oldContact = emp001.getContactNumber() != null ? emp001.getContactNumber() : "9123456780";
        String targetContact = "9998887776";

        ProfileChangeRequest req = new ProfileChangeRequest();
        req.setEmployee(emp001);
        req.setFieldName("contactNumber");
        req.setCurrentValue(oldContact);
        req.setRequestedValue(targetContact);
        req.setStatus(ProfileChangeStatus.PENDING);
        req.setRequestedAt(LocalDateTime.now());
        ProfileChangeRequest saved = profileChangeRequestRepository.save(req);

        // Call Unified Approvals endpoint with frontend payload { "decisionReason": "..." }
        String payload = """
        {
            "decisionReason": "Approved by administrator"
        }
        """;

        mockMvc.perform(post("/api/admin/approvals/profile/" + saved.getId() + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.adminRemarks").value("Approved by administrator"));

        // Verify employee profile in DB was updated
        Employee updatedEmp = employeeRepository.findById(emp001.getId()).orElseThrow();
        assertEquals(targetContact, updatedEmp.getContactNumber(), "Employee contactNumber must be updated to requested value");

        // Verify request status in DB
        ProfileChangeRequest verifiedReq = profileChangeRequestRepository.findById(saved.getId()).orElseThrow();
        assertEquals(ProfileChangeStatus.APPROVED, verifiedReq.getStatus());
        assertEquals("Approved by administrator", verifiedReq.getAdminRemarks());
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("CASE 2: Reject profile change -> Reject successful, profile unchanged")
    void test5_Case2_RejectProfileChange_LeavesProfileUnchanged() throws Exception {
        String originalFirstName = emp001.getFirstName();
        String requestedFirstName = "BrandNewFirstName";

        ProfileChangeRequest req = new ProfileChangeRequest();
        req.setEmployee(emp001);
        req.setFieldName("firstName");
        req.setCurrentValue(originalFirstName);
        req.setRequestedValue(requestedFirstName);
        req.setStatus(ProfileChangeStatus.PENDING);
        req.setRequestedAt(LocalDateTime.now());
        ProfileChangeRequest saved = profileChangeRequestRepository.save(req);

        // Frontend payload { "decisionReason": "Rejected by administrator" }
        String payload = """
        {
            "decisionReason": "Rejected by administrator"
        }
        """;

        mockMvc.perform(post("/api/admin/approvals/profile/" + saved.getId() + "/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.adminRemarks").value("Rejected by administrator"));

        // Verify employee profile remains unchanged
        Employee currentEmp = employeeRepository.findById(emp001.getId()).orElseThrow();
        assertEquals(originalFirstName, currentEmp.getFirstName(), "Employee firstName must remain unchanged upon rejection");

        // Verify request status in DB
        ProfileChangeRequest verifiedReq = profileChangeRequestRepository.findById(saved.getId()).orElseThrow();
        assertEquals(ProfileChangeStatus.REJECTED, verifiedReq.getStatus());
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("CASE 3: Admin page refresh -> Approved request does not return to Pending")
    void test6_Case3_AdminPageRefresh_ApprovedRequestNotInPending() throws Exception {
        // Create and approve request
        ProfileChangeRequest req = new ProfileChangeRequest();
        req.setEmployee(emp001);
        req.setFieldName("contactNumber");
        req.setCurrentValue("1111111111");
        req.setRequestedValue("2222222222");
        req.setStatus(ProfileChangeStatus.PENDING);
        req.setRequestedAt(LocalDateTime.now());
        ProfileChangeRequest saved = profileChangeRequestRepository.save(req);

        mockMvc.perform(post("/api/admin/approvals/profile/" + saved.getId() + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionReason\": \"Approved\"}"))
                .andExpect(status().isOk());

        // Refresh: Query all pending approvals
        mockMvc.perform(get("/api/admin/approvals/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileChanges[?(@.id == " + saved.getId() + ")]").doesNotExist());

        // Query pending profile requests directly
        mockMvc.perform(get("/api/admin/profile-change-requests/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + saved.getId() + ")]").doesNotExist());
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("CASE 4: Employee profile view -> Approved changes visible")
    void test7_Case4_EmployeeProfileView_ReflectsApprovedChanges() throws Exception {
        String newContact = "9887766554";

        ProfileChangeRequest req = new ProfileChangeRequest();
        req.setEmployee(emp001);
        req.setFieldName("contactNumber");
        req.setCurrentValue(emp001.getContactNumber() != null ? emp001.getContactNumber() : "123");
        req.setRequestedValue(newContact);
        req.setStatus(ProfileChangeStatus.PENDING);
        req.setRequestedAt(LocalDateTime.now());
        ProfileChangeRequest saved = profileChangeRequestRepository.save(req);

        // Approve with dual-format payload
        mockMvc.perform(post("/api/admin/approvals/profile/" + saved.getId() + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adminRemarks\": \"Approved\", \"decisionReason\": \"Approved\"}"))
                .andExpect(status().isOk());

        // View employee profile via API
        mockMvc.perform(get("/api/employees/" + emp001.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactNumber").value(newContact));
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("CASE 5: Leave Approval -> Verified working via Unified Approval Controller")
    void test8_Case5_LeaveApprovalWorking() throws Exception {
        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(emp001);
        leave.setStartDate(LocalDate.now().plusDays(10));
        leave.setEndDate(LocalDate.now().plusDays(12));
        leave.setReason("Annual family vacation");
        leave.setStatus(LeaveStatus.PENDING);
        leave.setRequestedAt(LocalDateTime.now());
        LeaveRequest saved = leaveRequestRepository.save(leave);

        String payload = """
        {
            "adminRemarks": "Leave granted by administrator",
            "decisionReason": "Leave granted by administrator"
        }
        """;

        mockMvc.perform(post("/api/admin/approvals/leave/" + saved.getId() + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.adminRemarks").value("Leave granted by administrator"));

        LeaveRequest updated = leaveRequestRepository.findById(saved.getId()).orElseThrow();
        assertEquals(LeaveStatus.APPROVED, updated.getStatus());
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("CASE 6: Preference Approval -> Verified working via Unified Approval Controller")
    void test9_Case6_PreferenceApprovalWorking() throws Exception {
        EmployeePreference pref = new EmployeePreference();
        pref.setEmployee(emp001);
        pref.setPreferredShiftTypes("MORNING");
        pref.setStatus(PreferenceStatus.PENDING);
        pref.setCreatedAt(LocalDateTime.now());
        EmployeePreference saved = preferenceRepository.save(pref);

        // Payload with decision and reviewNote as sent by Unified Approvals UI
        String payload = """
        {
            "status": "APPROVED",
            "decision": "APPROVE",
            "adminRemarks": "Shift preference approved",
            "reviewNote": "Shift preference approved"
        }
        """;

        mockMvc.perform(post("/api/admin/approvals/preference/" + saved.getId() + "/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.adminRemarks").value("Shift preference approved"));

        EmployeePreference updated = preferenceRepository.findById(saved.getId()).orElseThrow();
        assertEquals(PreferenceStatus.APPROVED, updated.getStatus());
    }
}
