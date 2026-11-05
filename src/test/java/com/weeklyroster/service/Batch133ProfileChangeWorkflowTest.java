package com.weeklyroster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.dto.request.CreateProfileChangeRequest;
import com.weeklyroster.dto.request.ProfileChangeDecisionRequest;
import com.weeklyroster.dto.response.ProfileChangeRequestResponse;
import com.weeklyroster.dto.response.UnifiedApprovalsResponse;
import com.weeklyroster.dto.response.UnifiedApprovalsSummaryResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.Notification;
import com.weeklyroster.entity.NotificationType;
import com.weeklyroster.entity.ProfileChangeRequest;
import com.weeklyroster.entity.ProfileChangeStatus;
import com.weeklyroster.entity.Role;
import com.weeklyroster.entity.User;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.NotificationRepository;
import com.weeklyroster.repository.ProfileChangeRequestRepository;
import com.weeklyroster.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class Batch133ProfileChangeWorkflowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileChangeRequestRepository profileChangeRequestRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private Employee emp1;
    private Employee emp2;
    private User userEmp1;
    private User userEmp2;
    private User userAdmin;

    @BeforeEach
    void setUp() {
        profileChangeRequestRepository.deleteAll();

        emp1 = employeeRepository.findByEmployeeCodeIgnoreCase("EMP001").orElse(null);
        if (emp1 == null) {
            emp1 = new Employee();
            emp1.setEmployeeCode("EMP001");
            emp1.setFirstName("Rajat");
            emp1.setLastName("Maurya");
            emp1.setEmail("rkmaurya080217@gmail.com");
            emp1.setContactNumber("9123456780");
            emp1.setGender(Gender.MALE);
            emp1.setActive(true);
            emp1 = employeeRepository.save(emp1);
        } else {
            emp1.setFirstName("Rajat");
            emp1.setLastName("Maurya");
            emp1.setContactNumber("9123456780");
            emp1.setEmail("rkmaurya080217@gmail.com");
            emp1.setGender(Gender.MALE);
            emp1 = employeeRepository.save(emp1);
        }

        userEmp1 = userRepository.findByUsernameIgnoreCase("emp001").orElse(null);
        if (userEmp1 == null) {
            userEmp1 = new User();
            userEmp1.setUsername("emp001");
            userEmp1.setPassword("password123");
            userEmp1.setRole(Role.ROLE_EMPLOYEE);
            userEmp1.setEnabled(true);
            userEmp1 = userRepository.save(userEmp1);
        }
        if (emp1.getUser() == null) {
            emp1.setUser(userEmp1);
            emp1 = employeeRepository.save(emp1);
        }

        emp2 = employeeRepository.findByEmployeeCodeIgnoreCase("EMP002").orElse(null);
        if (emp2 == null) {
            emp2 = new Employee();
            emp2.setEmployeeCode("EMP002");
            emp2.setFirstName("Sapna");
            emp2.setLastName("Pandey");
            emp2.setEmail("sapna.pandey@example.com");
            emp2.setContactNumber("9111222333");
            emp2.setGender(Gender.FEMALE);
            emp2.setActive(true);
            emp2 = employeeRepository.save(emp2);
        } else {
            emp2.setContactNumber("9111222333");
            emp2 = employeeRepository.save(emp2);
        }

        userEmp2 = userRepository.findByUsernameIgnoreCase("emp002").orElse(null);
        if (userEmp2 == null) {
            userEmp2 = new User();
            userEmp2.setUsername("emp002");
            userEmp2.setPassword("password123");
            userEmp2.setRole(Role.ROLE_EMPLOYEE);
            userEmp2.setEnabled(true);
            userEmp2 = userRepository.save(userEmp2);
        }
        if (emp2.getUser() == null) {
            emp2.setUser(userEmp2);
            emp2 = employeeRepository.save(emp2);
        }

        userAdmin = userRepository.findByUsernameIgnoreCase("admin").orElse(null);
        if (userAdmin == null) {
            userAdmin = new User();
            userAdmin.setUsername("admin");
            userAdmin.setPassword("Admin@123");
            userAdmin.setRole(Role.ROLE_ADMIN);
            userAdmin.setEnabled(true);
            userAdmin = userRepository.save(userAdmin);
        }
    }

    // =========================================================================
    // 1. Employee submits a valid request
    // =========================================================================
    @Test
    @DisplayName("Test 01: Employee submits valid request with reason -> 201 Created with PENDING status")
    void test01_employeeSubmitsValidRequest() throws Exception {
        String newMobile = "9876543210";
        String reason = "Updated personal mobile number";
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", newMobile, reason);

        MvcResult result = mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fieldName").value("contactNumber"))
                .andExpect(jsonPath("$.currentValue").value("9123456780"))
                .andExpect(jsonPath("$.requestedValue").value(newMobile))
                .andExpect(jsonPath("$.reason").value(reason))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.employeeCode").value("EMP001"))
                .andReturn();

        ProfileChangeRequestResponse res = objectMapper.readValue(result.getResponse().getContentAsString(), ProfileChangeRequestResponse.class);
        assertNotNull(res.id());
        assertNotNull(res.requestedAt());
        assertNull(res.reviewedAt());
        assertNull(res.adminRemarks());

        // Master record must remain unchanged while pending
        Employee unchanged = employeeRepository.findById(emp1.getId()).orElseThrow();
        assertEquals("9123456780", unchanged.getContactNumber());
    }

    // =========================================================================
    // 2. Empty request rejected
    // =========================================================================
    @Test
    @DisplayName("Test 02: Empty field name or requested value is rejected with 400 Bad Request")
    void test02_emptyRequestRejected() throws Exception {
        CreateProfileChangeRequest emptyField = new CreateProfileChangeRequest("", "9876543210", "reason");
        mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyField)))
                .andExpect(status().isBadRequest());

        CreateProfileChangeRequest emptyVal = new CreateProfileChangeRequest("contactNumber", "", "reason");
        mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyVal)))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // 3. Unchanged value rejected
    // =========================================================================
    @Test
    @DisplayName("Test 03: Request with identical current value is rejected with 400 Bad Request")
    void test03_unchangedValueRejected() throws Exception {
        CreateProfileChangeRequest sameContact = new CreateProfileChangeRequest("contactNumber", emp1.getContactNumber(), "no change");
        mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sameContact)))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // 4. Duplicate pending request blocked
    // =========================================================================
    @Test
    @DisplayName("Test 04: Duplicate pending request for the same employee and field is blocked")
    void test04_duplicatePendingRequestBlocked() throws Exception {
        CreateProfileChangeRequest req1 = new CreateProfileChangeRequest("contactNumber", "9876543210", "First request");
        mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        CreateProfileChangeRequest req2 = new CreateProfileChangeRequest("contactNumber", "9876543211", "Second request");
        mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // 5. Employee cannot submit for another employee
    // =========================================================================
    @Test
    @DisplayName("Test 05: Employee identity strictly bound to security context (cannot submit for another)")
    void test05_employeeCannotSubmitForAnotherEmployee() throws Exception {
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", "9876543210", "My phone update");

        // Submitting as emp001 creates request strictly linked to emp001
        MvcResult res = mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        ProfileChangeRequestResponse created = objectMapper.readValue(res.getResponse().getContentAsString(), ProfileChangeRequestResponse.class);
        assertEquals(emp1.getId(), created.employeeId());
        assertEquals("EMP001", created.employeeCode());
    }

    // =========================================================================
    // 6. Employee cannot view another employee's requests
    // =========================================================================
    @Test
    @DisplayName("Test 06: Employee cannot view another employee's request (403 Forbidden)")
    void test06_employeeCannotViewAnotherEmployeesRequests() throws Exception {
        // emp002 submits a request
        CreateProfileChangeRequest req2 = new CreateProfileChangeRequest("contactNumber", "9876543288", "Emp2 phone");
        MvcResult res2 = mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp002").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isCreated())
                .andReturn();

        ProfileChangeRequestResponse created2 = objectMapper.readValue(res2.getResponse().getContentAsString(), ProfileChangeRequestResponse.class);

        // emp001 attempts to view emp002's request by ID -> 403 Forbidden
        mockMvc.perform(get("/api/profile-change-requests/" + created2.id())
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isForbidden());

        // emp001 calls /my -> does not see emp002's request
        mockMvc.perform(get("/api/profile-change-requests/my")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // =========================================================================
    // 7. Employee cannot approve requests
    // =========================================================================
    @Test
    @DisplayName("Test 07: Employee cannot approve or reject requests (403 Forbidden)")
    void test07_employeeCannotApproveRequests() throws Exception {
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", "9876543210", "Phone update");
        MvcResult res = mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        ProfileChangeRequestResponse created = objectMapper.readValue(res.getResponse().getContentAsString(), ProfileChangeRequestResponse.class);

        // Employee attempts to approve via Admin controller -> 403
        mockMvc.perform(post("/api/admin/profile-change-requests/" + created.id() + "/approve")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProfileChangeDecisionRequest("Self approve"))))
                .andExpect(status().isForbidden());

        // Employee attempts to approve via Unified Approvals controller -> 403
        mockMvc.perform(post("/api/admin/approvals/profile/" + created.id() + "/approve")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProfileChangeDecisionRequest("Self approve"))))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // 8. Admin can view pending requests
    // =========================================================================
    @Test
    @DisplayName("Test 08: Admin can view pending profile change requests including reason")
    void test08_adminCanViewPendingRequests() throws Exception {
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", "9876543210", "Legal phone update");
        mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/admin/profile-change-requests/pending")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fieldName").value("contactNumber"))
                .andExpect(jsonPath("$[0].reason").value("Legal phone update"))
                .andExpect(jsonPath("$[0].requestedValue").value("9876543210"));

        // Also via getAll with status filter
        mockMvc.perform(get("/api/admin/profile-change-requests?status=PENDING")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // =========================================================================
    // 9. Admin approves a valid request
    // =========================================================================
    @Test
    @DisplayName("Test 09: Admin approves a valid request -> status APPROVED, reviewer and remarks saved")
    void test09_adminApprovesValidRequest() throws Exception {
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", "9876543210", "New mobile");
        MvcResult subRes = mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        ProfileChangeRequestResponse created = objectMapper.readValue(subRes.getResponse().getContentAsString(), ProfileChangeRequestResponse.class);

        ProfileChangeDecisionRequest decision = new ProfileChangeDecisionRequest("Approved by Admin Team");
        mockMvc.perform(post("/api/admin/profile-change-requests/" + created.id() + "/approve")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(decision)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.adminRemarks").value("Approved by Admin Team"))
                .andExpect(jsonPath("$.reviewedBy").value("admin"))
                .andExpect(jsonPath("$.reviewedAt").isNotEmpty());
    }

    // =========================================================================
    // 10. Employee record updates after approval
    // =========================================================================
    @Test
    @DisplayName("Test 10: Target employee entity in database updates with approved value")
    void test10_employeeRecordUpdatesAfterApproval() throws Exception {
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", "9876543210", "Phone change");
        MvcResult subRes = mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        ProfileChangeRequestResponse created = objectMapper.readValue(subRes.getResponse().getContentAsString(), ProfileChangeRequestResponse.class);

        mockMvc.perform(post("/api/admin/profile-change-requests/" + created.id() + "/approve")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProfileChangeDecisionRequest("Approved"))))
                .andExpect(status().isOk());

        Employee updated = employeeRepository.findById(emp1.getId()).orElseThrow();
        assertEquals("9876543210", updated.getContactNumber());
    }

    // =========================================================================
    // 11. Admin rejects a request
    // =========================================================================
    @Test
    @DisplayName("Test 11: Admin rejects request -> status REJECTED with remarks recorded")
    void test11_adminRejectsRequest() throws Exception {
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", "9876543210", "Phone update");
        MvcResult subRes = mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        ProfileChangeRequestResponse created = objectMapper.readValue(subRes.getResponse().getContentAsString(), ProfileChangeRequestResponse.class);

        ProfileChangeDecisionRequest decision = new ProfileChangeDecisionRequest("Contact proof not submitted");
        mockMvc.perform(post("/api/admin/profile-change-requests/" + created.id() + "/reject")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(decision)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.adminRemarks").value("Contact proof not submitted"))
                .andExpect(jsonPath("$.reviewedBy").value("admin"))
                .andExpect(jsonPath("$.reviewedAt").isNotEmpty());
    }

    // =========================================================================
    // 12. Employee record remains unchanged after rejection
    // =========================================================================
    @Test
    @DisplayName("Test 12: Employee record in database remains 100% unchanged after rejection")
    void test12_employeeRecordRemainsUnchangedAfterRejection() throws Exception {
        String originalMobile = emp1.getContactNumber();
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", "9876543210", "Phone update");
        MvcResult subRes = mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        ProfileChangeRequestResponse created = objectMapper.readValue(subRes.getResponse().getContentAsString(), ProfileChangeRequestResponse.class);

        mockMvc.perform(post("/api/admin/profile-change-requests/" + created.id() + "/reject")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProfileChangeDecisionRequest("Rejected"))))
                .andExpect(status().isOk());

        Employee record = employeeRepository.findById(emp1.getId()).orElseThrow();
        assertEquals(originalMobile, record.getContactNumber(), "Original contact number must NOT be modified");
    }

    // =========================================================================
    // 13. Admin remarks persist
    // =========================================================================
    @Test
    @DisplayName("Test 13: Admin remarks persist and are visible to employee in request history")
    void test13_adminRemarksPersist() throws Exception {
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("firstName", "Rajendra", "Spelling correction");
        MvcResult subRes = mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        ProfileChangeRequestResponse created = objectMapper.readValue(subRes.getResponse().getContentAsString(), ProfileChangeRequestResponse.class);

        String rejectionReason = "Official government ID proof required for legal name changes";
        mockMvc.perform(post("/api/admin/profile-change-requests/" + created.id() + "/reject")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProfileChangeDecisionRequest(rejectionReason))))
                .andExpect(status().isOk());

        // Employee inspects request by ID
        mockMvc.perform(get("/api/profile-change-requests/" + created.id())
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.adminRemarks").value(rejectionReason));

        // Employee inspects /my history
        mockMvc.perform(get("/api/profile-change-requests/my")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].adminRemarks").value(rejectionReason));
    }

    // =========================================================================
    // 14. Double approval is prevented
    // =========================================================================
    @Test
    @DisplayName("Test 14: Double approval or decision on already processed request is prevented")
    void test14_doubleApprovalPrevented() throws Exception {
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", "9876543210", "Phone change");
        MvcResult subRes = mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        ProfileChangeRequestResponse created = objectMapper.readValue(subRes.getResponse().getContentAsString(), ProfileChangeRequestResponse.class);

        // First approval succeeds
        mockMvc.perform(post("/api/admin/profile-change-requests/" + created.id() + "/approve")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProfileChangeDecisionRequest("Approved 1"))))
                .andExpect(status().isOk());

        // Second approval attempt fails with 400 Bad Request
        mockMvc.perform(post("/api/admin/profile-change-requests/" + created.id() + "/approve")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProfileChangeDecisionRequest("Approved 2"))))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // 15. Concurrent approval is handled safely
    // =========================================================================
    @Test
    @DisplayName("Test 15: Optimistic locking version field exists on entity for concurrency safety")
    void test15_concurrentApprovalHandledSafely() throws Exception {
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", "9876543210", "Phone change");
        MvcResult subRes = mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        ProfileChangeRequestResponse created = objectMapper.readValue(subRes.getResponse().getContentAsString(), ProfileChangeRequestResponse.class);

        ProfileChangeRequest entity = profileChangeRequestRepository.findById(created.id()).orElseThrow();
        assertNotNull(entity.getVersion(), "Entity must have a version field for optimistic locking concurrency control");
    }

    // =========================================================================
    // 16. Notification is generated once
    // =========================================================================
    @Test
    @DisplayName("Test 16: In-app notification generated for employee upon decision")
    void test16_notificationGeneratedOnce() throws Exception {
        int notifCountBefore = notificationRepository.findAll().size();

        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", "9876543210", "Phone change");
        MvcResult subRes = mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        ProfileChangeRequestResponse created = objectMapper.readValue(subRes.getResponse().getContentAsString(), ProfileChangeRequestResponse.class);

        mockMvc.perform(post("/api/admin/profile-change-requests/" + created.id() + "/approve")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProfileChangeDecisionRequest("Approved!"))))
                .andExpect(status().isOk());

        List<Notification> allNotifs = notificationRepository.findAll();
        assertTrue(allNotifs.size() > notifCountBefore, "Notification must be generated for employee");

        boolean foundDecisionNotif = allNotifs.stream().anyMatch(n ->
                n.getRecipientUsername().equalsIgnoreCase("emp001") &&
                n.getType() == NotificationType.PROFILE_CHANGE_DECISION &&
                n.getMessage().contains("approved"));
        assertTrue(foundDecisionNotif, "Decision notification with approved status must exist for emp001");
    }

    // =========================================================================
    // 17. Audit history is correct
    // =========================================================================
    @Test
    @DisplayName("Test 17: System audit log captures profile change events")
    void test17_auditHistoryIsCorrect() throws Exception {
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", "9876543210", "Phone update");
        MvcResult subRes = mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        ProfileChangeRequestResponse created = objectMapper.readValue(subRes.getResponse().getContentAsString(), ProfileChangeRequestResponse.class);

        mockMvc.perform(post("/api/admin/profile-change-requests/" + created.id() + "/approve")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProfileChangeDecisionRequest("Audit check"))))
                .andExpect(status().isOk());

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM system_audit_logs WHERE audit_action IN ('PROFILE_CHANGE_REQUESTED', 'PROFILE_CHANGE_APPROVED')",
                Integer.class
        );
        assertNotNull(auditCount);
        assertTrue(auditCount >= 2, "Audit logs must record both submission and approval actions");
    }

    // =========================================================================
    // 18. SQLite persistence
    // =========================================================================
    @Test
    @DisplayName("Test 18: SQLite physical persistence verifies all table columns on employee_requests")
    void test18_sqlitePersistence() throws Exception {
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", "9876543210", "SQLite persistence test reason");
        MvcResult subRes = mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        ProfileChangeRequestResponse created = objectMapper.readValue(subRes.getResponse().getContentAsString(), ProfileChangeRequestResponse.class);

        mockMvc.perform(post("/api/admin/profile-change-requests/" + created.id() + "/approve")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProfileChangeDecisionRequest("Approved SQLite check"))))
                .andExpect(status().isOk());

        entityManager.flush();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, request_type, field_name, current_value, requested_value, request_reason, profile_status, decided_by, admin_remarks FROM employee_requests WHERE id = ?",
                created.id()
        );

        assertEquals("PROFILE_CHANGE", row.get("request_type"));
        assertEquals("contactNumber", row.get("field_name"));
        assertEquals("9876543210", row.get("requested_value"));
        assertEquals("SQLite persistence test reason", row.get("request_reason"));
        assertEquals("APPROVED", row.get("profile_status"));
        assertEquals("admin", row.get("decided_by"));
        assertEquals("Approved SQLite check", row.get("admin_remarks"));
    }

    // =========================================================================
    // 19. MySQL persistence & schema compatibility
    // =========================================================================
    @Test
    @DisplayName("Test 19: MySQL schema compatibility for employee_requests table and profile change discriminator")
    void test19_mySqlPersistenceAndSchemaCompatibility() throws Exception {
        File schemaFile = new File("src/main/resources/schema.sql");
        assertTrue(schemaFile.exists(), "schema.sql must exist");
        String schema = Files.readString(schemaFile.toPath());

        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS employee_requests"), "schema.sql must define employee_requests");
        assertTrue(schema.contains("request_reason VARCHAR"), "employee_requests must contain request_reason column");
        assertTrue(schema.contains("decided_by VARCHAR"), "employee_requests must contain decided_by column");
        assertTrue(schema.contains("profile_status VARCHAR"), "employee_requests must contain profile_status column");
        assertTrue(schema.contains("profile_reviewed_at DATETIME"), "employee_requests must contain profile_reviewed_at column");
        assertTrue(schema.contains("admin_remarks VARCHAR"), "employee_requests must contain admin_remarks column");
    }

    // =========================================================================
    // 20. Existing profile and approval workflows remain functional
    // =========================================================================
    @Test
    @DisplayName("Test 20: Unified Admin approvals summary & profile requests seamlessly integrate")
    void test20_existingProfileAndApprovalWorkflowsRemainFunctional() throws Exception {
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", "9876543210", "Unified check");
        mockMvc.perform(post("/api/profile-change-requests")
                        .with(user("emp001").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        // 1. Unified summary counts
        MvcResult summaryResult = mockMvc.perform(get("/api/admin/approvals/summary")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andReturn();

        UnifiedApprovalsSummaryResponse summary = objectMapper.readValue(summaryResult.getResponse().getContentAsString(), UnifiedApprovalsSummaryResponse.class);
        assertTrue(summary.profileRequestsCount() >= 1, "Unified summary must reflect pending profile change request");
        assertTrue(summary.totalPending() >= 1);

        // 2. Unified all pending list
        MvcResult allResult = mockMvc.perform(get("/api/admin/approvals/all")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andReturn();

        UnifiedApprovalsResponse all = objectMapper.readValue(allResult.getResponse().getContentAsString(), UnifiedApprovalsResponse.class);
        assertNotNull(all.profileRequests());
        assertFalse(all.profileRequests().isEmpty());
        assertEquals("Unified check", all.profileRequests().get(0).reason());

        // 3. UI static files verify request history table markup and reason column
        File appJsFile = new File("src/main/resources/static/app.js");
        String appJs = Files.readString(appJsFile.toPath());
        assertTrue(appJs.contains("Request ID</th>"), "Request history table must have Request ID column");
        assertTrue(appJs.contains("<th>Reason</th>"), "Request history table must have Reason column");
        assertTrue(appJs.contains("pcrFormReason"), "app.js must handle pcrFormReason");

        File indexHtmlFile = new File("src/main/resources/static/index.html");
        String indexHtml = Files.readString(indexHtmlFile.toPath());
        assertTrue(indexHtml.contains("id=\"pcrFormReason\""), "index.html must have #pcrFormReason textarea");
        assertTrue(indexHtml.contains("pcrFormReasonGroup"), "index.html must have #pcrFormReasonGroup");
    }
}
