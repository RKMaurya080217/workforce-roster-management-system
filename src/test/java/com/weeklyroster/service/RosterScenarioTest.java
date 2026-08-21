package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.RosterAssignment;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.LeaveRequestRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.RosterOverrideRepository;
import com.weeklyroster.repository.ShiftRepository;
import com.weeklyroster.repository.EmailDeliveryLogRepository;

public class RosterScenarioTest {

    private EmployeeRepository employeeRepository;
    private ShiftRepository shiftRepository;
    private RosterCycleRepository cycleRepository;
    private RosterAssignmentRepository assignmentRepository;
    private RosterOverrideRepository overrideRepository;
    private LeaveRequestRepository leaveRepository;

    private RosterService rosterService;
    private List<Employee> testEmployees;
    private List<Shift> standardShifts;

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
        Employee e6 = createEmployee(6L, "EMP006", "Divyansh", "Sharma", Gender.MALE);
        testEmployees = List.of(e1, e2, e3, e4, e5, e6);

        standardShifts = List.of(
                createShift(1L, ShiftType.MORNING, 1),
                createShift(2L, ShiftType.GENERAL, 1),
                createShift(3L, ShiftType.EVENING, 1),
                createShift(4L, ShiftType.NIGHT, 1),
                createShift(5L, ShiftType.OFF, 0)
        );
    }

    private void mockCycleSave() {
        lenient().when(cycleRepository.findByStartDateAndEndDate(any(LocalDate.class), any(LocalDate.class))).thenReturn(Optional.empty());
        lenient().when(cycleRepository.save(any(RosterCycle.class))).thenAnswer(inv -> {
            RosterCycle c = inv.getArgument(0);
            c.setId(101L);
            return c;
        });
        lenient().when(assignmentRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(assignmentRepository.findWorkedAssignmentsBefore(anyLong(), any(LocalDate.class))).thenReturn(Collections.emptyList());
    }

    @Test
    @DisplayName("Scenario 1: Normal roster generation with sufficient employees")
    void testScenario1_NormalGeneration() {
        when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(standardShifts);
        when(employeeRepository.findByActiveTrueOrderByIdAsc()).thenReturn(testEmployees);
        mockCycleSave();

        LocalDate startDate = LocalDate.of(2026, 9, 7);
        RosterCycleResponse response = rosterService.generateWeeklyRoster(startDate);

        assertNotNull(response);
        assertEquals(7 * testEmployees.size(), response.assignments().size());
        assertEquals(startDate, response.startDate());
        assertEquals(startDate.plusDays(6), response.endDate());
    }

    @Test
    @DisplayName("Scenario 2: Insufficient total employees triggers BusinessException")
    void testScenario2_InsufficientTotalEmployees() {
        when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(standardShifts);
        when(employeeRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(testEmployees.get(0)));

        assertThrows(BusinessException.class, () -> rosterService.generateWeeklyRoster());
    }

    @Test
    @DisplayName("Scenario 3: Insufficient male employees for evening/night triggers BusinessException")
    void testScenario3_InsufficientMaleEmployees() {
        Employee f1 = createEmployee(1L, "EMP001", "Prachi", "Mishra", Gender.FEMALE);
        Employee f2 = createEmployee(2L, "EMP002", "Sapna", "Pandey", Gender.FEMALE);
        Employee f3 = createEmployee(3L, "EMP003", "Pooja", "Singh", Gender.FEMALE);
        Employee f4 = createEmployee(4L, "EMP004", "Neha", "Sharma", Gender.FEMALE);
        Employee m1 = createEmployee(5L, "EMP005", "Rajat", "Maurya", Gender.MALE);

        when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(standardShifts);
        when(employeeRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(f1, f2, f3, f4, m1));

        assertThrows(BusinessException.class, () -> rosterService.generateWeeklyRoster());
    }

    @Test
    @DisplayName("Scenario 4: Approved leave sets employee to OFF and keeps coverage")
    void testScenario4_ApprovedLeaveHandling() {
        when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(standardShifts);
        when(employeeRepository.findByActiveTrueOrderByIdAsc()).thenReturn(testEmployees);
        mockCycleSave();

        LocalDate leaveDate = LocalDate.of(2026, 9, 9);
        when(leaveRepository.existsByEmployeeIdAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                eq(1L), any(), eq(leaveDate), eq(leaveDate))).thenReturn(true);

        RosterCycleResponse response = rosterService.generateWeeklyRoster(LocalDate.of(2026, 9, 7));

        assertNotNull(response);
        var leaveAssignment = response.assignments().stream()
                .filter(a -> a.employeeId().equals(1L) && a.rosterDate().equals(leaveDate))
                .findFirst();

        assertTrue(leaveAssignment.isPresent());
        assertTrue(leaveAssignment.get().onLeave());
        assertEquals(ShiftType.OFF, leaveAssignment.get().shiftType());
    }

    @Test
    @DisplayName("Scenario 5: Female employees strictly NEVER assigned to EVENING or NIGHT")
    void testScenario5_FemaleEmployeeRestrictions() {
        when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(standardShifts);
        when(employeeRepository.findByActiveTrueOrderByIdAsc()).thenReturn(testEmployees);
        mockCycleSave();

        RosterCycleResponse response = rosterService.generateWeeklyRoster(LocalDate.of(2026, 9, 7));

        for (var a : response.assignments()) {
            if (a.gender() == Gender.FEMALE) {
                assertNotEquals(ShiftType.EVENING, a.shiftType(),
                        "Female employee " + a.employeeName() + " assigned to EVENING on " + a.rosterDate());
                assertNotEquals(ShiftType.NIGHT, a.shiftType(),
                        "Female employee " + a.employeeName() + " assigned to NIGHT on " + a.rosterDate());
                assertTrue(a.shiftType() == ShiftType.MORNING || a.shiftType() == ShiftType.GENERAL || a.shiftType() == ShiftType.OFF,
                        "Female employee should only have MORNING, GENERAL, or OFF");
            }
        }
    }

    @Test
    @DisplayName("Scenario 6: Every employee gets exactly 1 weekly off per cycle")
    void testScenario6_SingleWeeklyOffPerCycle() {
        when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(standardShifts);
        when(employeeRepository.findByActiveTrueOrderByIdAsc()).thenReturn(testEmployees);
        mockCycleSave();

        RosterCycleResponse response = rosterService.generateWeeklyRoster(LocalDate.of(2026, 9, 7));

        for (Employee emp : testEmployees) {
            long offCount = response.assignments().stream()
                    .filter(a -> a.employeeId().equals(emp.getId()) && a.weeklyOff())
                    .count();
            assertEquals(1, offCount, "Employee " + emp.getEmployeeCode() + " must have exactly 1 weekly off");
        }
    }

    @Test
    @DisplayName("Scenario 7: Shift override respects minimum coverage")
    void testScenario7_ShiftOverrideValidation() {
        Shift morning = createShift(1L, ShiftType.MORNING, 1);
        Shift evening = createShift(3L, ShiftType.EVENING, 1);

        RosterAssignment a = new RosterAssignment();
        a.setId(10L);
        a.setEmployee(testEmployees.get(0));
        a.setShift(morning);
        a.setRosterDate(LocalDate.of(2026, 9, 7));
        a.setWeeklyOff(false);
        a.setOnLeave(false);

        when(assignmentRepository.findById(10L)).thenReturn(Optional.of(a));
        when(shiftRepository.findByShiftType(ShiftType.EVENING)).thenReturn(Optional.of(evening));
        when(assignmentRepository.countByRosterDateAndShiftShiftTypeAndWeeklyOffFalseAndOnLeaveFalse(any(), eq(ShiftType.MORNING)))
                .thenReturn(1L); // only 1 person in morning -> reducing would leave 0

        assertThrows(BusinessException.class, () ->
                rosterService.changeShift(10L, new com.weeklyroster.dto.request.ShiftChangeRequest(ShiftType.EVENING, "Test override")));
    }

    @Test
    @DisplayName("Scenario 8: Overwriting existing cycle cleans up previous records")
    void testScenario8_OverwriteExistingCycle() {
        when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(standardShifts);
        when(employeeRepository.findByActiveTrueOrderByIdAsc()).thenReturn(testEmployees);

        RosterCycle existing = new RosterCycle();
        existing.setId(99L);
        existing.setStartDate(LocalDate.of(2026, 9, 7));
        existing.setEndDate(LocalDate.of(2026, 9, 13));

        when(cycleRepository.findByStartDateAndEndDate(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 13)))
                .thenReturn(Optional.of(existing));
        when(cycleRepository.findOverlappingCycles(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 13)))
                .thenReturn(List.of(existing));
        when(cycleRepository.save(any(RosterCycle.class))).thenAnswer(inv -> inv.getArgument(0));
        when(assignmentRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(assignmentRepository.findWorkedAssignmentsBefore(anyLong(), any(LocalDate.class))).thenReturn(Collections.emptyList());

        RosterCycleResponse response = rosterService.generateWeeklyRoster(LocalDate.of(2026, 9, 7));

        assertNotNull(response);
        verify(overrideRepository).deleteByCycleIdNative(99L);
        verify(assignmentRepository).deleteByCycleIdNative(99L);
        verify(cycleRepository).deleteCycleByIdNative(99L);
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
