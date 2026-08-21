package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;

public class ShiftRestRuleTest {

    private RosterService rosterService;
    private Shift morning;
    private Shift general;
    private Shift evening;
    private Shift night;
    private Shift off;

    @BeforeEach
    void setUp() {
        rosterService = new RosterService(null, null, null, null, null, null, null);

        morning = createShift(1L, ShiftType.MORNING, LocalTime.of(7, 0), LocalTime.of(15, 0), false);
        general = createShift(2L, ShiftType.GENERAL, LocalTime.of(9, 30), LocalTime.of(18, 0), false);
        evening = createShift(3L, ShiftType.EVENING, LocalTime.of(14, 0), LocalTime.of(22, 0), false);
        night = createShift(4L, ShiftType.NIGHT, LocalTime.of(22, 0), LocalTime.of(7, 0), true);
        off = createShift(5L, ShiftType.OFF, null, null, false);
    }

    private Shift createShift(Long id, ShiftType type, LocalTime start, LocalTime end, boolean overnight) {
        Shift s = new Shift();
        s.setId(id);
        s.setShiftType(type);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setOvernight(overnight);
        s.setActive(true);
        s.setCapacity(1);
        return s;
    }

    @Test
    @DisplayName("Case 1: Night -> Next Day Morning (Rest = 0h) => REJECT")
    void testNightToMorning_Rejected() {
        LocalDate day1 = LocalDate.of(2026, 8, 18);
        LocalDate day2 = LocalDate.of(2026, 8, 19);

        Duration rest = rosterService.calculateRestDuration(day1, night, day2, morning);
        assertEquals(0, rest.toHours());
        assertFalse(rosterService.hasMinimumRest(day1, night, day2, morning));
    }

    @Test
    @DisplayName("Case 2: Night -> Next Day General (Rest = 2.5h) => REJECT")
    void testNightToGeneral_Rejected() {
        LocalDate day1 = LocalDate.of(2026, 8, 18);
        LocalDate day2 = LocalDate.of(2026, 8, 19);

        Duration rest = rosterService.calculateRestDuration(day1, night, day2, general);
        assertEquals(2, rest.toHours());
        assertEquals(30, rest.toMinutes() % 60);
        assertFalse(rosterService.hasMinimumRest(day1, night, day2, general));
    }

    @Test
    @DisplayName("Case 3: Night -> Next Day Evening (Rest = 7h) => REJECT")
    void testNightToEvening_Rejected() {
        LocalDate day1 = LocalDate.of(2026, 8, 18);
        LocalDate day2 = LocalDate.of(2026, 8, 19);

        Duration rest = rosterService.calculateRestDuration(day1, night, day2, evening);
        assertEquals(7, rest.toHours());
        assertFalse(rosterService.hasMinimumRest(day1, night, day2, evening));
    }

    @Test
    @DisplayName("Case 4: Night -> Next Day Night (Rest = 15h) => ALLOW")
    void testNightToNight_Allowed() {
        LocalDate day1 = LocalDate.of(2026, 8, 18);
        LocalDate day2 = LocalDate.of(2026, 8, 19);

        Duration rest = rosterService.calculateRestDuration(day1, night, day2, night);
        assertEquals(15, rest.toHours());
        assertTrue(rosterService.hasMinimumRest(day1, night, day2, night));
    }

    @Test
    @DisplayName("Case 5: Evening -> Next Day Morning (Rest = 9h) => REJECT")
    void testEveningToMorning_Rejected() {
        LocalDate day1 = LocalDate.of(2026, 8, 18);
        LocalDate day2 = LocalDate.of(2026, 8, 19);

        Duration rest = rosterService.calculateRestDuration(day1, evening, day2, morning);
        assertEquals(9, rest.toHours());
        assertFalse(rosterService.hasMinimumRest(day1, evening, day2, morning));
    }

