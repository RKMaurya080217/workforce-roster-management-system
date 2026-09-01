package com.weeklyroster.service;

import com.weeklyroster.controller.SmartCommandCenterController;
import com.weeklyroster.dto.response.RosterHealthReport;
import com.weeklyroster.dto.response.SmartCommandCenterResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class Batch42SmartCommandCenterE2ETest {

    @Mock
    private RosterCycleRepository cycleRepository;

    @Mock
    private RosterAssignmentRepository assignmentRepository;

    @Mock
    private RosterHealthService healthService;

    @Mock
    private LeaveRequestRepository leaveRequestRepository;

    @Mock
    private EmployeePreferenceRepository preferenceRepository;

    @Mock
    private RosterOverrideRepository overrideRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private RosterService rosterService;

    @Mock
    private AuditService auditService;

    @Mock
    private SseEmitterService sseEmitterService;

    @InjectMocks
    private SmartCommandCenterService commandCenterService;

    private SmartCommandCenterController controller;

    private RosterCycle mockCycle;

    private RosterHealthReport buildHealthyReport() {
        return new RosterHealthReport(
                201L, mockCycle.getStartDate(), mockCycle.getEndDate(),
                RosterStatus.TENTATIVE, true, "VALID",
                "PASSED", "PASSED", "PASSED", "PASSED", "PASSED",
                "PASSED", "PASSED", "PASSED", "PASSED",
                0, 0, 0, 0, 0,
                Collections.emptyList(),
                94.0, 96.0, "All Satisfied", "VALID",
                93.0, 92.0
        );
    }

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "pass", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );

        controller = new SmartCommandCenterController(commandCenterService);

        mockCycle = new RosterCycle();
        mockCycle.setId(201L);
        mockCycle.setStartDate(LocalDate.of(2026, 9, 7));
        mockCycle.setEndDate(LocalDate.of(2026, 9, 13));
        mockCycle.setStatus(RosterStatus.TENTATIVE);
        mockCycle.setGenerationMode(GenerationMode.AUTOMATIC);
        mockCycle.setGeneratedAt(LocalDateTime.now());
    }

    @Test
    @DisplayName("Test 1: Smart Command Center Summary Loads For Active Cycle")
    void testActiveCycleSummaryLoads() {
        when(cycleRepository.findTopByOrderByStartDateDesc()).thenReturn(Optional.of(mockCycle));
        when(healthService.evaluateHealth(any(), any())).thenReturn(buildHealthyReport());

        ResponseEntity<SmartCommandCenterResponse> response = controller.getActiveCycleSummary();

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        SmartCommandCenterResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(201L, body.cycleId());
        assertEquals(RosterStatus.TENTATIVE, body.status());
        assertNotNull(body.smartSummary());
    }

    @Test
    @DisplayName("Test 2: Smart Command Center Throws ResourceNotFound When No Cycles Exist")
    void testNoCycleThrowsNotFoundForEmptyState() {
        when(cycleRepository.findTopByOrderByStartDateDesc()).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> controller.getActiveCycleSummary());
    }

    @Test
    @DisplayName("Test 3: Publish Cycle Delegated to RosterService and Returns Summary")
    void testPublishCycle() {
        when(cycleRepository.findById(201L)).thenReturn(Optional.of(mockCycle));
        when(healthService.evaluateHealth(any(), any())).thenReturn(buildHealthyReport());

        ResponseEntity<SmartCommandCenterResponse> response = controller.publishCycle(201L);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        verify(rosterService, times(1)).publishRoster(201L);
    }

    @Test
    @DisplayName("Test 4: Lock Cycle Delegated to RosterService and Returns Summary")
    void testLockCycle() {
        when(cycleRepository.findById(201L)).thenReturn(Optional.of(mockCycle));
        when(healthService.evaluateHealth(any(), any())).thenReturn(buildHealthyReport());

        ResponseEntity<SmartCommandCenterResponse> response = controller.lockCycle(201L);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        verify(rosterService, times(1)).lockRoster(201L);
    }
}
