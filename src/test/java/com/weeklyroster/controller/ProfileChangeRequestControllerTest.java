package com.weeklyroster.controller;

import com.weeklyroster.dto.request.CreateProfileChangeRequest;
import com.weeklyroster.dto.request.ProfileChangeDecisionRequest;
import com.weeklyroster.dto.response.ProfileChangeRequestResponse;
import com.weeklyroster.entity.ProfileChangeStatus;
import com.weeklyroster.service.ProfileChangeRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileChangeRequestControllerTest {

    @Mock
    private ProfileChangeRequestService profileChangeRequestService;

    @InjectMocks
    private ProfileChangeRequestController employeeController;

    @InjectMocks
    private AdminProfileChangeRequestController adminController;

    private ProfileChangeRequestResponse sampleResponse;

    @BeforeEach
    void setUp() {
        sampleResponse = new ProfileChangeRequestResponse(
                1L,
                10L,
                "EMP001",
                "Rajat Maurya",
                "firstName",
                "Rajat",
                "Rajendra",
                ProfileChangeStatus.PENDING,
                LocalDateTime.now(),
                null,
                null
        );
    }

    @Test
    @DisplayName("Employee Controller: submit request returns 201 Created")
    void testSubmitRequest() {
        CreateProfileChangeRequest req = new CreateProfileChangeRequest("firstName", "Rajendra");
        when(profileChangeRequestService.submitRequest(any())).thenReturn(sampleResponse);

        ResponseEntity<ProfileChangeRequestResponse> res = employeeController.submit(req);

        assertEquals(HttpStatus.CREATED, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("firstName", res.getBody().fieldName());
        verify(profileChangeRequestService).submitRequest(req);
    }

    @Test
    @DisplayName("Employee Controller: get my requests returns 200 OK")
    void testMyRequests() {
        when(profileChangeRequestService.getMyRequests()).thenReturn(List.of(sampleResponse));

        ResponseEntity<List<ProfileChangeRequestResponse>> res = employeeController.myRequests();

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(1, res.getBody().size());
        verify(profileChangeRequestService).getMyRequests();
    }

    @Test
    @DisplayName("Admin Controller: get pending requests returns 200 OK")
    void testAdminPending() {
        when(profileChangeRequestService.getPendingRequests()).thenReturn(List.of(sampleResponse));

        ResponseEntity<List<ProfileChangeRequestResponse>> res = adminController.getPendingRequests();

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(1, res.getBody().size());
        verify(profileChangeRequestService).getPendingRequests();
    }

    @Test
    @DisplayName("Admin Controller: approve request returns 200 OK")
    void testAdminApprove() {
        ProfileChangeRequestResponse approvedResponse = new ProfileChangeRequestResponse(
                1L, 10L, "EMP001", "Rajat Maurya", "firstName", "Rajat", "Rajendra",
                ProfileChangeStatus.APPROVED, LocalDateTime.now(), LocalDateTime.now(), "Approved"
        );
        ProfileChangeDecisionRequest decision = new ProfileChangeDecisionRequest("Approved");
        when(profileChangeRequestService.approve(eq(1L), any())).thenReturn(approvedResponse);

        ResponseEntity<ProfileChangeRequestResponse> res = adminController.approve(1L, decision);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(ProfileChangeStatus.APPROVED, res.getBody().status());
        verify(profileChangeRequestService).approve(1L, decision);
    }

    @Test
    @DisplayName("Admin Controller: reject request returns 200 OK")
    void testAdminReject() {
        ProfileChangeRequestResponse rejectedResponse = new ProfileChangeRequestResponse(
                1L, 10L, "EMP001", "Rajat Maurya", "firstName", "Rajat", "Rajendra",
                ProfileChangeStatus.REJECTED, LocalDateTime.now(), LocalDateTime.now(), "Rejected"
        );
        ProfileChangeDecisionRequest decision = new ProfileChangeDecisionRequest("Rejected");
        when(profileChangeRequestService.reject(eq(1L), any())).thenReturn(rejectedResponse);

        ResponseEntity<ProfileChangeRequestResponse> res = adminController.reject(1L, decision);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(ProfileChangeStatus.REJECTED, res.getBody().status());
        verify(profileChangeRequestService).reject(1L, decision);
    }
}