    @Test
    @DisplayName("Case 6: Evening -> Next Day General (Rest = 11.5h) => REJECT")
    void testEveningToGeneral_Rejected() {
        LocalDate day1 = LocalDate.of(2026, 8, 18);
        LocalDate day2 = LocalDate.of(2026, 8, 19);

        Duration rest = rosterService.calculateRestDuration(day1, evening, day2, general);
        assertEquals(11, rest.toHours());
        assertEquals(30, rest.toMinutes() % 60);
        assertFalse(rosterService.hasMinimumRest(day1, evening, day2, general));
    }

    @Test
    @DisplayName("Case 7: Morning -> Next Day General (Rest = 18.5h) => ALLOW")
    void testMorningToGeneral_Allowed() {
        LocalDate day1 = LocalDate.of(2026, 8, 18);
        LocalDate day2 = LocalDate.of(2026, 8, 19);

        Duration rest = rosterService.calculateRestDuration(day1, morning, day2, general);
        assertEquals(18, rest.toHours());
        assertEquals(30, rest.toMinutes() % 60);
        assertTrue(rosterService.hasMinimumRest(day1, morning, day2, general));
    }

    @Test
    @DisplayName("Case 8 & 9: Previous Cycle Boundary Rest Calculation")
    void testPreviousCycleBoundaryRest() {
        LocalDate prevCycleEnd = LocalDate.of(2026, 8, 17); // Night on Sun 22:00 -> Mon 07:00
        LocalDate newCycleStart = LocalDate.of(2026, 8, 18); // Mon

        // Mon Morning starts 07:00 => Rest 0h => REJECT
        assertFalse(rosterService.hasMinimumRest(prevCycleEnd, night, newCycleStart, morning));

        // Mon General starts 09:30 => Rest 2.5h => REJECT
        assertFalse(rosterService.hasMinimumRest(prevCycleEnd, night, newCycleStart, general));

        // Mon Night starts 22:00 => Rest 15h => ALLOW
        assertTrue(rosterService.hasMinimumRest(prevCycleEnd, night, newCycleStart, night));
    }

    @Test
    @DisplayName("Case 15: Rajat Regression Case: Wed Night -> Thu General (2.5h rest) is Invalid")
    void testRajatRegressionCase_WedNightToThuGeneral() {
        LocalDate wed = LocalDate.of(2026, 8, 19);
        LocalDate thu = LocalDate.of(2026, 8, 20);

        // Wed Night (22:00 Wed -> 07:00 Thu) to Thu General (09:30 Thu)
        Duration rest = rosterService.calculateRestDuration(wed, night, thu, general);
        assertEquals(2, rest.toHours());
        assertEquals(30, rest.toMinutes() % 60);
        assertFalse(rosterService.hasMinimumRest(wed, night, thu, general));
    }

    @Test
    @DisplayName("Case 16: Shift Timing Display Formatting")
    void testShiftTimingDisplay() {
        assertEquals("07:00 - 15:00", morning.getTimingDisplay());
        assertEquals("09:30 - 18:00", general.getTimingDisplay());
        assertEquals("14:00 - 22:00", evening.getTimingDisplay());
        assertEquals("22:00 - 07:00 next day", night.getTimingDisplay());
        assertEquals("No working hours", off.getTimingDisplay());
    }

    @Test
    @DisplayName("Case 17: Dynamic Shift Timing Change propagates to Rest Calculation")
    void testDynamicShiftTimingChange() {
        // Change Night to 23:00 -> 08:00 next day
        Shift customNight = createShift(10L, ShiftType.NIGHT, LocalTime.of(23, 0), LocalTime.of(8, 0), true);
        assertEquals("23:00 - 08:00 next day", customNight.getTimingDisplay());

        LocalDate day1 = LocalDate.of(2026, 8, 18);
        LocalDate day2 = LocalDate.of(2026, 8, 19);

        // Next Day General at 09:30 -> Rest is now 08:00 to 09:30 = 1 hour 30 mins
        Duration rest = rosterService.calculateRestDuration(day1, customNight, day2, general);
        assertEquals(1, rest.toHours());
        assertEquals(30, rest.toMinutes() % 60);
        assertFalse(rosterService.hasMinimumRest(day1, customNight, day2, general));
    }
}
