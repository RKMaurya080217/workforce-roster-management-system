package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.dto.response.RosterHealthReport;
import com.weeklyroster.entity.GenerationMode;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.RosterStatus;
import com.weeklyroster.repository.RosterCycleRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RosterSchedulerServiceTest {

    private RosterService rosterService;
    private RosterEmailService rosterEmailService;
    private RosterCycleRepository cycleRepository;
    private RosterHealthService rosterHealthService;
    private NotificationService notificationService;
    private RosterSchedulerService schedulerService;

    @BeforeEach
    void setUp() {
        rosterService = mock(RosterService.class);
        rosterEmailService = mock(RosterEmailService.class);
        cycleRepository = mock(RosterCycleRepository.class);
        rosterHealthService = mock(RosterHealthService.class);
        notificationService = mock(NotificationService.class);
        schedulerService = new RosterSchedulerService(rosterService, rosterEmailService, cycleRepository,
                rosterHealthService, notificationService, true, true, "Asia/Kolkata");
    }

    @Test
    @DisplayName("Date Formula: Calculate upcoming week correctly across all test dates")
    void testUpcomingWeekCalculations() {
        // Test Case 1: 24 Aug 2026 -> 31 Aug 2026 to 06 Sep 2026
        LocalDate date24Aug = LocalDate.of(2026, 8, 24);
        assertEquals(LocalDate.of(2026, 8, 24), schedulerService.calculateCurrentWeekStart(date24Aug));
        assertEquals(LocalDate.of(2026, 8, 30), schedulerService.calculateCurrentWeekEnd(date24Aug));
        assertEquals(LocalDate.of(2026, 8, 31), schedulerService.calculateUpcomingWeekStart(date24Aug));
        assertEquals(LocalDate.of(2026, 9, 6), schedulerService.calculateUpcomingWeekEnd(date24Aug));
        assertEquals(LocalDate.of(2026, 8, 31), schedulerService.calculateTargetMonday(date24Aug));

        // Test Case 2: 31 Aug 2026 -> 07 Sep 2026 to 13 Sep 2026
        LocalDate date31Aug = LocalDate.of(2026, 8, 31);
        assertEquals(LocalDate.of(2026, 8, 31), schedulerService.calculateCurrentWeekStart(date31Aug));
        assertEquals(LocalDate.of(2026, 9, 6), schedulerService.calculateCurrentWeekEnd(date31Aug));
        assertEquals(LocalDate.of(2026, 9, 7), schedulerService.calculateUpcomingWeekStart(date31Aug));
        assertEquals(LocalDate.of(2026, 9, 13), schedulerService.calculateUpcomingWeekEnd(date31Aug));
        assertEquals(LocalDate.of(2026, 9, 7), schedulerService.calculateTargetMonday(date31Aug));

        // Test Case 3: 07 Sep 2026 -> 14 Sep 2026 to 20 Sep 2026
        LocalDate date07Sep = LocalDate.of(2026, 9, 7);
        assertEquals(LocalDate.of(2026, 9, 7), schedulerService.calculateCurrentWeekStart(date07Sep));
        assertEquals(LocalDate.of(2026, 9, 13), schedulerService.calculateCurrentWeekEnd(date07Sep));
        assertEquals(LocalDate.of(2026, 9, 14), schedulerService.calculateUpcomingWeekStart(date07Sep));
        assertEquals(LocalDate.of(2026, 9, 20), schedulerService.calculateUpcomingWeekEnd(date07Sep));
        assertEquals(LocalDate.of(2026, 9, 14), schedulerService.calculateTargetMonday(date07Sep));

        // Test Case 4: 14 Sep 2026 -> 21 Sep 2026 to 27 Sep 2026
        LocalDate date14Sep = LocalDate.of(2026, 9, 14);
        assertEquals(LocalDate.of(2026, 9, 14), schedulerService.calculateCurrentWeekStart(date14Sep));
        assertEquals(LocalDate.of(2026, 9, 20), schedulerService.calculateCurrentWeekEnd(date14Sep));
        assertEquals(LocalDate.of(2026, 9, 21), schedulerService.calculateUpcomingWeekStart(date14Sep));
        assertEquals(LocalDate.of(2026, 9, 27), schedulerService.calculateUpcomingWeekEnd(date14Sep));
        assertEquals(LocalDate.of(2026, 9, 21), schedulerService.calculateTargetMonday(date14Sep));
    }

    @Test
    @DisplayName("Sunday Trigger: Calculate target Monday correctly on Sunday")
    void testCalculateTargetMonday_OnSunday() {
        // Sunday 16 Aug 2026 -> current week 10-16 Aug -> upcoming week 17-23 Aug
        LocalDate sunday16 = LocalDate.of(2026, 8, 16);
        assertEquals(LocalDate.of(2026, 8, 17), schedulerService.calculateUpcomingWeekStart(sunday16));
        assertEquals(LocalDate.of(2026, 8, 23), schedulerService.calculateUpcomingWeekEnd(sunday16));

        // Sunday 23 Aug 2026 -> current week 17-23 Aug -> upcoming week 24-30 Aug
        LocalDate sunday23 = LocalDate.of(2026, 8, 23);
        assertEquals(LocalDate.of(2026, 8, 24), schedulerService.calculateUpcomingWeekStart(sunday23));
        assertEquals(LocalDate.of(2026, 8, 30), schedulerService.calculateUpcomingWeekEnd(sunday23));

        // Sunday 30 Aug 2026 -> current week 24-30 Aug -> upcoming week 31 Aug-06 Sep
        LocalDate sunday30 = LocalDate.of(2026, 8, 30);
        assertEquals(LocalDate.of(2026, 8, 31), schedulerService.calculateUpcomingWeekStart(sunday30));
        assertEquals(LocalDate.of(2026, 9, 6), schedulerService.calculateUpcomingWeekEnd(sunday30));
    }

    @Test
    @DisplayName("Mid-week Base Date: Returns the next Monday")
    void testCalculateTargetMonday_OnMidWeek() {
        // Wednesday 19 Aug 2026 -> current week 17-23 Aug -> upcoming week 24-30 Aug
        LocalDate wednesday19 = LocalDate.of(2026, 8, 19);
        assertEquals(LocalDate.of(2026, 8, 24), schedulerService.calculateUpcomingWeekStart(wednesday19));
        assertEquals(LocalDate.of(2026, 8, 30), schedulerService.calculateUpcomingWeekEnd(wednesday19));
    }

    @Test
    @DisplayName("Duplicate Prevention: Should skip generation if cycle already exists (Idempotent)")
    void testIdempotencySkip() {
        LocalDate monday = schedulerService.calculateUpcomingWeekStart(null);
        LocalDate sunday = monday.plusDays(6);

        RosterCycle existing = new RosterCycle();
        existing.setId(99L);
        existing.setStartDate(monday);
        existing.setEndDate(sunday);

        RosterCycleResponse mockResp = new RosterCycleResponse(99L, monday, sunday, LocalDateTime.now(), List.of());

        when(cycleRepository.findByStartDateAndEndDate(monday, sunday)).thenReturn(Optional.of(existing));
        when(cycleRepository.findOverlappingCycles(monday, sunday)).thenReturn(List.of(existing));
        when(rosterService.cycle(99L)).thenReturn(mockResp);

        RosterCycleResponse result = schedulerService.executeAutoGeneration(monday);

        assertNotNull(result);
        assertEquals(99L, result.id());
        verify(rosterService, never()).generateWeeklyRoster(any(), any());
        verify(rosterEmailService, never()).distributeRosterEmails(any(), any(), any());
    }

    @Test
    @DisplayName("Manual + Automatic Coexistence: Admin manually generated cycle -> Scheduler skips and does not resend email")
    void testManualAndAutomaticCoexistence_AdminPreGenerated() {
        LocalDate monday = schedulerService.calculateUpcomingWeekStart(null);
        LocalDate sunday = monday.plusDays(6);

        RosterCycle manualCycle = new RosterCycle();
        manualCycle.setId(55L);
        manualCycle.setStartDate(monday);
        manualCycle.setEndDate(sunday);
        manualCycle.setGenerationMode(GenerationMode.MANUAL);
        manualCycle.setStatus(RosterStatus.PUBLISHED);

        RosterCycleResponse mockResp = new RosterCycleResponse(55L, monday, sunday, LocalDateTime.now(),
                GenerationMode.MANUAL, "SENT", List.of(), null);

        when(cycleRepository.findByStartDateAndEndDate(monday, sunday)).thenReturn(Optional.of(manualCycle));
        when(cycleRepository.findOverlappingCycles(monday, sunday)).thenReturn(List.of(manualCycle));
        when(rosterService.cycle(55L)).thenReturn(mockResp);

        // When scheduler runs targeting immediate upcoming Monday
        RosterCycleResponse result = schedulerService.executeAutoGeneration(monday);

        assertNotNull(result);
        assertEquals(55L, result.id());
        assertEquals(GenerationMode.MANUAL, result.generationMode());

        // Zero duplicate generation or blind email dispatch
        verify(rosterService, never()).generateWeeklyRoster(any(), any());
    }

    @Test
    @DisplayName("Automatic Generation: Generates new roster in AUTOMATIC mode, publishes and distributes email")
    void testGenerateNewAutomatic() {
        LocalDate monday = schedulerService.calculateUpcomingWeekStart(null);
        LocalDate sunday = monday.plusDays(6);

        RosterCycle cycle = new RosterCycle();
        cycle.setId(101L);
        cycle.setStartDate(monday);
        cycle.setEndDate(sunday);
        cycle.setGenerationMode(GenerationMode.AUTOMATIC);

        RosterCycleResponse genResp = new RosterCycleResponse(
                101L, monday, sunday, LocalDateTime.now(), GenerationMode.AUTOMATIC, "SENT", List.of(), null
        );

        RosterHealthReport passedHealth = new RosterHealthReport(
                101L, monday, sunday, RosterStatus.GENERATED, true, "READY TO PUBLISH (All Safety Rules Passed)",
                "PASSED", "PASSED", "PASSED", "PASSED", "PASSED", "PASSED", "PASSED", "PASSED", "PASSED",
                0, 0, 0, 0, 0, List.of()
        );

        when(cycleRepository.findByStartDateAndEndDate(monday, sunday)).thenReturn(Optional.empty());
        when(cycleRepository.findOverlappingCycles(monday, sunday)).thenReturn(List.of());
        when(rosterService.generateWeeklyRoster(monday, GenerationMode.AUTOMATIC)).thenReturn(genResp);
        when(cycleRepository.findById(101L)).thenReturn(Optional.of(cycle));
        when(rosterHealthService.getCycleHealth(101L)).thenReturn(passedHealth);

        RosterCycleResponse result = schedulerService.executeAutoGeneration(monday);

        assertNotNull(result);
        assertEquals(101L, result.id());
        assertEquals(GenerationMode.AUTOMATIC, result.generationMode());

        verify(rosterService).generateWeeklyRoster(monday, GenerationMode.AUTOMATIC);
        verify(cycleRepository).save(cycle);
        verify(rosterEmailService).distributeRosterEmails(eq(cycle), eq(genResp), eq(GenerationMode.AUTOMATIC));
    }
}
