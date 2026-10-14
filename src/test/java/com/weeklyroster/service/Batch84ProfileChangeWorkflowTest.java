package com.weeklyroster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.dto.request.CreateProfileChangeRequest;
import com.weeklyroster.dto.request.ProfileChangeDecisionRequest;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.ProfileChangeRequest;
import com.weeklyroster.entity.ProfileChangeStatus;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.ProfileChangeRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public class Batch84ProfileChangeWorkflowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ProfileChangeRequestRepository profileChangeRequestRepository;

    private Employee testEmployee;

    @BeforeEach
    void setUp() {
        testEmployee = employeeRepository.findByUserUsernameIgnoreCase("emp001").orElseGet(() -> {
            List<Employee> all = employeeRepository.findAll();
            assertFalse(all.isEmpty(), "At least one employee must exist in the database");
            return all.get(0);
        });
        testEmployee.setContactNumber("9123456780");
        testEmployee.setFirstName("Rajat");
        testEmployee.setLastName("Maurya");
        employeeRepository.save(testEmployee);

        profileChangeRequestRepository.deleteAll();
    }

    @Test
    @DisplayName("1. Mobile Number Change: Employee submits valid request -> Admin approves -> Employee profile updated")
    void testMobileNumberChangeWorkflow() throws Exception {
        String newMobile = "9876543210";
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", newMobile);

        // Step 1: Employee submits
        mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fieldName").value("contactNumber"))
                .andExpect(jsonPath("$.currentValue").value("9123456780"))
                .andExpect(jsonPath("$.requestedValue").value(newMobile))
                .andExpect(jsonPath("$.status").value("PENDING"));

        List<ProfileChangeRequest> pending = profileChangeRequestRepository.findByStatusOrderByRequestedAtAsc(ProfileChangeStatus.PENDING);
        assertEquals(1, pending.size());
        ProfileChangeRequest pcr = pending.get(0);
        assertEquals(newMobile, pcr.getRequestedValue());

        // Step 2: Admin views pending
        mockMvc.perform(get("/api/admin/profile-change-requests/pending")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(pcr.getId()))
                .andExpect(jsonPath("$[0].fieldName").value("contactNumber"))
                .andExpect(jsonPath("$[0].requestedValue").value(newMobile));

        // Step 3: Admin approves
        ProfileChangeDecisionRequest decision = new ProfileChangeDecisionRequest("Approved by Admin");
        mockMvc.perform(post("/api/admin/profile-change-requests/" + pcr.getId() + "/approve")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(decision)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // Step 4: Verify employee master data updated
        Employee updated = employeeRepository.findById(testEmployee.getId()).orElseThrow();
        assertEquals(newMobile, updated.getContactNumber(), "Employee contact number must reflect the approved value");
    }

    @Test
    @DisplayName("2. Rejection Flow: Admin rejects mobile number change -> Employee profile remains unchanged")
    void testRejectionFlowPreservesEmployeeData() throws Exception {
        String originalMobile = testEmployee.getContactNumber();
        String proposedMobile = "9998887776";
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", proposedMobile);

        mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        ProfileChangeRequest pcr = profileChangeRequestRepository.findByStatusOrderByRequestedAtAsc(ProfileChangeStatus.PENDING).get(0);

        // Admin rejects
        ProfileChangeDecisionRequest decision = new ProfileChangeDecisionRequest("Documentation incomplete");
        mockMvc.perform(post("/api/admin/profile-change-requests/" + pcr.getId() + "/reject")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(decision)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        // Verify employee master data is NOT modified
        Employee unchanged = employeeRepository.findById(testEmployee.getId()).orElseThrow();
        assertEquals(originalMobile, unchanged.getContactNumber(), "Employee contact number must NOT be modified on rejection");
    }

    @Test
    @DisplayName("3. Validation: Reject invalid mobile number (< 7 digits)")
    void testInvalidMobileNumberRejected() throws Exception {
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", "12345");

        mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("4. Validation: Reject identical value request")
    void testIdenticalValueRejected() throws Exception {
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", testEmployee.getContactNumber());

        mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("5. Duplicate Prevention: Cannot submit second pending request for same field")
    void testDuplicatePendingRequestRejected() throws Exception {
        CreateProfileChangeRequest req1 = new CreateProfileChangeRequest("contactNumber", "9876543210");
        mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        CreateProfileChangeRequest req2 = new CreateProfileChangeRequest("contactNumber", "9876543211");
        mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("6. Second Editable Field: First Name change approved and updated")
    void testFirstNameChangeWorkflow() throws Exception {
        String newFirstName = "Rajat Test";
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("firstName", newFirstName);

        mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fieldName").value("firstName"))
                .andExpect(jsonPath("$.requestedValue").value(newFirstName));

        ProfileChangeRequest pcr = profileChangeRequestRepository.findByStatusOrderByRequestedAtAsc(ProfileChangeStatus.PENDING).get(0);

        mockMvc.perform(post("/api/admin/profile-change-requests/" + pcr.getId() + "/approve")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProfileChangeDecisionRequest("Approved"))))
                .andExpect(status().isOk());

        Employee updated = employeeRepository.findById(testEmployee.getId()).orElseThrow();
        assertEquals(newFirstName, updated.getFirstName());
    }

    @Test
    @DisplayName("7. Security: Unauthenticated request is rejected with 401")
    void testUnauthenticatedRequestRejected() throws Exception {
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", "9876543210");

        mockMvc.perform(post("/api/profile-change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("8. Security: Employee cannot access admin approval endpoints")
    void testEmployeeCannotAccessAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/profile-change-requests/pending")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isForbidden());
    }
}
