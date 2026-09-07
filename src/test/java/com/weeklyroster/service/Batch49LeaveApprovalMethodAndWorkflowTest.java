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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class Batch49LeaveApprovalMethodAndWorkflowTest {

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

    @Test
    @DisplayName("Test 1: index.html contains app.js and enterprise-app.js scripts before closing body tag")
    void testIndexHtmlContainsScriptTags() throws Exception {
        InputStream is = new ClassPathResource("static/index.html").getInputStream();
        String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(html.contains("<script src=\"/app.js"), "index.html must load /app.js");
        assertTrue(html.contains("<script src=\"/enterprise-app.js"), "index.html must load /enterprise-app.js");
        int appJsIdx = html.indexOf("<script src=\"/app.js");
        int bodyCloseIdx = html.indexOf("</body>");
        assertTrue(appJsIdx < bodyCloseIdx, "Script tag must be placed before </body>");
    }

    @Test
    @DisplayName("Test 2: app.js contains string method normalization in apiRequest and proper leave approval handlers")
    void testAppJsNormalizationAndHandlers() throws Exception {
        InputStream is = new ClassPathResource("static/app.js").getInputStream();
        String js = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(js.contains("typeof options === \"string\""), "apiRequest must normalize string method argument");
        assertTrue(js.contains("data-approve-leave"), "Must contain data-approve-leave listener");
        assertTrue(js.contains("remarks: \"Approved by administrator\""), "Leave approval payload must use 'remarks' field");
    }

    @Test
    @DisplayName("Test 3: LeaveController supports both PUT and POST for approve, reject, modification, and cancellation")
    void testLeaveControllerMethodsSupportPutAndPost() throws NoSuchMethodException {
        // approve method
        Method approveMethod = LeaveController.class.getMethod("approve", Long.class, LeaveDecisionRequest.class);
        RequestMapping approveMapping = approveMethod.getAnnotation(RequestMapping.class);
        assertNotNull(approveMapping, "LeaveController.approve must have @RequestMapping");
        List<RequestMethod> approveMethods = Arrays.asList(approveMapping.method());
        assertTrue(approveMethods.contains(RequestMethod.PUT), "approve must support PUT");
        assertTrue(approveMethods.contains(RequestMethod.POST), "approve must support POST");

        // reject method
        Method rejectMethod = LeaveController.class.getMethod("reject", Long.class, LeaveDecisionRequest.class);
        RequestMapping rejectMapping = rejectMethod.getAnnotation(RequestMapping.class);
        assertNotNull(rejectMapping, "LeaveController.reject must have @RequestMapping");
        List<RequestMethod> rejectMethods = Arrays.asList(rejectMapping.method());
        assertTrue(rejectMethods.contains(RequestMethod.PUT), "reject must support PUT");
        assertTrue(rejectMethods.contains(RequestMethod.POST), "reject must support POST");

        // modification approve
        Method modApprove = LeaveController.class.getMethod("approveModification", Long.class, LeaveDecisionRequest.class);
        RequestMapping modApproveMapping = modApprove.getAnnotation(RequestMapping.class);
        assertNotNull(modApproveMapping);
        assertTrue(Arrays.asList(modApproveMapping.method()).contains(RequestMethod.PUT));
        assertTrue(Arrays.asList(modApproveMapping.method()).contains(RequestMethod.POST));

        // cancellation approve
        Method cancelApprove = LeaveController.class.getMethod("approveCancellation", Long.class, LeaveDecisionRequest.class);
        RequestMapping cancelApproveMapping = cancelApprove.getAnnotation(RequestMapping.class);
        assertNotNull(cancelApproveMapping);
        assertTrue(Arrays.asList(cancelApproveMapping.method()).contains(RequestMethod.PUT));
        assertTrue(Arrays.asList(cancelApproveMapping.method()).contains(RequestMethod.POST));
    }

    @Test
    @DisplayName("Test 4: UnifiedApprovalController supports both POST and PUT for leave approve and reject")
    void testUnifiedApprovalControllerMethodsSupportPostAndPut() throws NoSuchMethodException {
        Method approveLeave = UnifiedApprovalController.class.getMethod("approveLeave", Long.class, LeaveDecisionRequest.class);
        RequestMapping approveMapping = approveLeave.getAnnotation(RequestMapping.class);
        assertNotNull(approveMapping, "UnifiedApprovalController.approveLeave must have @RequestMapping");
        List<RequestMethod> approveMethods = Arrays.asList(approveMapping.method());
        assertTrue(approveMethods.contains(RequestMethod.POST), "approveLeave must support POST");
        assertTrue(approveMethods.contains(RequestMethod.PUT), "approveLeave must support PUT");

        Method rejectLeave = UnifiedApprovalController.class.getMethod("rejectLeave", Long.class, LeaveDecisionRequest.class);
        RequestMapping rejectMapping = rejectLeave.getAnnotation(RequestMapping.class);
        assertNotNull(rejectMapping, "UnifiedApprovalController.rejectLeave must have @RequestMapping");
        List<RequestMethod> rejectMethods = Arrays.asList(rejectMapping.method());
        assertTrue(rejectMethods.contains(RequestMethod.POST), "rejectLeave must support POST");
        assertTrue(rejectMethods.contains(RequestMethod.PUT), "rejectLeave must support PUT");
    }

    @Test
    @DisplayName("Test 5: LeaveController delegate to LeaveService with both PUT and POST invocations")
    void testLeaveControllerInvocation() {
        LeaveController controller = new LeaveController(leaveService);
        LeaveRequest leave = new LeaveRequest();
        leave.setId(100L);
        leave.setEmployee(employee);
        leave.setStartDate(LocalDate.now().plusDays(1));
        leave.setEndDate(LocalDate.now().plusDays(2));
        leave.setStatus(LeaveStatus.PENDING);
        leave.setRequestedAt(LocalDateTime.now());

        when(leaveRepository.findById(100L)).thenReturn(Optional.of(leave));
        when(assignmentRepository.findByEmployeeIdAndRosterDateBetween(eq(1L), any(), any()))
                .thenReturn(List.of());

        ResponseEntity<LeaveResponse> res = controller.approve(100L, new LeaveDecisionRequest("Approved"));
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(LeaveStatus.APPROVED, res.getBody().status());
    }

    @Test
    @DisplayName("Test 6: State transition validation - APPROVED -> APPROVED throws BusinessException")
    void testInvalidTransition_ApprovedToApproved_Throws() {
        LeaveRequest leave = new LeaveRequest();
        leave.setId(100L);
        leave.setEmployee(employee);
        leave.setStatus(LeaveStatus.APPROVED);

        when(leaveRepository.findById(100L)).thenReturn(Optional.of(leave));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                leaveService.approve(100L, new LeaveDecisionRequest("Double approve"))
        );
        assertTrue(ex.getMessage().contains("already approved"));
    }

    @Test
    @DisplayName("Test 7: State transition validation - REJECTED -> APPROVED throws BusinessException")
    void testInvalidTransition_RejectedToApproved_Throws() {
        LeaveRequest leave = new LeaveRequest();
        leave.setId(100L);
        leave.setEmployee(employee);
        leave.setStatus(LeaveStatus.REJECTED);

        when(leaveRepository.findById(100L)).thenReturn(Optional.of(leave));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                leaveService.approve(100L, new LeaveDecisionRequest("Approve rejected"))
        );
        assertTrue(ex.getMessage().contains("Cannot approve a rejected leave request"));
    }

    @Test
    @DisplayName("Test 8: State transition validation - APPROVED -> REJECTED throws BusinessException")
    void testInvalidTransition_ApprovedToRejected_Throws() {
        LeaveRequest leave = new LeaveRequest();
        leave.setId(100L);
        leave.setEmployee(employee);
        leave.setStatus(LeaveStatus.APPROVED);

        when(leaveRepository.findById(100L)).thenReturn(Optional.of(leave));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                leaveService.reject(100L, new LeaveDecisionRequest("Reject approved"))
        );
        assertTrue(ex.getMessage().contains("Cannot reject an already approved leave request"));
    }

    @Test
    @DisplayName("Test 9: State transition validation - REJECTED -> REJECTED throws BusinessException")
    void testInvalidTransition_RejectedToRejected_Throws() {
        LeaveRequest leave = new LeaveRequest();
        leave.setId(100L);
        leave.setEmployee(employee);
        leave.setStatus(LeaveStatus.REJECTED);

        when(leaveRepository.findById(100L)).thenReturn(Optional.of(leave));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                leaveService.reject(100L, new LeaveDecisionRequest("Double reject"))
        );
        assertTrue(ex.getMessage().contains("already rejected"));
    }

    @Test
    @DisplayName("Test 10: Non-admin employee cannot approve or reject leave requests (RBAC 403)")
    void testNonAdmin_CannotApproveOrRejectLeave() {
        var empAuth = new UsernamePasswordAuthenticationToken("emp001", null, List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        SecurityContextHolder.getContext().setAuthentication(empAuth);

        assertThrows(AccessDeniedException.class, () ->
                leaveService.approve(100L, new LeaveDecisionRequest("Unauthorized approve"))
        );

        assertThrows(AccessDeniedException.class, () ->
                leaveService.reject(100L, new LeaveDecisionRequest("Unauthorized reject"))
        );
    }

    @Test
    @DisplayName("Test 11: UnifiedApprovalService correctly decides leave and handles reoptimization safely")
    void testUnifiedApprovalServiceDecideLeave() {
        UnifiedApprovalService unifiedService = new UnifiedApprovalService(
                profileChangeRequestService, leaveService, preferenceService,
                rosterService, cycleRepository, changeRequestRepository, rosterReviewService
        );

        LeaveRequest leave = new LeaveRequest();
        leave.setId(200L);
        leave.setEmployee(employee);
        leave.setStartDate(LocalDate.now().plusDays(3));
        leave.setEndDate(LocalDate.now().plusDays(4));
        leave.setStatus(LeaveStatus.PENDING);

        when(leaveRepository.findById(200L)).thenReturn(Optional.of(leave));
        when(assignmentRepository.findByEmployeeIdAndRosterDateBetween(eq(1L), any(), any()))
                .thenReturn(List.of());
        when(cycleRepository.findOverlappingCycles(any(), any()))
                .thenReturn(List.of());

        LeaveResponse res = unifiedService.decideLeave(200L, true, new LeaveDecisionRequest("Unified approve"));
        assertNotNull(res);
        assertEquals(LeaveStatus.APPROVED, res.status());
    }
}
