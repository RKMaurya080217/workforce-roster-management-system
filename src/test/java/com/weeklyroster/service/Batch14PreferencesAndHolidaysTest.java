package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.weeklyroster.dto.request.PreferenceDecisionRequest;
import com.weeklyroster.dto.request.PreferenceSubmitRequest;
import com.weeklyroster.dto.response.PreferenceResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.PreferenceStatus;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.EmployeeRepository;
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
import java.util.List;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public class Batch14PreferencesAndHolidaysTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmployeePreferenceService preferenceService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Test
    @DisplayName("1. Employee Preference: Create preference")
    void testCreatePreference() {
        Employee emp = employeeRepository.findAll().get(0);
        PreferenceSubmitRequest req = new PreferenceSubmitRequest(
                "MORNING, GENERAL",
                "SUNDAY",
                "MONDAY to FRIDAY",
                "NIGHT",
                "Daytime preference for study",
                "Family commitments",
                LocalDate.now(),
                LocalDate.now().plusMonths(1)
        );

        PreferenceResponse res = preferenceService.submitPreference(emp.getId(), req, emp.getUser().getUsername());
        assertNotNull(res);
        assertNotNull(res.id());
        assertEquals("MORNING, GENERAL", res.preferredShiftTypes());
        assertEquals(PreferenceStatus.PENDING, res.status());
    }

    @Test
    @DisplayName("2. Employee Preference: Read own preference")
    void testReadOwnPreference() {
        Employee emp = employeeRepository.findAll().get(0);
        List<PreferenceResponse> list = preferenceService.getMyPreferences(emp.getId());
        assertNotNull(list);
    }

    @Test
    @DisplayName("3. Employee Preference: Update own preference")
    void testUpdateOwnPreference() {
        Employee emp = employeeRepository.findAll().get(0);
        PreferenceSubmitRequest req1 = new PreferenceSubmitRequest("GENERAL", "SUNDAY", null, null, null, null, null, null);
        PreferenceResponse created = preferenceService.submitPreference(emp.getId(), req1, emp.getUser().getUsername());

        PreferenceSubmitRequest req2 = new PreferenceSubmitRequest("MORNING", "SATURDAY", null, null, "Updated note", null, null, null);
        PreferenceResponse updated = preferenceService.updatePreference(emp.getId(), created.id(), req2, emp.getUser().getUsername());

        assertEquals("MORNING", updated.preferredShiftTypes());
        assertEquals("SATURDAY", updated.preferredOffDays());
    }

    @Test
    @DisplayName("4. Employee Preference: Delete/reset own preference")
    void testDeleteOwnPreference() {
        Employee emp = employeeRepository.findAll().get(0);
        PreferenceSubmitRequest req = new PreferenceSubmitRequest("GENERAL", "SUNDAY", null, null, null, null, null, null);
        PreferenceResponse created = preferenceService.submitPreference(emp.getId(), req, emp.getUser().getUsername());

        preferenceService.deletePreference(emp.getId(), created.id(), emp.getUser().getUsername());
        List<PreferenceResponse> list = preferenceService.getMyPreferences(emp.getId());
        assertFalse(list.stream().anyMatch(p -> p.id().equals(created.id())));
    }

    @Test
    @DisplayName("5. Employee Preference: Employee cannot modify another employee's preference")
    void testIdorProtection() {
        List<Employee> emps = employeeRepository.findAll();
        if (emps.size() >= 2) {
            Employee emp1 = emps.get(0);
            Employee emp2 = emps.get(1);

            PreferenceSubmitRequest req = new PreferenceSubmitRequest("GENERAL", "SUNDAY", null, null, null, null, null, null);
            PreferenceResponse created = preferenceService.submitPreference(emp1.getId(), req, emp1.getUser().getUsername());

            assertThrows(BusinessException.class, () -> {
                preferenceService.updatePreference(emp2.getId(), created.id(), req, emp2.getUser().getUsername());
            });

            assertThrows(BusinessException.class, () -> {
                preferenceService.deletePreference(emp2.getId(), created.id(), emp2.getUser().getUsername());
            });
        }
    }

    @Test
    @DisplayName("6. Employee Preference: Admin decide preference (Approve/Reject)")
    void testAdminDecidePreference() {
        Employee emp = employeeRepository.findAll().get(0);
        PreferenceSubmitRequest req = new PreferenceSubmitRequest("MORNING", "SUNDAY", null, null, null, null, null, null);
        PreferenceResponse created = preferenceService.submitPreference(emp.getId(), req, emp.getUser().getUsername());

        PreferenceDecisionRequest decision = new PreferenceDecisionRequest(PreferenceStatus.APPROVED, "Approved by manager");
        PreferenceResponse approved = preferenceService.decidePreference(created.id(), decision, "admin");

        assertEquals(PreferenceStatus.APPROVED, approved.status());
        assertEquals("Approved by manager", approved.adminRemarks());
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("10. Admin authorization allows preference management")
    void testAdminAuthorization() throws Exception {
        mockMvc.perform(get("/api/admin/preferences").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}