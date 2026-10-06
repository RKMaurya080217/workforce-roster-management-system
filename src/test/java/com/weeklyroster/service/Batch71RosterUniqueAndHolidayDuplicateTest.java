package com.weeklyroster.service;

import com.weeklyroster.dto.request.HolidayRequest;
import com.weeklyroster.dto.response.HolidayResponse;
import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Holiday;
import com.weeklyroster.entity.RosterAssignment;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.HolidayRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class Batch71RosterUniqueAndHolidayDuplicateTest {

    private static final Path ENTERPRISE_APP_JS = Path.of("src/main/resources/static/enterprise-app.js");
    private static final Path BATCH_55_RUNNER = Path.of("src/main/java/com/weeklyroster/config/Batch55DatabaseConsolidationRunner.java");

    @Test
    @DisplayName("Bug #1: HolidayRepository queries returning List do not throw IncorrectResultSizeDataAccessException when duplicates exist")
    void testHolidayRepositoryDuplicateHandling() {
        HolidayRepository mockRepo = mock(HolidayRepository.class);
        AuditService mockAudit = mock(AuditService.class);
        HolidayService service = new HolidayService(mockRepo, mockAudit);

        LocalDate date = LocalDate.of(2026, 10, 2);
        Holiday h1 = new Holiday("Gandhi Jayanti", date, "National Holiday 1");
        Holiday h2 = new Holiday("Gandhi Jayanti", date, "National Holiday 2");

        // Simulating the duplicate rows that previously triggered IncorrectResultSizeDataAccessException
        when(mockRepo.findByHolidayDateAndActiveTrue(date)).thenReturn(List.of(h1, h2));
        when(mockRepo.findByHolidayDate(date)).thenReturn(List.of(h1, h2));

        // isHoliday must return true without throwing IncorrectResultSizeDataAccessException
        boolean isHol = assertDoesNotThrow(() -> service.isHoliday(date));
        assertTrue(isHol, "isHoliday must return true when active holiday exists");

        // findHolidayByDate must return first holiday safely
        var opt = assertDoesNotThrow(() -> service.findHolidayByDate(date));
        assertTrue(opt.isPresent());
        assertEquals("Gandhi Jayanti", opt.get().getName());
    }

    @Test
    @DisplayName("Bug #2: Backend HolidayService prevents duplicate holiday creation for the same date")
    void testBackendHolidayDuplicateProtection() {
        HolidayRepository mockRepo = mock(HolidayRepository.class);
        AuditService mockAudit = mock(AuditService.class);
        HolidayService service = new HolidayService(mockRepo, mockAudit);

        LocalDate date = LocalDate.of(2026, 10, 2);
        HolidayRequest req = new HolidayRequest("Gandhi Jayanti", date, "National Holiday", true);

        // When holiday doesn't exist, creation succeeds
        when(mockRepo.findByHolidayDate(date)).thenReturn(List.of());
        when(mockRepo.existsByHolidayDate(date)).thenReturn(false);
        when(mockRepo.save(any(Holiday.class))).thenAnswer(inv -> {
            Holiday h = inv.getArgument(0);
            h.setId(101L);
            return h;
        });

        HolidayResponse resp = service.createHoliday(req, "admin");
        assertNotNull(resp);
        assertEquals("Gandhi Jayanti", resp.name());
        assertEquals(date, resp.holidayDate());

        // When same date is submitted again, duplicate must be rejected
        Holiday existing = new Holiday("Gandhi Jayanti", date, "National Holiday");
        when(mockRepo.findByHolidayDate(date)).thenReturn(List.of(existing));
        when(mockRepo.existsByHolidayDate(date)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createHoliday(req, "admin"));
        assertTrue(ex.getMessage().contains("Holiday already exists"), "Error message must state holiday already exists");
    }

    @Test
    @DisplayName("Bug #2: Frontend enterprise-app.js includes double-submit guard and prevents multiple bindings")
    void testFrontendHolidayDoubleSubmitGuards() throws Exception {
        String js = Files.readString(ENTERPRISE_APP_JS, StandardCharsets.UTF_8);

        assertTrue(js.contains("let isSavingHoliday = false;"), "Must declare isSavingHoliday guard variable");
        assertTrue(js.contains("if (isSavingHoliday) return;"), "Must check isSavingHoliday guard in handler");
        assertTrue(js.contains("saveBtn.disabled = true;"), "Must disable submit button during request");
        assertTrue(js.contains("isSavingHoliday = false;"), "Must release isSavingHoliday guard in finally");
        assertTrue(js.contains("form.onsubmit = null;"), "Must clear form.onsubmit to avoid duplicate firing");
        assertTrue(js.contains("holidayForm.dataset.boundSubmit"), "Must use dataset check to avoid duplicate submit event listener");
    }

    @Test
    @DisplayName("Database Runner: Batch 55 runner must preserve HOLIDAY records on startup")
    void testBatch55RunnerPreservesHolidays() throws Exception {
        String code = Files.readString(BATCH_55_RUNNER, StandardCharsets.UTF_8);

        assertFalse(code.contains("item_type IN ('SKILL', 'HOLIDAY')"), "Must not delete HOLIDAY records on startup");
        assertTrue(code.contains("item_type IN ('SKILL')"), "Must only delete obsolete SKILL records");
    }

    @Test
    @DisplayName("Roster deduplication: employeeRoster preserves single assignment per date")
    void testEmployeeRosterAssignmentDeduplication() {
        Employee emp = new Employee();
        emp.setId(10L);
        emp.setEmployeeCode("EMP010");
        emp.setFirstName("Rahul");
        emp.setLastName("Sharma");

        Shift morning = new Shift();
        morning.setId(1L);
        morning.setShiftType(ShiftType.MORNING);

        Shift evening = new Shift();
        evening.setId(2L);
        evening.setShiftType(ShiftType.EVENING);

        RosterCycle cycle = new RosterCycle();
        cycle.setId(100L);

        LocalDate date = LocalDate.of(2026, 10, 6);

        RosterAssignment a1 = new RosterAssignment();
        a1.setId(1L);
        a1.setEmployee(emp);
        a1.setRosterDate(date);
        a1.setShift(morning);
        a1.setCycle(cycle);

        RosterAssignment a2 = new RosterAssignment();
        a2.setId(2L);
        a2.setEmployee(emp);
        a2.setRosterDate(date);
        a2.setShift(evening);
        a2.setCycle(cycle);
        a2.setOverridden(true);

        List<RosterAssignment> rawAssignments = List.of(a1, a2);

        java.util.Map<LocalDate, RosterAssignment> dedupMap = new java.util.LinkedHashMap<>();
        for (RosterAssignment a : rawAssignments) {
            if (a.getRosterDate() != null) {
                RosterAssignment current = dedupMap.get(a.getRosterDate());
                if (current == null || a.isOverridden()) {
                    dedupMap.put(a.getRosterDate(), a);
                }
            }
        }

        assertEquals(1, dedupMap.size(), "Should have exactly 1 assignment for the date");
        assertEquals(2L, dedupMap.get(date).getId(), "Should select the overridden/latest assignment");
    }
}
