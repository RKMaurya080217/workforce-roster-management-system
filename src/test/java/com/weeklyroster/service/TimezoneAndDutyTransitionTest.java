package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.weeklyroster.dto.response.TodayDutyResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.LeaveRequest;
import com.weeklyroster.entity.LeaveStatus;
import com.weeklyroster.entity.RosterAssignment;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.RosterOverride;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.entity.User;
import com.weeklyroster.repository.EmailDeliveryLogRepository;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.LeaveRequestRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.RosterOverrideRepository;
import com.weeklyroster.repository.ShiftRepository;

class TimezoneAndDutyTransitionTest {

    private EmployeeRepository employeeRepository;
    private ShiftRepository shiftRepository;
    private RosterCycleRepository cycleRepository;
    private RosterAssignmentRepository assignmentRepository;
    private RosterOverrideRepository overrideRepository;
    private LeaveRequestRepository leaveRepository;
    private EmailDeliveryLogRepository emailLogRepository;

    private RosterService rosterService;
    private Employee emp1;
    private Employee emp2;

    private Shift morningShift;
    private Shift generalShift;
    private Shift eveningShift;
    private Shift nightShift;
    private Shift offShift;

    @BeforeEach
    void setUp() {
        employeeRepository = mock(EmployeeRepository.class);
        shiftRepository = mock(ShiftRepository.class);
        cycleRepository = mock(RosterCycleRepository.class);
        assignmentRepository = mock(RosterAssignmentRepository.class);
        overrideRepository = mock(RosterOverrideRepository.class);
        leaveRepository = mock(LeaveRequestRepository.class);
        emailLogRepository = mock(EmailDeliveryLogRepository.class);

        rosterService = new RosterService(employeeRepository, shiftRepository, cycleRepository,
                assignmentRepository, overrideRepository, leaveRepository, emailLogRepository);

        User user1 = new User();
        user1.setUsername("emp001");
        emp1 = new Employee();
        emp1.setId(1L);
        emp1.setEmployeeCode("EMP001");
        emp1.setFirstName("Rahul");
        emp1.setLastName("Sharma");
        emp1.setGender(Gender.MALE);
        emp1.setActive(true);
        emp1.setUser(user1);

        User user2 = new User();
        user2.setUsername("emp002");
        emp2 = new Employee();
        emp2.setId(2L);
        emp2.setEmployeeCode("EMP002");
        emp2.setFirstName("Priya");
        emp2.setLastName("Patel");
        emp2.setGender(Gender.FEMALE);
        emp2.setActive(true);
        emp2.setUser(user2);

        morningShift = createShift(1L, ShiftType.MORNING, LocalTime.of(7, 0), LocalTime.of(15, 0), false, 2);
        generalShift = createShift(2L, ShiftType.GENERAL, LocalTime.of(9, 30), LocalTime.of(18, 0), false, 2);
        eveningShift = createShift(3L, ShiftType.EVENING, LocalTime.of(14, 0), LocalTime.of(22, 0), false, 2);
        nightShift = createShift(4L, ShiftType.NIGHT, LocalTime.of(22, 0), LocalTime.of(7, 0), true, 2);
        offShift = createShift(5L, ShiftType.OFF, null, null, false, 0);

        when(shiftRepository.findAll()).thenReturn(List.of(morningShift, generalShift, eveningShift, nightShift, offShift));
        when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(morningShift, generalShift, eveningShift, nightShift, offShift));
        when(shiftRepository.findByShiftType(ShiftType.MORNING)).thenReturn(Optional.of(morningShift));
        when(shiftRepository.findByShiftType(ShiftType.GENERAL)).thenReturn(Optional.of(generalShift));
        when(shiftRepository.findByShiftType(ShiftType.EVENING)).thenReturn(Optional.of(eveningShift));
        when(shiftRepository.findByShiftType(ShiftType.NIGHT)).thenReturn(Optional.of(nightShift));
        when(shiftRepository.findByShiftType(ShiftType.OFF)).thenReturn(Optional.of(offShift));

