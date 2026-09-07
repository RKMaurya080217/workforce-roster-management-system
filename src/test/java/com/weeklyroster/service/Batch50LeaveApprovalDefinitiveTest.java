package com.weeklyroster.service;

import com.weeklyroster.controller.LeaveController;
import com.weeklyroster.controller.UnifiedApprovalController;
import com.weeklyroster.dto.request.LeaveDecisionRequest;
import com.weeklyroster.dto.response.LeaveResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.LeaveRequestRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.ShiftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class Batch50LeaveApprovalDefinitiveTest {

    @Mock
    private LeaveRequestRepository leaveRepository;
    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private RosterAssignmentRepository assignmentRepository;
    @Mock
    private ShiftRepository shiftRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private EmployeeActivityLogService activityLogService;

    @InjectMocks
    private LeaveService leaveService;

    @Mock
    private ProfileChangeRequestService profileChangeRequestService;
    @Mock
    private EmployeePreferenceService preferenceService;
    @Mock
    private RosterService rosterService;
    @Mock
    private com.weeklyroster.repository.RosterCycleRepository cycleRepository;
    @Mock
    private com.weeklyroster.repository.RosterChangeRequestRepository changeRequestRepository;
    @Mock
    private RosterReviewService rosterReviewService;

    private Employee employee;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId(10L);
        user.setUsername("admin001");

        employee = new Employee();
        employee.setId(1L);
        employee.setEmployeeCode("EMP001");
        employee.setFirstName("Rajat");
        employee.setLastName("Maurya");
        employee.setEmail("rajat@cris.com");
        employee.setGender(Gender.MALE);
        employee.setActive(true);
        employee.setUser(user);

        var adminAuth = new UsernamePasswordAuthenticationToken("admin001", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(adminAuth);
    }

    private LeaveRequest createPendingLeave(Long id) {
        LeaveRequest leave = new LeaveRequest();
        leave.setId(id);
        leave.setEmployee(employee);
        leave.setStartDate(LocalDate.now().plusDays(5));
        leave.setEndDate(LocalDate.now().plusDays(7));
        leave.setReason("Personal emergency");
        leave.setStatus(LeaveStatus.PENDING);
        leave.setRequestedAt(LocalDateTime.now().minusDays(1));
        return leave;
    }

    @Test
    @DisplayName("1. Direct LeaveController: approve endpoint maps both PUT and POST")
    void testDirectLeaveApproveMapping() throws Exception {
        Method approveMethod = LeaveController.class.getMethod("approve", Long.class, LeaveDecisionRequest.class);
        RequestMapping mapping = approveMethod.getAnnotation(RequestMapping.class);
        assertNotNull(mapping, "LeaveController.approve must have @RequestMapping");
        assertTrue(Arrays.asList(mapping.method()).contains(RequestMethod.POST), "Must support POST");
        assertTrue(Arrays.asList(mapping.method()).contains(RequestMethod.PUT), "Must support PUT");
        assertFalse(Arrays.asList(mapping.method()).contains(RequestMethod.GET), "Must NOT support GET");
    }

    @Test
    @DisplayName("2. UnifiedApprovalController: leave/approve endpoint maps both POST and PUT, rejecting GET")
    void testUnifiedLeaveApproveMapping() throws Exception {
        Method approveMethod = UnifiedApprovalController.class.getMethod("approveLeave", Long.class, LeaveDecisionRequest.class);
        RequestMapping mapping = approveMethod.getAnnotation(RequestMapping.class);
        assertNotNull(mapping, "UnifiedApprovalController.approveLeave must have @RequestMapping");
        assertTrue(Arrays.asList(mapping.method()).contains(RequestMethod.POST), "Must support POST");
        assertTrue(Arrays.asList(mapping.method()).contains(RequestMethod.PUT), "Must support PUT");
        assertFalse(Arrays.asList(mapping.method()).contains(RequestMethod.GET), "Must NOT support GET");
    }

    @Test
    @DisplayName("3. Service Layer: approve transitions leave status from PENDING to APPROVED")
    void testApproveTransitionsStatus() {
        LeaveRequest leave = createPendingLeave(101L);
        when(leaveRepository.findById(101L)).thenReturn(Optional.of(leave));

        LeaveDecisionRequest request = new LeaveDecisionRequest("Approved by Admin");
        LeaveResponse response = leaveService.approve(101L, request);

        assertEquals(LeaveStatus.APPROVED, response.status());
        assertEquals(LeaveStatus.APPROVED, leave.getStatus());
        assertEquals("Approved by Admin", response.adminRemarks());
        assertNotNull(response.reviewedAt());
    }

    @Test
    @DisplayName("4. Service Layer: reject transitions leave status from PENDING to REJECTED")
    void testRejectTransitionsStatus() {
        LeaveRequest leave = createPendingLeave(102L);
        when(leaveRepository.findById(102L)).thenReturn(Optional.of(leave));

        LeaveDecisionRequest request = new LeaveDecisionRequest("Operational coverage requirements");
        LeaveResponse response = leaveService.reject(102L, request);

        assertEquals(LeaveStatus.REJECTED, response.status());
        assertEquals(LeaveStatus.REJECTED, leave.getStatus());
        assertEquals("Operational coverage requirements", response.adminRemarks());
        assertNotNull(response.reviewedAt());
    }

    @Test
    @DisplayName("5. Duplicate Click Protection: Approving an already APPROVED leave throws BusinessException")
    void testApproveAlreadyApprovedThrows() {
        LeaveRequest leave = createPendingLeave(103L);
        leave.setStatus(LeaveStatus.APPROVED);
        when(leaveRepository.findById(103L)).thenReturn(Optional.of(leave));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                leaveService.approve(103L, new LeaveDecisionRequest("Repeat approval attempt"))
        );
        assertEquals("Leave request is already approved", ex.getMessage());
    }

    @Test
    @DisplayName("6. Duplicate Click Protection: Rejecting an already REJECTED leave throws BusinessException")
    void testRejectAlreadyRejectedThrows() {
        LeaveRequest leave = createPendingLeave(104L);
        leave.setStatus(LeaveStatus.REJECTED);
        when(leaveRepository.findById(104L)).thenReturn(Optional.of(leave));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                leaveService.reject(104L, new LeaveDecisionRequest("Repeat rejection attempt"))
        );
        assertEquals("Leave request is already rejected", ex.getMessage());
    }

    @Test
    @DisplayName("7. UnifiedApprovalService: decideLeave correctly delegates to LeaveService")
    void testUnifiedApprovalServiceDelegation() {
        UnifiedApprovalService unifiedService = new UnifiedApprovalService(
                profileChangeRequestService, leaveService, preferenceService,
                rosterService, cycleRepository, changeRequestRepository, rosterReviewService
        );

        LeaveRequest leave = createPendingLeave(105L);
        when(leaveRepository.findById(105L)).thenReturn(Optional.of(leave));

        LeaveResponse res = unifiedService.decideLeave(105L, true, new LeaveDecisionRequest("Unified approval OK"));
        assertEquals(LeaveStatus.APPROVED, res.status());
        assertEquals("Unified approval OK", res.adminRemarks());
    }

    @Test
    @DisplayName("8. index.html contains cache-busting, CSP upgrade-insecure-requests, and no-cache meta tags")
    void testIndexHtmlSecurityAndCacheBusting() throws Exception {
        InputStream is = new ClassPathResource("static/index.html").getInputStream();
        String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(html.contains("<script src=\"/app.js?v="), "index.html must have cache-busted app.js");
        assertTrue(html.contains("<script src=\"/enterprise-app.js?v="), "index.html must have cache-busted enterprise-app.js");
        assertTrue(html.contains("<link rel=\"stylesheet\" href=\"/styles.css?v="), "index.html must have cache-busted styles.css");
        assertTrue(html.contains("upgrade-insecure-requests"), "index.html must have upgrade-insecure-requests CSP");
        assertTrue(html.contains("no-cache, no-store, must-revalidate"), "index.html must have no-cache meta tag");
    }

    @Test
    @DisplayName("9. app.js contains POST for leave decisions and loading feedback")
    void testAppJsConfiguration() throws Exception {
        InputStream is = new ClassPathResource("static/app.js").getInputStream();
        String js = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(js.contains("data-approve-leave"), "app.js must bind data-approve-leave");
        assertTrue(js.contains("btn.textContent = \"Approving...\";"), "app.js must provide immediate visual feedback");
        assertTrue(js.contains("btn.textContent = \"Rejecting...\";"), "app.js must provide reject feedback");
        assertTrue(js.contains("if (options && options.body && !options.method)"), "apiRequest must default to POST when body is present");
    }
}
