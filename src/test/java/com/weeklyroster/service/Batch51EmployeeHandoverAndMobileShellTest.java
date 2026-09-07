package com.weeklyroster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.weeklyroster.controller.AdminShiftHandoverController;
import com.weeklyroster.controller.ShiftHandoverController;
import com.weeklyroster.dto.request.CreateHandoverRequest;
import com.weeklyroster.dto.response.HandoverResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.ShiftHandoverRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class Batch51EmployeeHandoverAndMobileShellTest {

    @Mock
    private ShiftHandoverRepository handoverRepository;
    @Mock
    private ShiftRepository shiftRepository;
    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private EmployeeActivityLogService activityLogService;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private ShiftHandoverService handoverService;

    private ShiftHandoverController shiftHandoverController;
    private AdminShiftHandoverController adminShiftHandoverController;

    private Employee outgoingStaff;
    private Employee oncomingReliever;
    private Employee unrelatedStaff;
    private Shift morningShift;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        outgoingStaff = new Employee();
        outgoingStaff.setId(101L);
        outgoingStaff.setEmployeeCode("EMP101");
        outgoingStaff.setFirstName("Rajat");
        outgoingStaff.setLastName("Maurya");
        outgoingStaff.setEmail("rajat@cris.com");
        outgoingStaff.setGender(Gender.MALE);
        outgoingStaff.setActive(true);

        oncomingReliever = new Employee();
        oncomingReliever.setId(102L);
        oncomingReliever.setEmployeeCode("EMP102");
        oncomingReliever.setFirstName("Suresh");
        oncomingReliever.setLastName("Kumar");
        oncomingReliever.setEmail("suresh@cris.com");
        oncomingReliever.setGender(Gender.MALE);
        oncomingReliever.setActive(true);

        unrelatedStaff = new Employee();
        unrelatedStaff.setId(103L);
        unrelatedStaff.setEmployeeCode("EMP103");
        unrelatedStaff.setFirstName("Pooja");
        unrelatedStaff.setLastName("Sharma");
        unrelatedStaff.setEmail("pooja@cris.com");
        unrelatedStaff.setGender(Gender.FEMALE);
        unrelatedStaff.setActive(true);

        morningShift = new Shift();
        morningShift.setId(1L);
        morningShift.setShiftType(ShiftType.MORNING);
        morningShift.setCapacity(2);

        shiftHandoverController = new ShiftHandoverController(handoverService, employeeRepository);
        adminShiftHandoverController = new AdminShiftHandoverController(handoverService);
    }

    private void authenticateAsEmployee(String username, Employee emp) {
        when(employeeRepository.findByUserUsernameIgnoreCase(username)).thenReturn(Optional.of(emp));
        var auth = new UsernamePasswordAuthenticationToken(username, null, List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void authenticateAsAdmin(String username) {
        var auth = new UsernamePasswordAuthenticationToken(username, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("Batch 51 [1]: Handover Request Deserialization with Aliases (summary / shiftSummary, notes / importantNotes)")
    void testCreateHandoverRequestDeserializationAliases() throws Exception {
        String jsonWithAliases = """
            {
              "handoverDate": "2026-09-10",
              "shiftId": 1,
              "toEmployeeId": 102,
              "shiftSummary": "Smooth handover with all servers operational",
              "pendingTasks": "Monitor batch jobs",
              "completedTasks": "Backup complete",
              "notes": "Ensure cooling is active",
              "priority": "HIGH"
            }
            """;

        CreateHandoverRequest req = objectMapper.readValue(jsonWithAliases, CreateHandoverRequest.class);
        assertNotNull(req);
        assertEquals(LocalDate.of(2026, 9, 10), req.handoverDate());
        assertEquals(1L, req.shiftId());
        assertEquals(102L, req.toEmployeeId());
        assertEquals("Smooth handover with all servers operational", req.summary());
        assertEquals("Ensure cooling is active", req.importantNotes());
        assertEquals(HandoverPriority.HIGH, req.priority());
    }

    @Test
    @DisplayName("Batch 51 [2]: Employee Retrieves Own Outgoing Handovers via Controller")
    void testGetMyHandoversScopedToAuthenticatedEmployee() {
        authenticateAsEmployee("rajat", outgoingStaff);

        ShiftHandover handover = new ShiftHandover();
        handover.setId(10L);
        handover.setHandoverDate(LocalDate.now());
        handover.setShift(morningShift);
        handover.setFromEmployee(outgoingStaff);
        handover.setToEmployee(oncomingReliever);
        handover.setSummary("End of day handover");
        handover.setStatus(HandoverStatus.OPEN);
        handover.setCreatedAt(LocalDateTime.now());
        handover.setUpdatedAt(LocalDateTime.now());

        when(handoverRepository.findByFromEmployeeIdOrderByHandoverDateDescCreatedAtDesc(101L))
                .thenReturn(List.of(handover));

        var resp = shiftHandoverController.getMyHandovers(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(1, resp.getBody().size());
        assertEquals("End of day handover", resp.getBody().get(0).summary());
        assertEquals("EMP101", resp.getBody().get(0).fromEmployeeCode());
    }

    @Test
    @DisplayName("Batch 51 [3]: Employee Retrieves Incoming Handovers via Controller")
    void testGetIncomingHandoversScopedToReliever() {
        authenticateAsEmployee("suresh", oncomingReliever);

        ShiftHandover handover = new ShiftHandover();
        handover.setId(11L);
        handover.setHandoverDate(LocalDate.now());
        handover.setShift(morningShift);
        handover.setFromEmployee(outgoingStaff);
        handover.setToEmployee(oncomingReliever);
        handover.setSummary("Critical incoming briefing");
        handover.setStatus(HandoverStatus.OPEN);
        handover.setCreatedAt(LocalDateTime.now());
        handover.setUpdatedAt(LocalDateTime.now());

        when(handoverRepository.findByToEmployeeIdOrderByHandoverDateDescCreatedAtDesc(102L))
                .thenReturn(List.of(handover));

        var resp = shiftHandoverController.getIncomingHandovers(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(1, resp.getBody().size());
        assertEquals("Critical incoming briefing", resp.getBody().get(0).summary());
        assertEquals("EMP102", resp.getBody().get(0).toEmployeeCode());
    }

    @Test
    @DisplayName("Batch 51 [4]: Designated Reliever Acknowledges Handover via POST with JSON body")
    void testRelieverAcknowledgeHandoverSuccess() {
        authenticateAsEmployee("suresh", oncomingReliever);

        ShiftHandover handover = new ShiftHandover();
        handover.setId(12L);
        handover.setHandoverDate(LocalDate.now());
        handover.setShift(morningShift);
        handover.setFromEmployee(outgoingStaff);
        handover.setToEmployee(oncomingReliever);
        handover.setSummary("Ready for night rotation");
        handover.setStatus(HandoverStatus.OPEN);
        handover.setCreatedAt(LocalDateTime.now());
        handover.setUpdatedAt(LocalDateTime.now());

        when(handoverRepository.findById(12L)).thenReturn(Optional.of(handover));
        when(handoverRepository.save(any(ShiftHandover.class))).thenAnswer(inv -> inv.getArgument(0));

        var body = Map.<String, Object>of("remarks", "Understood, systems verified");
        ResponseEntity<HandoverResponse> resp = shiftHandoverController.acknowledgeHandoverPost(
                12L, null, body, SecurityContextHolder.getContext().getAuthentication());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(HandoverStatus.ACKNOWLEDGED, resp.getBody().status());
        verify(activityLogService, times(1)).logActivity(eq(102L), eq("suresh"), any(), any(), any(), any());
        verify(notificationService, times(1)).createNotification(eq("emp101"), eq(101L), anyString(), anyString(), any(), anyString(), eq(12L));
    }

    @Test
    @DisplayName("Batch 51 [5]: Unrelated Employee Cannot Acknowledge Someone Else's Handover")
    void testUnrelatedEmployeeCannotAcknowledgeHandover() {
        authenticateAsEmployee("pooja", unrelatedStaff);

        ShiftHandover handover = new ShiftHandover();
        handover.setId(13L);
        handover.setHandoverDate(LocalDate.now());
        handover.setShift(morningShift);
        handover.setFromEmployee(outgoingStaff);
        handover.setToEmployee(oncomingReliever);
        handover.setStatus(HandoverStatus.OPEN);

        when(handoverRepository.findById(13L)).thenReturn(Optional.of(handover));

        assertThrows(BusinessException.class, () ->
                shiftHandoverController.acknowledgeHandoverPost(
                        13L, "Attempting unassigned ack", null, SecurityContextHolder.getContext().getAuthentication()));
    }

    @Test
    @DisplayName("Batch 51 [6]: Employee Data Isolation Guards GET /api/handovers/{id}")
    void testEmployeeDataIsolationOnGetHandoverById() {
        authenticateAsEmployee("pooja", unrelatedStaff);

        ShiftHandover handover = new ShiftHandover();
        handover.setId(14L);
        handover.setFromEmployee(outgoingStaff);
        handover.setToEmployee(oncomingReliever);
        handover.setSummary("Private handover");
        handover.setHandoverDate(LocalDate.now());
        handover.setShift(morningShift);
        handover.setStatus(HandoverStatus.OPEN);

        when(handoverRepository.findById(14L)).thenReturn(Optional.of(handover));

        assertThrows(BusinessException.class, () ->
                shiftHandoverController.getHandoverById(14L, SecurityContextHolder.getContext().getAuthentication()));
    }

    @Test
    @DisplayName("Batch 51 [7]: Admin Can View All Handovers via AdminShiftHandoverController")
    void testAdminCanViewAllHandovers() {
        authenticateAsAdmin("admin001");

        ShiftHandover h1 = new ShiftHandover();
        h1.setId(20L);
        h1.setHandoverDate(LocalDate.now());
        h1.setShift(morningShift);
        h1.setFromEmployee(outgoingStaff);
        h1.setToEmployee(oncomingReliever);
        h1.setSummary("Summary 1");
        h1.setStatus(HandoverStatus.OPEN);

        when(handoverRepository.findAll()).thenReturn(List.of(h1));

        var resp = adminShiftHandoverController.getAllHandovers(null, null);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(1, resp.getBody().size());
    }

    @Test
    @DisplayName("Batch 51 [8]: Mobile Responsive CSS & Drawer Structure Verification")
    void testMobileResponsiveCssRulesIntegrity() throws Exception {
        InputStream is = new ClassPathResource("static/styles.css").getInputStream();
        String css = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // Verify desktop sidebar is sticky with 100dvh and hidden overflow
        assertTrue(css.contains(".app-sidebar {"), "styles.css must style .app-sidebar");
        assertTrue(css.contains("height: 100dvh;"), "styles.css must include 100dvh height for sidebar");
        assertTrue(css.contains("align-self: flex-start;"), "styles.css must include align-self: flex-start for sticky sidebar");

        // Verify mobile drawer uses transform hardware acceleration and isolates internal nav scroll
        assertTrue(css.contains("transform: translateX(-100%) !important;"), "styles.css must use translateX(-100%) for closed drawer");
        assertTrue(css.contains("transform: translateX(0) !important;"), "styles.css must use translateX(0) for opened drawer");
        assertTrue(css.contains(".sidebar-mobile-backdrop.active"), "styles.css must style active mobile backdrop");
        assertTrue(css.contains("body.sidebar-mobile-locked"), "styles.css must lock body scroll when mobile drawer is open");
    }

    @Test
    @DisplayName("Batch 51 [9]: Static Assets Version Cache-Buster & Handover Acknowledge Route")
    void testStaticAssetsAndEnterpriseAppScriptIntegrity() throws Exception {
        InputStream htmlIs = new ClassPathResource("static/index.html").getInputStream();
        String html = new String(htmlIs.readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(html.contains("styles.css?v=2.5.0") || html.contains("styles.css?v=2.4.0") || html.contains("styles.css?v=2.3.0"), "index.html must reference versioned styles.css");
        assertTrue(html.contains("app.js?v=2.5.0") || html.contains("app.js?v=2.4.0") || html.contains("app.js?v=2.3.0"), "index.html must reference versioned app.js");
        assertTrue(html.contains("enterprise-app.js?v=2.5.0") || html.contains("enterprise-app.js?v=2.4.0") || html.contains("enterprise-app.js?v=2.3.0"), "index.html must reference versioned enterprise-app.js");

        InputStream jsIs = new ClassPathResource("static/enterprise-app.js").getInputStream();
        String js = new String(jsIs.readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(js.contains("/api/handovers/${id}/acknowledge") || js.contains("/api/handovers/\" + id + \"/acknowledge"),
                "enterprise-app.js must call canonical acknowledge endpoint");
        assertTrue(js.contains("/api/employees/active"),
                "enterprise-app.js must fetch active employees");
    }
}
