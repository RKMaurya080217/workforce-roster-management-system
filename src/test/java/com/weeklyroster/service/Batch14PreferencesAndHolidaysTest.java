package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.weeklyroster.dto.request.HolidayRequest;
import com.weeklyroster.dto.request.PreferenceDecisionRequest;
import com.weeklyroster.dto.request.PreferenceSubmitRequest;
import com.weeklyroster.dto.response.HolidayResponse;
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
    private HolidayService holidayService;

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
    @DisplayName("10. Holiday: Create holiday")
    void testCreateHoliday() {
        LocalDate testDate = LocalDate.of(2035, 8, 15);
        HolidayRequest req = new HolidayRequest("Future Independence Day", testDate, "National Holiday", true);
        HolidayResponse res = holidayService.createHoliday(req, "admin");

        assertNotNull(res);
        assertNotNull(res.id());
        assertEquals("Future Independence Day", res.name());
        assertEquals(testDate, res.holidayDate());
    }

    @Test
    @DisplayName("11. Holiday: Read holidays (active & upcoming)")
    void testReadHolidays() {
        List<HolidayResponse> active = holidayService.getActiveHolidays();
        List<HolidayResponse> upcoming = holidayService.getUpcomingHolidays();
        assertNotNull(active);
        assertNotNull(upcoming);
    }

    @Test
    @DisplayName("12. Holiday: Update holiday")
    void testUpdateHoliday() {
        LocalDate testDate = LocalDate.of(2035, 10, 2);
        HolidayRequest req1 = new HolidayRequest("Gandhi Jayanti 2035", testDate, "National celebration", true);
        HolidayResponse created = holidayService.createHoliday(req1, "admin");

        HolidayRequest req2 = new HolidayRequest("Mahatma Gandhi Jayanti 2035", testDate, "National holiday celebration", true);
        HolidayResponse updated = holidayService.updateHoliday(created.id(), req2, "admin");

        assertEquals("Mahatma Gandhi Jayanti 2035", updated.name());
    }

    @Test
    @DisplayName("13. Holiday: Delete holiday")
    void testDeleteHoliday() {
        LocalDate testDate = LocalDate.of(2035, 12, 25);
        HolidayRequest req = new HolidayRequest("Christmas 2035", testDate, "Winter festival", true);
        HolidayResponse created = holidayService.createHoliday(req, "admin");

        holidayService.deleteHoliday(created.id(), "admin");
        assertFalse(holidayService.isHoliday(testDate));
    }

    @Test
    @DisplayName("14. Holiday: Activate/deactivate holiday")
    void testToggleHoliday() {
        LocalDate testDate = LocalDate.of(2035, 1, 26);
        HolidayRequest req = new HolidayRequest("Republic Day 2035", testDate, "Constitution Day", true);
        HolidayResponse created = holidayService.createHoliday(req, "admin");
        assertTrue(created.active());

        HolidayResponse toggled = holidayService.toggleActive(created.id(), "admin");
        assertFalse(toggled.active());
    }

    @Test
    @DisplayName("15. Holiday: Duplicate holiday date prevention")
    void testDuplicateHolidayDatePrevention() {
        LocalDate testDate = LocalDate.of(2035, 5, 1);
        HolidayRequest req1 = new HolidayRequest("May Day 2035", testDate, "Labor Day", true);
        holidayService.createHoliday(req1, "admin");

        HolidayRequest req2 = new HolidayRequest("Workers Day 2035", testDate, "Another name", true);
        assertThrows(BusinessException.class, () -> {
            holidayService.createHoliday(req2, "admin");
        });
    }

    @Test
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    @DisplayName("16. Employee read-only access to holidays")
    void testEmployeeReadHolidays() throws Exception {
        mockMvc.perform(get("/api/holidays").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    @DisplayName("17. Employee cannot create holiday (403 Forbidden)")
    void testEmployeeCannotCreateHoliday() throws Exception {
        String body = "{\"name\":\"Test\",\"holidayDate\":\"2035-09-01\",\"description\":\"Test\",\"active\":true}";
        mockMvc.perform(post("/api/admin/holidays")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    @DisplayName("18. Employee cannot delete holiday (403 Forbidden)")
    void testEmployeeCannotDeleteHoliday() throws Exception {
        mockMvc.perform(delete("/api/admin/holidays/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    @DisplayName("19. Admin authorization allows full holiday and preference management")
    void testAdminAuthorization() throws Exception {
        mockMvc.perform(get("/api/admin/holidays").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/preferences").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}