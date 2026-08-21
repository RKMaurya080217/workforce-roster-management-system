package com.weeklyroster.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.weeklyroster.dto.request.ApplyLeaveRequest;
import com.weeklyroster.dto.request.CancelLeaveRequest;
import com.weeklyroster.dto.request.EmployeeRequest;
import com.weeklyroster.dto.request.ModifyLeaveRequest;
import com.weeklyroster.dto.request.UpdateShiftRequest;
import com.weeklyroster.dto.response.EmployeeResponse;
import com.weeklyroster.dto.response.LeaveResponse;
import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.dto.response.ShiftResponse;
import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.LeaveStatus;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.service.EmployeeService;
import com.weeklyroster.service.LeaveService;
import com.weeklyroster.service.RosterService;
import com.weeklyroster.service.ShiftService;

@ExtendWith(MockitoExtension.class)
class WorkspaceEndpointsTest {

    @Mock
    private RosterService rosterService;

    @Mock
    private LeaveService leaveService;

    @Mock
    private EmployeeService employeeService;

    @Mock
    private ShiftService shiftService;

    @InjectMocks
    private RosterController rosterController;

    @InjectMocks
    private LeaveController leaveController;

    @InjectMocks
    private EmployeeController employeeController;

    @InjectMocks
    private ShiftController shiftController;

    @Test
    void testEmployeeRoster_LongPathVariableResolvesSuccessfully() {
        Long empId = 1L;
        RosterAssignmentResponse assignment = new RosterAssignmentResponse(
                10L, 100L, LocalDate.of(2026, 8, 17), empId, "EMP001", "Rajat Maurya",
                Gender.MALE, ShiftType.MORNING, false, false, false
        );

        when(rosterService.employeeRoster(empId)).thenReturn(List.of(assignment));

        ResponseEntity<List<RosterAssignmentResponse>> response = rosterController.employeeRoster(empId);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals(empId, response.getBody().get(0).employeeId());
        verify(rosterService, times(1)).employeeRoster(empId);
    }

    @Test
    void testMyLeaves_LongPathVariableResolvesSuccessfully() {
        Long empId = 1L;
        LeaveResponse leave = new LeaveResponse(
                5L, empId, "EMP001", "Rajat Maurya",
                LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 18),
                "Personal", LeaveStatus.APPROVED, null,
                LocalDateTime.now(), LocalDateTime.now(),
                null, null, null, null, null, null, null
        );

        when(leaveService.myLeaves(empId)).thenReturn(List.of(leave));

        ResponseEntity<List<LeaveResponse>> response = leaveController.myLeaves(empId);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals(empId, response.getBody().get(0).employeeId());
        verify(leaveService, times(1)).myLeaves(empId);
    }

    @Test
    void testGetEmployeeById_LongPathVariableResolvesSuccessfully() {
        Long empId = 1L;
        EmployeeResponse employee = new EmployeeResponse(
                empId, "EMP001", "Rajat", "Maurya", "rajat@example.com", Gender.MALE, true, "emp001"
        );

        when(employeeService.getById(empId)).thenReturn(employee);

        ResponseEntity<EmployeeResponse> response = employeeController.getById(empId);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(empId, response.getBody().id());
        verify(employeeService, times(1)).getById(empId);
    }

    @Test
    void testUpdateShift_LongPathVariableResolvesSuccessfully() {
        Long shiftId = 4L;
        UpdateShiftRequest request = new UpdateShiftRequest(1, null, null, null);
        ShiftResponse shiftResponse = new ShiftResponse(
                shiftId, ShiftType.NIGHT, 1, 1, true, null, null, true, "22:00 - 07:00 next day"
        );

        when(shiftService.update(eq(shiftId), any(UpdateShiftRequest.class))).thenReturn(shiftResponse);

        ResponseEntity<ShiftResponse> response = shiftController.update(shiftId, request);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(shiftId, response.getBody().id());
        verify(shiftService, times(1)).update(eq(shiftId), any(UpdateShiftRequest.class));
    }

    @Test
    void testRosterGenerate_RequestParamResolvesSuccessfully() {
        LocalDate startDate = LocalDate.of(2026, 8, 17);
        RosterCycleResponse cycle = new RosterCycleResponse(100L, startDate, startDate.plusDays(6), LocalDateTime.now(), List.of());

        when(rosterService.generateWeeklyRoster(startDate, com.weeklyroster.entity.GenerationMode.MANUAL)).thenReturn(cycle);

        ResponseEntity<RosterCycleResponse> response = rosterController.generate(startDate);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(startDate, response.getBody().startDate());
        verify(rosterService, times(1)).generateWeeklyRoster(startDate, com.weeklyroster.entity.GenerationMode.MANUAL);
    }

    @Test
    void testLeaveModificationAndCancellation_LongPathVariablesResolveSuccessfully() {
        Long leaveId = 12L;
        ModifyLeaveRequest modReq = new ModifyLeaveRequest(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 17), "Shortened");
        LeaveResponse modResp = new LeaveResponse(leaveId, 1L, "EMP001", "Rajat Maurya",
                LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 18),
                "Personal", LeaveStatus.PENDING_MODIFICATION, null,
                LocalDateTime.now(), null,
                LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 18),
                LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 17),
                "Shortened", null, LocalDateTime.now());

        when(leaveService.requestModification(eq(leaveId), any(ModifyLeaveRequest.class))).thenReturn(modResp);

        ResponseEntity<LeaveResponse> res = leaveController.requestModification(leaveId, modReq);
        assertNotNull(res);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(LeaveStatus.PENDING_MODIFICATION, res.getBody().status());

        CancelLeaveRequest cancelReq = new CancelLeaveRequest("Cancel");
        LeaveResponse cancelResp = new LeaveResponse(leaveId, 1L, "EMP001", "Rajat Maurya",
                LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 18),
                "Personal", LeaveStatus.PENDING_CANCELLATION, null,
                LocalDateTime.now(), null,
                null, null, null, null, null, "Cancel", LocalDateTime.now());

        when(leaveService.requestCancellation(eq(leaveId), any(CancelLeaveRequest.class))).thenReturn(cancelResp);

        ResponseEntity<LeaveResponse> resCancel = leaveController.requestCancellation(leaveId, cancelReq);
        assertNotNull(resCancel);
        assertEquals(HttpStatus.OK, resCancel.getStatusCode());
        assertEquals(LeaveStatus.PENDING_CANCELLATION, resCancel.getBody().status());
    }
}
