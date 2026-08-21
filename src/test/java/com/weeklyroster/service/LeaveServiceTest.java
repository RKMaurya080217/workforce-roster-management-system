package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.weeklyroster.dto.request.ApplyLeaveRequest;
import com.weeklyroster.dto.request.LeaveDecisionRequest;
import com.weeklyroster.dto.response.LeaveResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.LeaveRequest;
import com.weeklyroster.entity.LeaveStatus;
import com.weeklyroster.entity.RosterAssignment;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.entity.User;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.LeaveRequestRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.ShiftRepository;

@ExtendWith(MockitoExtension.class)
class LeaveServiceTest {

    @Mock
    private LeaveRequestRepository leaveRepository;
    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private RosterAssignmentRepository assignmentRepository;
    @Mock
    private ShiftRepository shiftRepository;

    @InjectMocks
    private LeaveService leaveService;

    private Employee employee;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId(10L);
        user.setUsername("emp001");

        employee = new Employee();
        employee.setId(1L);
        employee.setEmployeeCode("EMP001");
        employee.setFirstName("Rajat");
        employee.setLastName("Maurya");
        employee.setEmail("rajat@cris.com");
        employee.setGender(Gender.MALE);
        employee.setActive(true);
        employee.setUser(user);

        var auth = new UsernamePasswordAuthenticationToken("emp001", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void testApply_Fails_WhenEndDateBeforeStartDate() {
        ApplyLeaveRequest request = new ApplyLeaveRequest(1L, LocalDate.now().plusDays(5), LocalDate.now().plusDays(2), "Medical");

        assertThrows(BusinessException.class, () -> leaveService.apply(request));
    }

    @Test
    void testApply_Fails_WhenOverlappingLeaveExists() {
        ApplyLeaveRequest request = new ApplyLeaveRequest(1L, LocalDate.now().plusDays(1), LocalDate.now().plusDays(3), "Vacation");

        when(employeeRepository.findById(1L)).thenReturn(Optional.of(employee));
        when(leaveRepository.existsOverlappingLeave(eq(1L), anyList(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(true);

        assertThrows(BusinessException.class, () -> leaveService.apply(request));
    }

    @Test
    void testApprove_UpdatesRosterAssignments() {
        LeaveRequest leave = new LeaveRequest();
        leave.setId(100L);
        leave.setEmployee(employee);
        leave.setStartDate(LocalDate.now().plusDays(1));
        leave.setEndDate(LocalDate.now().plusDays(2));
        leave.setStatus(LeaveStatus.PENDING);
        leave.setRequestedAt(LocalDateTime.now());

        RosterAssignment assignment = new RosterAssignment();
        assignment.setId(50L);
        assignment.setEmployee(employee);
        assignment.setRosterDate(LocalDate.now().plusDays(1));
        assignment.setOnLeave(false);

        Shift offShift = new Shift();
        offShift.setId(99L);
        offShift.setShiftType(ShiftType.OFF);

        when(leaveRepository.findById(100L)).thenReturn(Optional.of(leave));
        when(shiftRepository.findByShiftType(ShiftType.OFF)).thenReturn(Optional.of(offShift));
        when(assignmentRepository.findByEmployeeIdAndRosterDateBetween(eq(1L), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(assignment));

        LeaveResponse response = leaveService.approve(100L, new LeaveDecisionRequest("Approved by Admin"));

        assertEquals(LeaveStatus.APPROVED, response.status());
        assertTrue(assignment.isOnLeave());
        assertEquals(ShiftType.OFF, assignment.getShift().getShiftType());
        verify(assignmentRepository).saveAll(anyList());
    }

    @Test
    void testRequestModification_ShortenTwoDaysToOneDay_Success() {
        LocalDate day1 = LocalDate.of(2026, 8, 17);
        LocalDate day2 = LocalDate.of(2026, 8, 18);

        LeaveRequest leave = new LeaveRequest();
        leave.setId(100L);
        leave.setEmployee(employee);
        leave.setStartDate(day1);
        leave.setEndDate(day2);
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setRequestedAt(LocalDateTime.now().minusDays(2));

        when(leaveRepository.findById(100L)).thenReturn(Optional.of(leave));
        when(leaveRepository.existsOverlappingLeaveExcludingId(eq(1L), anyList(), eq(day1), eq(day1), eq(100L)))
                .thenReturn(false);
        when(leaveRepository.save(any(LeaveRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var modReq = new com.weeklyroster.dto.request.ModifyLeaveRequest(day1, day1, "Shortened to 1 day only");
        LeaveResponse response = leaveService.requestModification(100L, modReq);

        assertEquals(LeaveStatus.PENDING_MODIFICATION, response.status());
        assertEquals(day1, response.pendingStartDate());
        assertEquals(day1, response.pendingEndDate());
        assertEquals("Shortened to 1 day only", response.modificationReason());
    }

    @Test
    void testApproveModification_ReleasesDayTwo_AndSynchronizesRoster() {
        LocalDate day1 = LocalDate.of(2026, 8, 17);
        LocalDate day2 = LocalDate.of(2026, 8, 18);

        LeaveRequest leave = new LeaveRequest();
        leave.setId(100L);
        leave.setEmployee(employee);
        leave.setStartDate(day1);
        leave.setEndDate(day2);
        leave.setPendingStartDate(day1);
        leave.setPendingEndDate(day1);
        leave.setStatus(LeaveStatus.PENDING_MODIFICATION);
        leave.setModificationReason("Shortened");
        leave.setRequestedAt(LocalDateTime.now().minusDays(2));

        RosterAssignment assignDay1 = new RosterAssignment();
        assignDay1.setId(101L);
        assignDay1.setEmployee(employee);
        assignDay1.setRosterDate(day1);
        assignDay1.setOnLeave(true);

        RosterAssignment assignDay2 = new RosterAssignment();
        assignDay2.setId(102L);
        assignDay2.setEmployee(employee);
        assignDay2.setRosterDate(day2);
        assignDay2.setOnLeave(true);

        Shift offShift = new Shift();
        offShift.setId(99L);
        offShift.setShiftType(ShiftType.OFF);

        when(leaveRepository.findById(100L)).thenReturn(Optional.of(leave));
        when(shiftRepository.findByShiftType(ShiftType.OFF)).thenReturn(Optional.of(offShift));
        when(assignmentRepository.findByEmployeeIdAndRosterDateBetween(1L, day1, day2))
                .thenReturn(List.of(assignDay1, assignDay2));
        when(assignmentRepository.findByEmployeeIdAndRosterDateBetween(1L, day1, day1))
                .thenReturn(List.of(assignDay1));

        LeaveResponse response = leaveService.approveModification(100L, new LeaveDecisionRequest("Approved shortened"));

        assertEquals(LeaveStatus.APPROVED, response.status());
        assertEquals(day1, response.startDate());
        assertEquals(day1, response.endDate());
        assertEquals(day1, response.originalStartDate());
        assertEquals(day2, response.originalEndDate());
        assertNull(response.pendingStartDate());

        assertTrue(assignDay1.isOnLeave());
        assertFalse(assignDay2.isOnLeave(), "Day 2 must be released from onLeave!");
    }

    @Test
    void testRequestCancellation_And_ApproveCancellation_Success() {
        LocalDate day1 = LocalDate.of(2026, 8, 17);
        LocalDate day2 = LocalDate.of(2026, 8, 18);

        LeaveRequest leave = new LeaveRequest();
        leave.setId(100L);
        leave.setEmployee(employee);
        leave.setStartDate(day1);
        leave.setEndDate(day2);
        leave.setStatus(LeaveStatus.APPROVED);

        when(leaveRepository.findById(100L)).thenReturn(Optional.of(leave));
        when(leaveRepository.save(any(LeaveRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var cancelReq = new com.weeklyroster.dto.request.CancelLeaveRequest("Trip cancelled");
        LeaveResponse cancelResp = leaveService.requestCancellation(100L, cancelReq);
        assertEquals(LeaveStatus.PENDING_CANCELLATION, cancelResp.status());

        RosterAssignment assignDay1 = new RosterAssignment();
        assignDay1.setId(101L);
        assignDay1.setEmployee(employee);
        assignDay1.setRosterDate(day1);
        assignDay1.setOnLeave(true);

        when(assignmentRepository.findByEmployeeIdAndRosterDateBetween(1L, day1, day2))
                .thenReturn(List.of(assignDay1));

        LeaveResponse approvedCancelResp = leaveService.approveCancellation(100L, new LeaveDecisionRequest("OK"));
        assertEquals(LeaveStatus.CANCELLED, approvedCancelResp.status());
        assertFalse(assignDay1.isOnLeave(), "Assignment should be released from leave");
    }

    @Test
    void testEmployeeCannotModifyOtherEmployeeLeave() {
        Employee otherEmployee = new Employee();
        otherEmployee.setId(2L);
        otherEmployee.setEmployeeCode("EMP002");

        LeaveRequest leave = new LeaveRequest();
        leave.setId(100L);
        leave.setEmployee(otherEmployee);
        leave.setStatus(LeaveStatus.APPROVED);

        // Authenticated as non-admin employee emp001 (id 1L)
        var empAuth = new UsernamePasswordAuthenticationToken("emp001", null, List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        SecurityContextHolder.getContext().setAuthentication(empAuth);
        when(employeeRepository.findByUserUsername("emp001")).thenReturn(Optional.of(employee));
        when(leaveRepository.findById(100L)).thenReturn(Optional.of(leave));

        var modReq = new com.weeklyroster.dto.request.ModifyLeaveRequest(LocalDate.now(), LocalDate.now(), "Hack");
        assertThrows(AccessDeniedException.class, () -> leaveService.requestModification(100L, modReq));
    }

    @Test
    void testRejectModification_PreservesOriginalDates() {
        LocalDate day1 = LocalDate.of(2026, 8, 17);
        LocalDate day2 = LocalDate.of(2026, 8, 18);

        LeaveRequest leave = new LeaveRequest();
        leave.setId(100L);
        leave.setEmployee(employee);
        leave.setStartDate(day1);
        leave.setEndDate(day2);
        leave.setPendingStartDate(day1);
        leave.setPendingEndDate(day1);
        leave.setStatus(LeaveStatus.PENDING_MODIFICATION);

        when(leaveRepository.findById(100L)).thenReturn(Optional.of(leave));

        LeaveResponse response = leaveService.rejectModification(100L, new LeaveDecisionRequest("Duty shortage"));

        assertEquals(LeaveStatus.APPROVED, response.status());
        assertEquals(day1, response.startDate());
        assertEquals(day2, response.endDate());
        assertNull(response.pendingStartDate());
        assertNull(response.pendingEndDate());
        assertEquals("Duty shortage", response.adminRemarks());
    }

    @Test
    void testRejectCancellation_KeepsLeaveApproved() {
        LocalDate day1 = LocalDate.of(2026, 8, 17);
        LocalDate day2 = LocalDate.of(2026, 8, 18);

        LeaveRequest leave = new LeaveRequest();
        leave.setId(100L);
        leave.setEmployee(employee);
        leave.setStartDate(day1);
        leave.setEndDate(day2);
        leave.setStatus(LeaveStatus.PENDING_CANCELLATION);

        when(leaveRepository.findById(100L)).thenReturn(Optional.of(leave));

        LeaveResponse response = leaveService.rejectCancellation(100L, new LeaveDecisionRequest("Too late to cancel"));

        assertEquals(LeaveStatus.APPROVED, response.status());
        assertEquals("Too late to cancel", response.adminRemarks());
    }

    @Test
    void testCannotModifyNonApprovedLeave() {
        LeaveRequest leave = new LeaveRequest();
        leave.setId(100L);
        leave.setEmployee(employee);
        leave.setStatus(LeaveStatus.PENDING);

        when(leaveRepository.findById(100L)).thenReturn(Optional.of(leave));

        var modReq = new com.weeklyroster.dto.request.ModifyLeaveRequest(LocalDate.now(), LocalDate.now(), "Change");
        assertThrows(BusinessException.class, () -> leaveService.requestModification(100L, modReq));
    }

    @Test
    void testCannotCancelNonApprovedLeave() {
        LeaveRequest leave = new LeaveRequest();
        leave.setId(100L);
        leave.setEmployee(employee);
        leave.setStatus(LeaveStatus.REJECTED);

        when(leaveRepository.findById(100L)).thenReturn(Optional.of(leave));

        var cancelReq = new com.weeklyroster.dto.request.CancelLeaveRequest("Cancel");
        assertThrows(BusinessException.class, () -> leaveService.requestCancellation(100L, cancelReq));
    }
}
