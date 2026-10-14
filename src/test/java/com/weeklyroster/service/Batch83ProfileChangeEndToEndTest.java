package com.weeklyroster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.dto.request.CreateProfileChangeRequest;
import com.weeklyroster.dto.request.ProfileChangeDecisionRequest;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.ProfileChangeRequest;
import com.weeklyroster.entity.ProfileChangeStatus;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.ProfileChangeRequestRepository;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"wrms.dev.credential-mirror.enabled=false"})
public class Batch83ProfileChangeEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ProfileChangeRequestRepository profileChangeRequestRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("1. Mobile Number Change Request End-To-End: Employee submit -> Pending in DB -> Admin see -> Admin approve -> Employee record updated")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void testMobileNumberChangeEndToEndLifecycle() throws Exception {
        Employee emp = employeeRepository.findByUserUsernameIgnoreCase("emp001").orElseThrow();
        String originalPhone = emp.getContactNumber();
        String newMobileNumber = "9988776655";
        if (newMobileNumber.equals(originalPhone)) {
            newMobileNumber = "9911223344";
        }

        // STEP 1: Employee submits profile change request for mobileNumber
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("mobileNumber", newMobileNumber);
        String responseJson = mockMvc.perform(post("/api/profile-change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fieldName").value("contactNumber"))
                .andExpect(jsonPath("$.requestedValue").value(newMobileNumber))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        Long requestId = objectMapper.readTree(responseJson).get("id").asLong();
        assertNotNull(requestId);

        // STEP 2: Verify request is actually persisted in database with PENDING status
        ProfileChangeRequest inDb = profileChangeRequestRepository.findById(requestId).orElseThrow();
        assertEquals(ProfileChangeStatus.PENDING, inDb.getStatus());
        assertEquals("contactNumber", inDb.getFieldName());
        assertEquals(newMobileNumber, inDb.getRequestedValue());
        assertEquals(emp.getId(), inDb.getEmployee().getId());

        // STEP 3: Admin sees pending request in /api/admin/profile-change-requests/pending
        mockMvc.perform(get("/api/admin/profile-change-requests/pending")
                        .with(user("admin").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(requestId.intValue())))
                .andExpect(jsonPath("$[*].fieldName", hasItem("contactNumber")))
                .andExpect(jsonPath("$[*].requestedValue", hasItem(newMobileNumber)));

        // STEP 4: Admin also sees pending request in unified approvals /api/admin/approvals/all
        mockMvc.perform(get("/api/admin/approvals/all")
                        .with(user("admin").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileRequests[*].id", hasItem(requestId.intValue())))
                .andExpect(jsonPath("$.profileRequests[*].requestedValue", hasItem(newMobileNumber)));

        // STEP 5: Admin approves the request
        ProfileChangeDecisionRequest decision = new ProfileChangeDecisionRequest("Verified employee contact update");
        mockMvc.perform(post("/api/admin/profile-change-requests/" + requestId + "/approve")
                        .with(user("admin").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(decision)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.adminRemarks").value("Verified employee contact update"));


        // STEP 6: Verify Employee master record in database is actually updated
        Employee updatedEmp = employeeRepository.findById(emp.getId()).orElseThrow();
        assertEquals(newMobileNumber, updatedEmp.getContactNumber(), "Employee contact number must reflect approved value");

        // STEP 7: Verify request in DB has status APPROVED
        ProfileChangeRequest approvedDb = profileChangeRequestRepository.findById(requestId).orElseThrow();
        assertEquals(ProfileChangeStatus.APPROVED, approvedDb.getStatus());
    }

    @Test
    @DisplayName("2. Mobile Number Validation: Rejects invalid format or fewer than 7 digits")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void testMobileNumberValidation() throws Exception {
        // Less than 7 digits
        CreateProfileChangeRequest shortReq = new CreateProfileChangeRequest("mobileNumber", "12345");
        mockMvc.perform(post("/api/profile-change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(shortReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Please enter a valid mobile number")));

        // Invalid characters
        CreateProfileChangeRequest invalidReq = new CreateProfileChangeRequest("mobileNumber", "invalid-phone!@#");
        mockMvc.perform(post("/api/profile-change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Please enter a valid mobile number")));
    }

    @Test
    @DisplayName("3. Mobile Number Identical Value: Rejects when requested value matches current")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void testMobileNumberIdenticalValue() throws Exception {
        Employee emp = employeeRepository.findByUserUsernameIgnoreCase("emp001").orElseThrow();
        String currentContact = emp.getContactNumber() != null ? emp.getContactNumber() : "9123456780";

        CreateProfileChangeRequest req = new CreateProfileChangeRequest("mobileNumber", currentContact);
        mockMvc.perform(post("/api/profile-change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("identical to current value")));
    }

    @Test
    @DisplayName("4. Prevent Duplicate Submissions: Rejects second pending request for same field")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void testPreventDuplicatePendingSubmissions() throws Exception {
        CreateProfileChangeRequest req1 = new CreateProfileChangeRequest("mobile", "9944332211");
        mockMvc.perform(post("/api/profile-change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        CreateProfileChangeRequest req2 = new CreateProfileChangeRequest("contactNumber", "9955443322");
        mockMvc.perform(post("/api/profile-change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("pending change request already exists")));
    }

    @Test
    @DisplayName("5. JSON Payload Aliases: Accepts {field: ..., value: ...} format smoothly")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void testJsonPayloadAliases() throws Exception {
        String jsonPayload = """
                {
                    "field": "mobile_number",
                    "value": "9977665544",
                    "extraIgnoredField": "test"
                }
                """;

        mockMvc.perform(post("/api/profile-change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fieldName").value("contactNumber"))
                .andExpect(jsonPath("$.requestedValue").value("9977665544"));
    }

    @Test
    @DisplayName("6. Admin Reject Profile Change: Marks status REJECTED without modifying employee record")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void testAdminRejectProfileChange() throws Exception {
        Employee emp = employeeRepository.findByUserUsernameIgnoreCase("emp001").orElseThrow();
        String originalPhone = emp.getContactNumber();

        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", "9966554433");
        String res = mockMvc.perform(post("/api/profile-change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long id = objectMapper.readTree(res).get("id").asLong();

        ProfileChangeDecisionRequest rejectDecision = new ProfileChangeDecisionRequest("Documentation not verified");
        mockMvc.perform(post("/api/admin/profile-change-requests/" + id + "/reject")
                        .with(user("admin").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rejectDecision)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.adminRemarks").value("Documentation not verified"));


        Employee unchangedEmp = employeeRepository.findById(emp.getId()).orElseThrow();
        assertEquals(originalPhone, unchangedEmp.getContactNumber(), "Original phone number must remain untouched on rejection");
    }

    @Test
    @DisplayName("7. Employee views own requests: /api/profile-change-requests/my returns employee's requests")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void testEmployeeViewsOwnRequests() throws Exception {
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", "9933221100");
        mockMvc.perform(post("/api/profile-change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/profile-change-requests/my"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", not(empty())))
                .andExpect(jsonPath("$[0].fieldName").value("contactNumber"))
                .andExpect(jsonPath("$[0].requestedValue").value("9933221100"));
    }
}
