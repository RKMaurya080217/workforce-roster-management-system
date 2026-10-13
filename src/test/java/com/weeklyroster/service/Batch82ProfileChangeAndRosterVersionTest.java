package com.weeklyroster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.dto.request.CreateProfileChangeRequest;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.RosterVersion;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.ProfileChangeRequestRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.RosterVersionRepository;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"wrms.dev.credential-mirror.enabled=false"})
public class Batch82ProfileChangeAndRosterVersionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ProfileChangeRequestRepository profileChangeRequestRepository;

    @Autowired
    private RosterCycleRepository rosterCycleRepository;

    @Autowired
    private RosterVersionRepository rosterVersionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Employee submits mobile change request using 'mobileNumber': succeeds and canonicalizes to contactNumber")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void testEmployeeSubmitMobileChangeRequest() throws Exception {
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("mobileNumber", "9911223344");

        mockMvc.perform(post("/api/profile-change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fieldName").value("contactNumber"))
                .andExpect(jsonPath("$.requestedValue").value("9911223344"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("Employee submits mobile change request with alias 'mobile': canonicalizes to contactNumber")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void testEmployeeSubmitMobileAlias() throws Exception {
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("mobile", "9988776655");

        mockMvc.perform(post("/api/profile-change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fieldName").value("contactNumber"))
                .andExpect(jsonPath("$.requestedValue").value("9988776655"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("Employee submits invalid mobile number (less than 7 digits): rejected with 400 Bad Request")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void testEmployeeSubmitInvalidMobileNumber() throws Exception {
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("mobileNumber", "12345");

        mockMvc.perform(post("/api/profile-change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Please enter a valid mobile number")));
    }

    @Test
    @DisplayName("Employee submits name change with 'fullName': canonicalizes to firstName")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void testEmployeeSubmitNameAlias() throws Exception {
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("fullName", "RajatKumar");

        mockMvc.perform(post("/api/profile-change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fieldName").value("firstName"))
                .andExpect(jsonPath("$.requestedValue").value("RajatKumar"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("Employee submits duplicate pending request: rejected with 400 Bad Request")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void testDuplicatePendingRequestRejected() throws Exception {
        CreateProfileChangeRequest req1 = new CreateProfileChangeRequest("contactNumber", "9911223344");
        mockMvc.perform(post("/api/profile-change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        CreateProfileChangeRequest req2 = new CreateProfileChangeRequest("mobileNumber", "9922334455");
        mockMvc.perform(post("/api/profile-change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("pending change request already exists")));
    }

    @Test
    @DisplayName("Employee submits identical value: rejected with 400 Bad Request")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void testIdenticalValueRejected() throws Exception {
        Employee emp = employeeRepository.findByUserUsernameIgnoreCase("emp001").orElseThrow();
        String currentContact = emp.getContactNumber() != null ? emp.getContactNumber() : "9123456780";

        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", currentContact);
        mockMvc.perform(post("/api/profile-change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("identical to current value")));
    }

    @Test
    @DisplayName("Case-insensitive username lookup works seamlessly (e.g. EMP001)")
    @WithMockUser(username = "EMP001", authorities = {"ROLE_EMPLOYEE"})
    void testCaseInsensitiveUsernameLookup() throws Exception {
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("contactNumber", "9998881122");

        mockMvc.perform(post("/api/profile-change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fieldName").value("contactNumber"));
    }

    @Test
    @DisplayName("Admin gets roster version by ID: /api/admin/roster-versions/{id} returns 200 OK with version data")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void testAdminGetRosterVersionById() throws Exception {
        RosterCycle cycle = rosterCycleRepository.findAll().stream().findFirst().orElseGet(() -> {
            RosterCycle c = new RosterCycle();
            c.setStartDate(LocalDate.now());
            c.setEndDate(LocalDate.now().plusDays(6));
            c.setGeneratedAt(LocalDateTime.now());
            c.setStatus(com.weeklyroster.entity.RosterStatus.TENTATIVE);
            return rosterCycleRepository.save(c);
        });

        RosterVersion v = new RosterVersion();
        v.setCycle(cycle);
        v.setVersionNumber(99);
        v.setAction("TEST_SNAPSHOT");
        v.setActionReason("Batch 82 verification snapshot");
        v.setCreatedTimestamp(LocalDateTime.now());
        v.setCreatedBy("admin");
        v.setStatus("DRAFT");
        v.setAffectedAssignmentsCount(42);
        v.setHealthScore(98);
        v.setSnapshotData("[{\"date\":\"2026-10-20\",\"employeeCode\":\"EMP001\",\"employeeName\":\"Rajat\",\"shiftType\":\"MORNING\"}]");
        RosterVersion saved = rosterVersionRepository.save(v);

        mockMvc.perform(get("/api/admin/roster-versions/" + saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(saved.getId()))
                .andExpect(jsonPath("$.versionNumber").value(99))
                .andExpect(jsonPath("$.action").value("TEST_SNAPSHOT"))
                .andExpect(jsonPath("$.healthScore").value(98))
                .andExpect(jsonPath("$.snapshotData", containsString("EMP001")));
    }

    @Test
    @DisplayName("Admin gets non-existent roster version by ID: returns 404 Not Found")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void testAdminGetRosterVersionByIdNotFound() throws Exception {
        mockMvc.perform(get("/api/admin/roster-versions/999999"))
                .andExpect(status().isNotFound());
    }
}
