package com.weeklyroster.service;

import com.weeklyroster.dto.request.ShiftChangeRequest;
import com.weeklyroster.dto.request.UnlockRosterRequest;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.dto.response.RosterHealthReport;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RosterPublishLockTest {

    @Mock
    private RosterCycleRepository cycleRepository;
    @Mock
    private RosterAssignmentRepository assignmentRepository;
    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private ShiftRepository shiftRepository;
    @Mock
    private RosterOverrideRepository overrideRepository;
    @Mock
    private LeaveRequestRepository leaveRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private RosterHealthService rosterHealthService;

    @InjectMocks
    private RosterService rosterService;

    private RosterCycle draftCycle;
    private RosterCycle publishedCycle;
    private RosterCycle lockedCycle;
    private Employee emp1;
    private Shift morningShift;
    private Shift generalShift;
    private Shift eveningShift;
    private Shift nightShift;
    private Shift offShift;
    private RosterAssignment assignment1;

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken("admin", "Admin@123", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        draftCycle = new RosterCycle();
        draftCycle.setId(1L);
        draftCycle.setStartDate(LocalDate.of(2026, 8, 24));
        draftCycle.setEndDate(LocalDate.of(2026, 8, 30));
        draftCycle.setStatus(RosterStatus.GENERATED);
        draftCycle.setGeneratedAt(LocalDateTime.now());

        publishedCycle = new RosterCycle();
        publishedCycle.setId(2L);
        publishedCycle.setStartDate(LocalDate.of(2026, 8, 24));
        publishedCycle.setEndDate(LocalDate.of(2026, 8, 30));
        publishedCycle.setStatus(RosterStatus.PUBLISHED);
        publishedCycle.setPublishedAt(LocalDateTime.now());
        publishedCycle.setPublishedBy("admin");

        lockedCycle = new RosterCycle();
        lockedCycle.setId(3L);
        lockedCycle.setStartDate(LocalDate.of(2026, 8, 24));
        lockedCycle.setEndDate(LocalDate.of(2026, 8, 30));
        lockedCycle.setStatus(RosterStatus.LOCKED);
        lockedCycle.setLockedAt(LocalDateTime.now());
        lockedCycle.setLockedBy("admin");

        emp1 = new Employee();
        emp1.setId(10L);
        emp1.setEmployeeCode("EMP001");
        emp1.setFirstName("Alice");
        emp1.setLastName("Smith");
        emp1.setGender(Gender.FEMALE);
        emp1.setActive(true);

        morningShift = new Shift();
        morningShift.setId(1L);
        morningShift.setShiftType(ShiftType.MORNING);
        morningShift.setStartTime(LocalTime.of(6, 0));
        morningShift.setEndTime(LocalTime.of(14, 0));
        morningShift.setActive(true);

        generalShift = new Shift();
        generalShift.setId(2L);
        generalShift.setShiftType(ShiftType.GENERAL);
        generalShift.setStartTime(LocalTime.of(9, 0));
        generalShift.setEndTime(LocalTime.of(17, 0));
        generalShift.setActive(true);

        eveningShift = new Shift();
        eveningShift.setId(3L);
        eveningShift.setShiftType(ShiftType.EVENING);
        eveningShift.setStartTime(LocalTime.of(14, 0));
        eveningShift.setEndTime(LocalTime.of(22, 0));
        eveningShift.setActive(true);

        nightShift = new Shift();
        nightShift.setId(4L);
        nightShift.setShiftType(ShiftType.NIGHT);
        nightShift.setStartTime(LocalTime.of(22, 0));
        nightShift.setEndTime(LocalTime.of(6, 0));
        nightShift.setOvernight(true);
        nightShift.setActive(true);

        offShift = new Shift();
        offShift.setId(5L);
        offShift.setShiftType(ShiftType.OFF);
        offShift.setActive(true);

        assignment1 = new RosterAssignment();
        assignment1.setId(100L);
        assignment1.setCycle(draftCycle);
        assignment1.setEmployee(emp1);
        assignment1.setShift(morningShift);
        assignment1.setRosterDate(LocalDate.of(2026, 8, 24));
        assignment1.setWeeklyOff(false);
        assignment1.setOnLeave(false);

        lenient().when(shiftRepository.findByActiveTrueOrderByIdAsc())
                .thenReturn(List.of(morningShift, generalShift, eveningShift, nightShift, offShift));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Publish succeeds when roster health has zero critical conflicts")
    void testPublishRosterSuccess() {
        when(cycleRepository.findById(1L)).thenReturn(Optional.of(draftCycle));
        when(assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(draftCycle)).thenReturn(List.of(assignment1));
        when(rosterHealthService.evaluateHealth(eq(draftCycle), any())).thenReturn(
                new RosterHealthReport(1L, LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30),
                        RosterStatus.GENERATED, true, "Ready to publish", "PASS", "PASS", "PASS", "PASS", "PASS", "PASS", "PASS", "PASS", "PASS",
                        0, 0, 0, 0, 0, List.of())
        );
        when(cycleRepository.save(any(RosterCycle.class))).thenAnswer(inv -> inv.getArgument(0));

        RosterCycleResponse response = rosterService.publishRoster(1L);

        assertNotNull(response);
        assertEquals(RosterStatus.PUBLISHED, response.status());
        verify(auditService).log(eq(AuditAction.ROSTER_PUBLISHED), eq("ROSTER_CYCLE"), eq(1L), eq(1L), isNull(), isNull(), any(), any(), any(), eq("MANUAL"));
        verify(notificationService).notifyAllActiveEmployees(any(), any(), eq(NotificationType.ROSTER_PUBLISHED), eq("roster"), eq(1L));
    }

    @Test
    @DisplayName("Publish fails when roster has critical conflicts")
    void testPublishRosterBlockedByCriticalConflict() {
        when(cycleRepository.findById(1L)).thenReturn(Optional.of(draftCycle));
        when(assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(draftCycle)).thenReturn(List.of(assignment1));
        when(rosterHealthService.evaluateHealth(eq(draftCycle), any())).thenReturn(
                new RosterHealthReport(1L, LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30),
                        RosterStatus.GENERATED, false, "Critical conflicts must be resolved", "FAIL", "PASS", "PASS", "PASS", "PASS", "PASS", "PASS", "PASS", "PASS",
                        1, 0, 0, 0, 0, List.of())
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> rosterService.publishRoster(1L));
        assertTrue(ex.getMessage().contains("cannot be published until critical conflicts are resolved"));
    }

    @Test
    @DisplayName("Locking roster updates status to LOCKED and sends notifications")
    void testLockRoster() {
        when(cycleRepository.findById(2L)).thenReturn(Optional.of(publishedCycle));
        when(assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(publishedCycle)).thenReturn(List.of(assignment1));
        when(cycleRepository.save(any(RosterCycle.class))).thenAnswer(inv -> inv.getArgument(0));

        RosterCycleResponse response = rosterService.lockRoster(2L);

        assertEquals(RosterStatus.LOCKED, response.status());
        verify(auditService).log(eq(AuditAction.ROSTER_LOCKED), eq("ROSTER_CYCLE"), eq(2L), eq(2L), isNull(), isNull(), any(), any(), any(), eq("MANUAL"));
        verify(notificationService).notifyAllActiveEmployees(any(), any(), eq(NotificationType.ROSTER_LOCKED), eq("roster"), eq(2L));
    }

    @Test
    @DisplayName("Unlocking roster requires a valid non-empty reason")
    void testUnlockRosterRequiresReason() {
        assertThrows(BusinessException.class, () -> rosterService.unlockRoster(3L, new UnlockRosterRequest("")));
        assertThrows(BusinessException.class, () -> rosterService.unlockRoster(3L, new UnlockRosterRequest(null)));
        assertThrows(BusinessException.class, () -> rosterService.unlockRoster(3L, new UnlockRosterRequest("   ")));
    }

    @Test
    @DisplayName("Unlocking locked roster succeeds with reason and updates status to PUBLISHED")
    void testUnlockRosterSuccess() {
        when(cycleRepository.findById(3L)).thenReturn(Optional.of(lockedCycle));
        when(assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(lockedCycle)).thenReturn(List.of(assignment1));
        when(cycleRepository.save(any(RosterCycle.class))).thenAnswer(inv -> inv.getArgument(0));

        RosterCycleResponse response = rosterService.unlockRoster(3L, new UnlockRosterRequest("Emergency operational coverage adjustment"));

        assertEquals(RosterStatus.PUBLISHED, response.status());
        verify(auditService).log(eq(AuditAction.ROSTER_UNLOCKED), eq("ROSTER_CYCLE"), eq(3L), eq(3L), isNull(), isNull(), any(), any(), contains("Emergency"), eq("MANUAL"));
        verify(notificationService).notifyAdmins(any(), contains("unlocked"), eq(NotificationType.ROSTER_UNLOCKED), eq("health"), eq(3L));
    }

    @Test
    @DisplayName("Modifying shift on locked roster throws BusinessException")
    void testModifyShiftOnLockedRosterBlocked() {
        assignment1.setCycle(lockedCycle);
        when(assignmentRepository.findById(100L)).thenReturn(Optional.of(assignment1));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                rosterService.changeShift(100L, new ShiftChangeRequest(ShiftType.EVENING, "Test"))
        );
        assertTrue(ex.getMessage().contains("locked and cannot be modified"));
    }

    @Test
    @DisplayName("Swapping shifts on locked roster throws BusinessException")
    void testSwapShiftsOnLockedRosterBlocked() {
        assignment1.setCycle(lockedCycle);
        RosterAssignment assignment2 = new RosterAssignment();
        assignment2.setId(101L);
        assignment2.setCycle(lockedCycle);
        assignment2.setRosterDate(LocalDate.of(2026, 8, 24));
        assignment2.setEmployee(emp1);
        assignment2.setShift(nightShift);

        when(assignmentRepository.findById(100L)).thenReturn(Optional.of(assignment1));
        when(assignmentRepository.findById(101L)).thenReturn(Optional.of(assignment2));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                rosterService.swapShifts(100L, 101L, "Swap test")
        );
        assertTrue(ex.getMessage().contains("Cannot swap shifts on a locked roster"));
    }

    @Test
    @DisplayName("Deleting locked roster throws BusinessException")
    void testDeleteLockedRosterBlocked() {
        when(cycleRepository.findById(3L)).thenReturn(Optional.of(lockedCycle));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                rosterService.deleteCycle(3L)
        );
        assertTrue(ex.getMessage().contains("is locked and cannot be deleted"));
    }
}
