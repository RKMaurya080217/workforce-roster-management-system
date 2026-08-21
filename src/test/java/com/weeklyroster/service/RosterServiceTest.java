package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.weeklyroster.dto.request.ShiftChangeRequest;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.RosterAssignment;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import org.springframework.security.access.AccessDeniedException;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.LeaveRequestRepository;
import com.weeklyroster.repository.EmailDeliveryLogRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.RosterOverrideRepository;
import com.weeklyroster.repository.ShiftRepository;

class RosterServiceTest {

    private EmployeeRepository employeeRepository;
    private ShiftRepository shiftRepository;
    private RosterCycleRepository cycleRepository;
    private RosterAssignmentRepository assignmentRepository;
    private RosterOverrideRepository overrideRepository;
    private LeaveRequestRepository leaveRepository;

    private RosterService rosterService;
    private List<Employee> testEmployees;

    @BeforeEach
    void setUp() {
        employeeRepository = mock(EmployeeRepository.class);
        shiftRepository = mock(ShiftRepository.class);
        cycleRepository = mock(RosterCycleRepository.class);
        assignmentRepository = mock(RosterAssignmentRepository.class);
        overrideRepository = mock(RosterOverrideRepository.class);
        leaveRepository = mock(LeaveRequestRepository.class);

        EmailDeliveryLogRepository emailLogRepository = mock(EmailDeliveryLogRepository.class);
        rosterService = new RosterService(employeeRepository, shiftRepository, cycleRepository,
                assignmentRepository, overrideRepository, leaveRepository, emailLogRepository);

        Employee e1 = createEmployee(1L, "EMP001", "Rajat", "Maurya", Gender.MALE);
        Employee e2 = createEmployee(2L, "EMP002", "Prachi", "Mishra", Gender.FEMALE);
        Employee e3 = createEmployee(3L, "EMP003", "Shriram", "Kumar", Gender.MALE);
        Employee e4 = createEmployee(4L, "EMP004", "Sapna", "Pandey", Gender.FEMALE);
        Employee e5 = createEmployee(5L, "EMP005", "Tushar", "Chandila", Gender.MALE);
        testEmployees = List.of(e1, e2, e3, e4, e5);

        lenient().when(assignmentRepository.findWorkedAssignmentsBefore(anyLong(), any(LocalDate.class))).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testGenerateWeeklyRoster_ThrowsException_WhenFewerThanFourEmployees() {
        when(employeeRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(testEmployees.get(0)));

        assertThrows(BusinessException.class, () -> rosterService.generateWeeklyRoster());
    }

    @Test
    void testGenerateWeeklyRoster_ThrowsException_WhenFewerThanThreeMaleEmployees() {
        Employee female1 = createEmployee(1L, "EMP001", "A", "A", Gender.FEMALE);
        Employee female2 = createEmployee(2L, "EMP002", "B", "B", Gender.FEMALE);
        Employee female3 = createEmployee(3L, "EMP003", "C", "C", Gender.FEMALE);
        Employee male1 = createEmployee(4L, "EMP004", "D", "D", Gender.MALE);

        when(employeeRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(female1, female2, female3, male1));

        assertThrows(BusinessException.class, () -> rosterService.generateWeeklyRoster());
    }

    @Test
    void testSwapShifts_Successful() {
        Employee e1 = testEmployees.get(0); // Male
        Employee e2 = testEmployees.get(2); // Male

        Shift morning = createShift(1L, ShiftType.MORNING, 1);
        Shift night = createShift(4L, ShiftType.NIGHT, 1);

        LocalDate date = LocalDate.now().plusDays(1);

        RosterCycle cycle = new RosterCycle();
        cycle.setId(1L);

        RosterAssignment a1 = new RosterAssignment();
        a1.setId(10L);
        a1.setCycle(cycle);
        a1.setEmployee(e1);
        a1.setShift(morning);
        a1.setRosterDate(date);

        RosterAssignment a2 = new RosterAssignment();
        a2.setId(20L);
        a2.setCycle(cycle);
        a2.setEmployee(e2);
        a2.setShift(night);
        a2.setRosterDate(date);

        when(assignmentRepository.findById(10L)).thenReturn(Optional.of(a1));
        when(assignmentRepository.findById(20L)).thenReturn(Optional.of(a2));
        when(assignmentRepository.saveAll(anyList())).thenReturn(List.of(a1, a2));

        var response = rosterService.swapShifts(10L, 20L, "Mutual agreement");

        assertNotNull(response);
        assertEquals(2, response.size());
        assertEquals(ShiftType.NIGHT, a1.getShift().getShiftType());
        assertEquals(ShiftType.MORNING, a2.getShift().getShiftType());
        assertTrue(a1.isOverridden());
        assertTrue(a2.isOverridden());
        verify(overrideRepository).saveAll(anyList());
    }

    @Test
    void testSwapShifts_ThrowsException_WhenFemaleAssignedToNight() {
        Employee female = testEmployees.get(1); // Female
        Employee male = testEmployees.get(0); // Male

        Shift morning = createShift(1L, ShiftType.MORNING, 1);
        Shift night = createShift(4L, ShiftType.NIGHT, 1);

        LocalDate date = LocalDate.now().plusDays(1);

        RosterAssignment a1 = new RosterAssignment();
        a1.setId(10L);
        a1.setEmployee(female);
        a1.setShift(morning);
        a1.setRosterDate(date);

        RosterAssignment a2 = new RosterAssignment();
        a2.setId(20L);
        a2.setEmployee(male);
        a2.setShift(night);
        a2.setRosterDate(date);

        when(assignmentRepository.findById(10L)).thenReturn(Optional.of(a1));
        when(assignmentRepository.findById(20L)).thenReturn(Optional.of(a2));

        assertThrows(BusinessException.class, () -> rosterService.swapShifts(10L, 20L, "Swap"));
    }

    @Test
    void testSwapShifts_ThrowsException_WhenDifferentDates() {
        RosterAssignment a1 = new RosterAssignment();
        a1.setId(10L);
        a1.setRosterDate(LocalDate.now().plusDays(1));

        RosterAssignment a2 = new RosterAssignment();
        a2.setId(20L);
        a2.setRosterDate(LocalDate.now().plusDays(2));

        when(assignmentRepository.findById(10L)).thenReturn(Optional.of(a1));
        when(assignmentRepository.findById(20L)).thenReturn(Optional.of(a2));

        assertThrows(BusinessException.class, () -> rosterService.swapShifts(10L, 20L, "Swap"));
    }

    @Test
    void testGenerateWeeklyRoster_EnforcesFemaleRestrictions_AndNightRest() {
        Shift morning = createShift(1L, ShiftType.MORNING, 1);
        Shift general = createShift(2L, ShiftType.GENERAL, 1);
        Shift evening = createShift(3L, ShiftType.EVENING, 1);
        Shift night = createShift(4L, ShiftType.NIGHT, 1);
        Shift off = createShift(5L, ShiftType.OFF, 0);

        when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(morning, general, evening, night, off));
        when(employeeRepository.findByActiveTrueOrderByIdAsc()).thenReturn(testEmployees);
        when(cycleRepository.findByStartDateAndEndDate(any(LocalDate.class), any(LocalDate.class))).thenReturn(Optional.empty());
        when(cycleRepository.save(any(RosterCycle.class))).thenAnswer(invocation -> {
            RosterCycle c = invocation.getArgument(0);
            c.setId(100L);
            return c;
        });
        when(assignmentRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        LocalDate startDate = LocalDate.of(2026, 9, 7);
        RosterCycleResponse response = rosterService.generateWeeklyRoster(startDate);

        assertNotNull(response);
        assertEquals(7 * 5, response.assignments().size());

        // Verify female employees NEVER receive EVENING or NIGHT
        for (var a : response.assignments()) {
            if (a.gender() == Gender.FEMALE) {
                assertNotEquals(ShiftType.EVENING, a.shiftType(), "Female employee cannot have EVENING shift");
                assertNotEquals(ShiftType.NIGHT, a.shiftType(), "Female employee cannot have NIGHT shift");
            }
        }
    }

    @Test
    void testWeeklyOffs_AreDistributedEvenly() {
        Shift morning = createShift(1L, ShiftType.MORNING, 1);
        Shift general = createShift(2L, ShiftType.GENERAL, 1);
        Shift evening = createShift(3L, ShiftType.EVENING, 1);
        Shift night = createShift(4L, ShiftType.NIGHT, 1);
        Shift off = createShift(5L, ShiftType.OFF, 0);

        when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(morning, general, evening, night, off));
        when(employeeRepository.findByActiveTrueOrderByIdAsc()).thenReturn(testEmployees);
        when(cycleRepository.findByStartDateAndEndDate(any(LocalDate.class), any(LocalDate.class))).thenReturn(Optional.empty());
        when(cycleRepository.save(any(RosterCycle.class))).thenAnswer(invocation -> {
            RosterCycle c = invocation.getArgument(0);
            c.setId(100L);
            return c;
        });
        when(assignmentRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        LocalDate startDate = LocalDate.of(2026, 9, 7);
        RosterCycleResponse response = rosterService.generateWeeklyRoster(startDate);

        assertNotNull(response);
        // Each employee should have exactly 1 weekly off during the 7-day cycle
        for (Employee emp : testEmployees) {
            long offCount = response.assignments().stream()
                    .filter(a -> a.employeeId().equals(emp.getId()) && a.weeklyOff())
                    .count();
            assertEquals(1, offCount, "Employee " + emp.getEmployeeCode() + " should have exactly 1 weekly off");
        }
    }

    @Test
    void testChangeShift_UpdatesAssignmentAndCreatesOverrideRecord() {
        Shift morning = createShift(1L, ShiftType.MORNING, 1);
        Shift general = createShift(2L, ShiftType.GENERAL, 1);

        RosterAssignment a = new RosterAssignment();
        a.setId(15L);
        a.setEmployee(testEmployees.get(0));
        a.setShift(morning);
        a.setRosterDate(LocalDate.now().plusDays(1));
        a.setWeeklyOff(false);
        a.setOnLeave(false);

        when(assignmentRepository.findById(15L)).thenReturn(Optional.of(a));
        when(shiftRepository.findByShiftType(ShiftType.GENERAL)).thenReturn(Optional.of(general));
        when(assignmentRepository.countByRosterDateAndShiftShiftTypeAndWeeklyOffFalseAndOnLeaveFalse(any(), eq(ShiftType.MORNING)))
                .thenReturn(2L); // 2 morning employees, safe to reduce by 1

        var response = rosterService.changeShift(15L, new ShiftChangeRequest(ShiftType.GENERAL, "Operational need"));

        assertNotNull(response);
        assertEquals(ShiftType.GENERAL, response.shiftType());
        assertTrue(response.overridden());
        verify(overrideRepository).save(any());
    }

    @Test
    void testDeleteCycle_Successful_AdminAuthorized() {
        RosterCycle cycle = new RosterCycle();
        cycle.setId(10L);
        cycle.setStartDate(LocalDate.of(2026, 9, 1));
        cycle.setEndDate(LocalDate.of(2026, 9, 7));

        var auth = new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        when(cycleRepository.findById(10L)).thenReturn(Optional.of(cycle));
        rosterService.deleteCycle(10L);

        verify(overrideRepository).deleteByCycleIdNative(10L);
        verify(assignmentRepository).deleteByCycleIdNative(10L);
        verify(cycleRepository).deleteCycleByIdNative(10L);
    }

    @Test
    void testDeleteCycle_ThrowsNotFound_WhenCycleDoesNotExist() {
        var auth = new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        when(cycleRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> rosterService.deleteCycle(999L));
    }

    @Test
    void testDeleteCycle_ThrowsAccessDenied_WhenNotAdmin() {
        var auth = new UsernamePasswordAuthenticationToken(
                "emp001", null, List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> rosterService.deleteCycle(10L));
    }

    private Shift createShift(Long id, ShiftType type, int capacity) {
        Shift s = new Shift();
        s.setId(id);
        s.setShiftType(type);
        s.setCapacity(capacity);
        s.setActive(true);
        if (type == ShiftType.MORNING) {
            s.setStartTime(java.time.LocalTime.of(7, 0));
            s.setEndTime(java.time.LocalTime.of(15, 0));
            s.setOvernight(false);
        } else if (type == ShiftType.GENERAL) {
            s.setStartTime(java.time.LocalTime.of(9, 30));
            s.setEndTime(java.time.LocalTime.of(18, 0));
            s.setOvernight(false);
        } else if (type == ShiftType.EVENING) {
            s.setStartTime(java.time.LocalTime.of(14, 0));
            s.setEndTime(java.time.LocalTime.of(22, 0));
            s.setOvernight(false);
        } else if (type == ShiftType.NIGHT) {
            s.setStartTime(java.time.LocalTime.of(22, 0));
            s.setEndTime(java.time.LocalTime.of(7, 0));
            s.setOvernight(true);
        }
        return s;
    }

    private Employee createEmployee(Long id, String code, String first, String last, Gender gender) {
        Employee e = new Employee();
        e.setId(id);
        e.setEmployeeCode(code);
        e.setFirstName(first);
        e.setLastName(last);
        e.setGender(gender);
        e.setActive(true);
        return e;
    }
}
