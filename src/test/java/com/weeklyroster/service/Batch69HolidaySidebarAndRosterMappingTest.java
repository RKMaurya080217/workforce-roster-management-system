package com.weeklyroster.service;

import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.entity.ShiftType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class Batch69HolidaySidebarAndRosterMappingTest {

    private static final Path APP_JS = Path.of("src/main/resources/static/app.js");
    private static final Path ENTERPRISE_APP_JS = Path.of("src/main/resources/static/enterprise-app.js");

    @Test
    @DisplayName("Admin Sidebar: Holiday Management must be a primary navigation item")
    void testAdminSidebarPrimaryNavHolidayManagement() throws Exception {
        String js = Files.readString(APP_JS, StandardCharsets.UTF_8);

        assertTrue(js.contains("const ADMIN_PRIMARY_NAV = ["), "Must define ADMIN_PRIMARY_NAV array");
        assertTrue(js.contains("id: \"holidayCalendar\""), "Must include holidayCalendar in primary navigation");
        assertTrue(js.contains("label: \"Holiday Management\""), "Must label as Holiday Management in primary navigation");
        assertTrue(js.contains("route: \"holiday-calendar\""), "Must use route holiday-calendar");

        // Verify active page highlight mapping
        assertTrue(js.contains("item.id === \"holidayCalendar\" && state.activePage === \"adminHolidays\""),
                "Active check must highlight holidayCalendar when state.activePage is adminHolidays");

        // Ensure legacy prohibited keys remain absent from navigation items
        assertFalse(js.contains("id: \"adminHolidays\""), "Must not use prohibited id adminHolidays");
    }

    @Test
    @DisplayName("Employee Roster: app.js must incorporate getHolidayForDate into calendar & table views")
    void testEmployeeRosterHolidayIntegration() throws Exception {
        String js = Files.readString(APP_JS, StandardCharsets.UTF_8);

        assertTrue(js.contains("function getHolidayForDate("), "Must define getHolidayForDate function");
        assertTrue(js.contains("function normalizeDateStr("), "Must define normalizeDateStr function");

        // Check filterEmployeeRoster checks holidays
        assertTrue(js.contains("!getHolidayForDate(a.rosterDate, a) && !a.onLeave && !a.weeklyOff"),
                "WORKING filter must exclude holidays");

        // Check renderEmployeeRosterCalendarHTML renders holiday badge and suppresses working shift
        assertTrue(js.contains("const hol = getHolidayForDate(a.rosterDate, a);"),
                "renderEmployeeRosterCalendarHTML must look up holiday for each assignment");
        assertTrue(js.contains("flag-badge flag-holiday"),
                "renderEmployeeRosterCalendarHTML must render flag-holiday badge");
        assertTrue(js.contains("🎉 HOLIDAY"),
                "renderEmployeeRosterCalendarHTML must display HOLIDAY");

        // Check table view
        assertTrue(js.contains("getHolidayForDate(a.rosterDate, a)"),
                "renderMyRosterTableHTML must check holidays using getHolidayForDate");
    }

    @Test
    @DisplayName("DTO & Backend: RosterAssignmentResponse must support holiday and holidayName")
    void testRosterAssignmentResponseHolidayFields() {
        RosterAssignmentResponse resp = new RosterAssignmentResponse(
                1L, 10L, LocalDate.of(2026, 10, 2), 5L, "EMP005", "John Doe",
                null, ShiftType.MORNING, false, false, false, "Holiday Assignment",
                true, "Gandhi Jayanti"
        );

        assertTrue(resp.holiday(), "Holiday flag should be true");
        assertEquals("Gandhi Jayanti", resp.holidayName(), "Holiday name should match");

        // Backward compatibility constructor test
        RosterAssignmentResponse legacyResp = new RosterAssignmentResponse(
                1L, 10L, LocalDate.of(2026, 10, 2), 5L, "EMP005", "John Doe",
                null, ShiftType.MORNING, false, false, false, "Regular Assignment"
        );
        assertFalse(legacyResp.holiday(), "Default holiday flag should be false");
        assertNull(legacyResp.holidayName(), "Default holiday name should be null");
    }

    @Test
    @DisplayName("Admin Holiday Management View: Must show Holiday Management and Existing Holidays")
    void testAdminHolidayManagementViewMarkup() throws Exception {
        String entJs = Files.readString(ENTERPRISE_APP_JS, StandardCharsets.UTF_8);

        assertTrue(entJs.contains("<h2>Holiday Management</h2>"),
                "Must render Holiday Management as page header");
        assertTrue(entJs.contains("<h3>Existing Holidays</h3>"),
                "Must render Existing Holidays as table card title");
        assertTrue(entJs.contains("<span>+ Add Holiday</span>"),
                "Must render + Add Holiday button");
    }
}
