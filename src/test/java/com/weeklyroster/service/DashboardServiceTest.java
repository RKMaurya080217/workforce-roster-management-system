package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.weeklyroster.dto.response.DashboardDayViewResponse;
import com.weeklyroster.dto.response.DashboardDetailResponse;
import com.weeklyroster.dto.response.DashboardEmployeeViewResponse;
import com.weeklyroster.dto.response.DashboardResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.LeaveStatus;
import com.weeklyroster.entity.RosterAssignment;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.LeaveRequestRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.ShiftRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private RosterAssignmentRepository assignmentRepository;
    @Mock
    private LeaveRequestRepository leaveRepository;
    @Mock
    private RosterCycleRepository cycleRepository;
    @Mock
    private ShiftRepository shiftRepository;

    @InjectMocks
    private DashboardService dashboardService;

    private Employee employee;
    private Shift morningShift;
    private Shift generalShift;
    private Shift eveningShift;
    private Shift nightShift;
    private Shift offShift;

    @BeforeEach
    void setUp() {
        employee = new Employee();
        employee.setId(1L);
        employee.setEmployeeCode("EMP001");
        employee.setFirstName("Aarav");
        employee.setLastName("Sharma");
        employee.setEmail("aarav@company.com");
        employee.setGender(Gender.MALE);
        employee.setActive(true);

        morningShift = createShift(10L, ShiftType.MORNING, LocalTime.of(7, 0), LocalTime.of(15, 0), 2, false);
        generalShift = createShift(20L, ShiftType.GENERAL, LocalTime.of(9, 30), LocalTime.of(18, 0), 2, false);
        eveningShift = createShift(30L, ShiftType.EVENING, LocalTime.of(14, 0), LocalTime.of(22, 0), 2, false);
        nightShift = createShift(40L, ShiftType.NIGHT, LocalTime.of(22, 0), LocalTime.of(7, 0), 1, true);
        offShift = createShift(50L, ShiftType.OFF, null, null, 1, false);
    }

    @Test
    void testDashboard_ReturnsCounts() {
        when(employeeRepository.count()).thenReturn(10L);
        when(employeeRepository.countByActiveTrue()).thenReturn(8L);
        when(employeeRepository.countByActiveFalse()).thenReturn(2L);
        when(leaveRepository.countByStatus(LeaveStatus.PENDING)).thenReturn(1L);

        DashboardResponse response = dashboardService.dashboard();

        assertNotNull(response);
        assertEquals(10L, response.totalEmployees());
        assertEquals(8L, response.activeEmployees());
        assertEquals(2L, response.inactiveEmployees());
        assertEquals(1L, response.pendingLeaveRequests());
    }

    @Test
    void testDashboardDetails_ReturnsEnrichedData() {
        when(employeeRepository.count()).thenReturn(10L);
        when(employeeRepository.countByActiveTrue()).thenReturn(8L);
        when(employeeRepository.countByActiveFalse()).thenReturn(2L);
        when(leaveRepository.countByStatus(LeaveStatus.PENDING)).thenReturn(1L);

        RosterAssignment assignment = new RosterAssignment();
        assignment.setId(100L);
        assignment.setEmployee(employee);
        assignment.setShift(morningShift);
        assignment.setRosterDate(LocalDate.now());

        when(assignmentRepository.findByRosterDate(any(LocalDate.class))).thenReturn(List.of(assignment));
        when(leaveRepository.findByStatusOrderByRequestedAtAsc(LeaveStatus.PENDING)).thenReturn(List.of());
        when(employeeRepository.findAllByOrderByIdAsc()).thenReturn(List.of(employee));
        when(cycleRepository.findAllByOrderByStartDateDesc()).thenReturn(List.of());

        DashboardDetailResponse response = dashboardService.dashboardDetails();

        assertNotNull(response);
        assertEquals(1, response.todaysAssignments().size());
        assertEquals(1, response.activeEmployees().size());
    }

    @Test
    @DisplayName("Should build 7-day dayView grouping correctly")
    void testDayView() {
        LocalDate start = LocalDate.of(2026, 8, 24);
        LocalDate end = start.plusDays(6);

        RosterCycle cycle = new RosterCycle();
        cycle.setId(1L);
        cycle.setStartDate(start);
        cycle.setEndDate(end);

        RosterAssignment a1 = new RosterAssignment();
        a1.setId(10L);
        a1.setCycle(cycle);
        a1.setEmployee(employee);
        a1.setShift(morningShift);
        a1.setRosterDate(start);

        when(cycleRepository.findById(1L)).thenReturn(Optional.of(cycle));
        when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(morningShift, generalShift, eveningShift, nightShift, offShift));
        when(assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(cycle)).thenReturn(List.of(a1));

        DashboardDayViewResponse dayView = dashboardService.dayView(1L);

        assertNotNull(dayView);
        assertEquals(7, dayView.days().size(), "Day view must contain 7 days (Monday to Sunday)");
        DashboardDayViewResponse.DayScheduleDto monday = dayView.days().get(0);
        assertEquals("Monday", monday.dayOfWeek());
        assertEquals(1, monday.totalWorking());
        assertEquals(1, monday.morning().assigned());
        assertEquals("07:00 - 15:00", monday.morning().timing());
    }

    @Test
    @DisplayName("Should build employeeView with 7-day slots and shift counts")
    void testEmployeeView() {
        LocalDate start = LocalDate.of(2026, 8, 24);
        LocalDate end = start.plusDays(6);

        RosterCycle cycle = new RosterCycle();
        cycle.setId(1L);
        cycle.setStartDate(start);
        cycle.setEndDate(end);

        RosterAssignment a1 = new RosterAssignment();
        a1.setId(10L);
        a1.setCycle(cycle);
        a1.setEmployee(employee);
        a1.setShift(nightShift);
        a1.setRosterDate(start);

        when(cycleRepository.findById(1L)).thenReturn(Optional.of(cycle));
        when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(morningShift, generalShift, eveningShift, nightShift, offShift));
        when(employeeRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(employee));
        when(assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(cycle)).thenReturn(List.of(a1));

        DashboardEmployeeViewResponse empView = dashboardService.employeeView(1L);

        assertNotNull(empView);
        assertEquals(1, empView.employees().size());
        DashboardEmployeeViewResponse.EmployeeScheduleDto empDto = empView.employees().get(0);
        assertEquals("Aarav Sharma", empDto.employeeName());
        assertEquals(1, empDto.workingDaysCount());
        assertEquals(1, empDto.nightShiftsCount());
        assertEquals(7, empDto.schedule().size());
        assertEquals("NIGHT", empDto.schedule().get(0).shiftType());
    }

    private Shift createShift(Long id, ShiftType type, LocalTime start, LocalTime end, int cap, boolean overnight) {
        Shift s = new Shift();
        s.setId(id);
        s.setShiftType(type);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setCapacity(cap);
        s.setOvernight(overnight);
        s.setActive(true);
        return s;
    }
}