        when(employeeRepository.findById(1L)).thenReturn(Optional.of(emp1));
        when(employeeRepository.findById(2L)).thenReturn(Optional.of(emp2));
        when(employeeRepository.findByUserUsername("emp001")).thenReturn(Optional.of(emp1));
        when(employeeRepository.findByUserUsername("emp002")).thenReturn(Optional.of(emp2));
        when(employeeRepository.countByActiveTrue()).thenReturn(7L);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "pass", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private void authenticateAsEmployee(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "pass", List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))));
    }

    @Test
    @DisplayName("Overnight Night Shift: active at 01:30 AM next day should return WORKING and shift ending at 07:00")
    void testNightShiftSpanningMidnight() {
        authenticateAsEmployee("emp001");

        LocalDate yesterday = LocalDate.of(2026, 8, 18);
        LocalDate today = LocalDate.of(2026, 8, 19);
        LocalDateTime currentDateTime = LocalDateTime.of(2026, 8, 19, 1, 30); // 01:30 AM on 19 Aug

        RosterAssignment yAssign = new RosterAssignment();
        yAssign.setId(101L);
        yAssign.setEmployee(emp1);
        yAssign.setRosterDate(yesterday);
        yAssign.setShift(nightShift);

        RosterAssignment tAssign = new RosterAssignment();
        tAssign.setId(102L);
        tAssign.setEmployee(emp1);
        tAssign.setRosterDate(today);
        tAssign.setShift(offShift);
        tAssign.setWeeklyOff(true);

        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, yesterday)).thenReturn(List.of(yAssign));
        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, today)).thenReturn(List.of(tAssign));

        TodayDutyResponse response = rosterService.getTodayEffectiveDuty(1L, today, currentDateTime);

        assertNotNull(response);
        assertEquals("WORKING", response.status());
        assertEquals(ShiftType.NIGHT, response.shiftType());
        assertTrue(response.overnight());
        assertTrue(response.dynamicStatusText().contains("Currently on duty"));
        assertTrue(response.dynamicStatusText().contains("07:00"));
    }

    @Test
    @DisplayName("Midnight Transition: Before shift start returns dynamic countdown (Starts in Xh Ym)")
    void testMidnightTransitionCountdown() {
        authenticateAsEmployee("emp001");

        LocalDate today = LocalDate.of(2026, 8, 19);
        LocalDateTime currentDateTime = LocalDateTime.of(2026, 8, 19, 0, 1); // 00:01 AM on 19 Aug

        RosterAssignment tAssign = new RosterAssignment();
        tAssign.setId(102L);
        tAssign.setEmployee(emp1);
        tAssign.setRosterDate(today);
        tAssign.setShift(morningShift); // 07:00 - 15:00

        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, today)).thenReturn(List.of(tAssign));

        TodayDutyResponse response = rosterService.getTodayEffectiveDuty(1L, today, currentDateTime);

        assertNotNull(response);
        assertEquals("WORKING", response.status());
        assertEquals(ShiftType.MORNING, response.shiftType());
        assertTrue(response.dynamicStatusText().startsWith("Starts in "));
        assertTrue(response.dynamicStatusText().contains("6h"));
    }

    @Test
    @DisplayName("Conflict Resolution: Approved Leave takes precedence over Roster Override and Assignment")
    void testConflictResolutionLeaveHierarchy() {
        authenticateAsAdmin();

        LocalDate today = LocalDate.of(2026, 8, 19);
        LocalDateTime currentDateTime = LocalDateTime.of(2026, 8, 19, 10, 0);

        // Assignment is MORNING
        RosterAssignment tAssign = new RosterAssignment();
        tAssign.setId(102L);
        tAssign.setEmployee(emp1);
        tAssign.setRosterDate(today);
        tAssign.setShift(morningShift);

        // Override is EVENING
        RosterOverride override = new RosterOverride();
        override.setId(201L);
        override.setAssignment(tAssign);
        override.setNewShiftType(ShiftType.EVENING);

        // Approved Leave covers today
        LeaveRequest leave = new LeaveRequest();
        leave.setId(301L);
        leave.setEmployee(emp1);
        leave.setStartDate(today);
        leave.setEndDate(today);
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setReason("Doctor Appointment");

        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, today)).thenReturn(List.of(tAssign));
        when(overrideRepository.findByAssignmentIdOrderByCreatedAtDesc(102L)).thenReturn(List.of(override));
        when(leaveRepository.findByEmployeeIdAndStatusOrderByIdDesc(1L, LeaveStatus.APPROVED)).thenReturn(List.of(leave));

        TodayDutyResponse response = rosterService.getTodayEffectiveDuty(1L, today, currentDateTime);

        assertNotNull(response);
        assertEquals("LEAVE", response.status());
        assertEquals("LEAVE", response.source());
        assertEquals("On approved leave.", response.dynamicStatusText());
        assertEquals("Doctor Appointment", response.leaveReason());
    }

    @Test
    @DisplayName("Conflict Resolution: Roster Override takes precedence over Base Roster Assignment")
    void testConflictResolutionOverrideOverAssignment() {
        authenticateAsAdmin();

        LocalDate today = LocalDate.of(2026, 8, 19);
        LocalDateTime currentDateTime = LocalDateTime.of(2026, 8, 19, 10, 0);

        RosterAssignment tAssign = new RosterAssignment();
        tAssign.setId(102L);
        tAssign.setEmployee(emp1);
        tAssign.setRosterDate(today);
        tAssign.setShift(morningShift); // Originally Morning

        RosterOverride override = new RosterOverride();
        override.setId(201L);
        override.setAssignment(tAssign);
        override.setWeeklyOff(true); // Overridden to OFF

        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, today)).thenReturn(List.of(tAssign));
        when(overrideRepository.findByAssignmentIdOrderByCreatedAtDesc(102L)).thenReturn(List.of(override));
        when(leaveRepository.findByEmployeeIdAndStatusOrderByIdDesc(1L, LeaveStatus.APPROVED)).thenReturn(List.of());

        TodayDutyResponse response = rosterService.getTodayEffectiveDuty(1L, today, currentDateTime);

        assertNotNull(response);
        assertEquals("OFF", response.status());
        assertEquals("OVERRIDE", response.source());
        assertEquals("Today is your weekly OFF.", response.dynamicStatusText());
    }

    @Test
    @DisplayName("Dynamic Status: Shift completed after shift end time")
    void testDynamicStatusShiftCompleted() {
        authenticateAsEmployee("emp001");

        LocalDate today = LocalDate.of(2026, 8, 19);
        LocalDateTime currentDateTime = LocalDateTime.of(2026, 8, 19, 16, 0); // 16:00 is after 15:00

        RosterAssignment tAssign = new RosterAssignment();
        tAssign.setId(102L);
        tAssign.setEmployee(emp1);
        tAssign.setRosterDate(today);
        tAssign.setShift(morningShift); // 07:00 - 15:00

        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, today)).thenReturn(List.of(tAssign));

        TodayDutyResponse response = rosterService.getTodayEffectiveDuty(1L, today, currentDateTime);

        assertNotNull(response);
        assertEquals("WORKING", response.status());
        assertEquals("Shift completed", response.dynamicStatusText());
    }

    @Test
    @DisplayName("Employee Security Isolation: Employee A accessing Employee B schedule throws AccessDeniedException (HTTP 403)")
    void testEmployeeSecurityIsolation() {
        authenticateAsEmployee("emp001"); // Authenticated as emp001

        LocalDate today = LocalDate.of(2026, 8, 19);
        LocalDateTime currentDateTime = LocalDateTime.of(2026, 8, 19, 10, 0);

        // emp001 attempts to access emp2 (id=2)
        assertThrows(AccessDeniedException.class, () -> {
            rosterService.getTodayEffectiveDuty(2L, today, currentDateTime);
        });

        assertThrows(AccessDeniedException.class, () -> {
            rosterService.employeeRoster(2L);
        });
    }

    @Test
    @DisplayName("Admin Security Access: Admin can view any employee's effective duty")
    void testAdminCanAccessAnyDuty() {
        authenticateAsAdmin();

        LocalDate today = LocalDate.of(2026, 8, 19);
        LocalDateTime currentDateTime = LocalDateTime.of(2026, 8, 19, 10, 0);

        RosterAssignment tAssign = new RosterAssignment();
        tAssign.setId(102L);
        tAssign.setEmployee(emp2);
        tAssign.setRosterDate(today);
        tAssign.setShift(generalShift);

        when(assignmentRepository.findByEmployeeIdAndRosterDate(2L, today)).thenReturn(List.of(tAssign));

        TodayDutyResponse response = rosterService.getTodayEffectiveDuty(2L, today, currentDateTime);

        assertNotNull(response);
        assertEquals("WORKING", response.status());
        assertEquals(ShiftType.GENERAL, response.shiftType());
        assertEquals(2L, response.employeeId());
    }

    private Shift createShift(Long id, ShiftType type, LocalTime start, LocalTime end, boolean overnight, int capacity) {
        Shift s = new Shift();
        s.setId(id);
        s.setShiftType(type);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setOvernight(overnight);
        s.setCapacity(capacity);
        s.setActive(true);
        return s;
    }
}
