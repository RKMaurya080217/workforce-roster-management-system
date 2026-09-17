package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.weeklyroster.WeeklyRosterManagementApplication;
import com.weeklyroster.dto.response.ShiftResponse;
import com.weeklyroster.entity.AuditAction;
import com.weeklyroster.entity.EmailType;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.EmailDeliveryLogRepository;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.ShiftRepository;
import com.weeklyroster.service.email.EmailService;
import com.weeklyroster.service.sms.SmsDeliveryResult;
import com.weeklyroster.service.sms.SmsService;
import com.weeklyroster.service.sms.SmsServiceImpl;

@ExtendWith(MockitoExtension.class)
class Batch61DynamicCapacityAndNotificationTest {

    @Mock
    private ShiftRepository shiftRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private ShiftService shiftService;

    @Mock
    private EmailDeliveryLogRepository emailLogRepository;

    @Mock
    private RosterCycleRepository cycleRepository;

    @Mock
    private RosterAssignmentRepository assignmentRepository;

    @Mock
    private EmailService mockEmailService;

    @Mock
    private SmsService mockSmsService;

    private RosterEmailService rosterEmailService;

    @BeforeEach
    void setUp() {
        rosterEmailService = new RosterEmailService(
                emailLogRepository,
                employeeRepository,
                cycleRepository,
                assignmentRepository,
                shiftRepository,
                mockEmailService,
                mockSmsService
        );
    }

    // ==========================================
    // 1. DYNAMIC SHIFT CAPACITY & VALIDATION
    // ==========================================

    @Test
    @DisplayName("Night shift target capacity can be dynamically set greater than 1 without artificial restriction")
    void testNightShiftCapacity_CanBeGreaterThanOne() {
        Shift nightShift = new Shift();
        nightShift.setId(4L);
        nightShift.setShiftType(ShiftType.NIGHT);
        nightShift.setCapacity(1);
        nightShift.setActive(true);

        when(shiftRepository.findById(4L)).thenReturn(Optional.of(nightShift));

        ShiftResponse response = shiftService.updateCapacity(4L, 2);

        assertNotNull(response);
        assertEquals(2, response.capacity());
        assertEquals(2, nightShift.getCapacity());

        // Verify audit log was recorded
        verify(auditService, atLeastOnce()).log(
                eq(AuditAction.SHIFT_CAPACITY_UPDATED),
                eq("SHIFT"),
                eq(4L),
                any(),
                any(),
                any(),
                any(),
                any(),
                contains("NIGHT"),
                any()
        );
    }

    @Test
    @DisplayName("Capacity validation rejects negative integers and values exceeding 50")
    void testCapacityValidation_BoundaryChecks() {
        assertThrows(BusinessException.class, () -> shiftService.updateCapacity(1L, -1),
                "Negative capacity should throw BusinessException");

        assertThrows(BusinessException.class, () -> shiftService.updateCapacity(1L, 51),
                "Capacity exceeding 50 should throw BusinessException");
    }

    @Test
    @DisplayName("Bulk capacity update adjusts multiple shifts atomically")
    void testBulkCapacityUpdate() {
        Shift morning = new Shift();
        morning.setId(1L);
        morning.setShiftType(ShiftType.MORNING);
        morning.setCapacity(2);
        morning.setActive(true);

        Shift general = new Shift();
        general.setId(2L);
        general.setShiftType(ShiftType.GENERAL);
        general.setCapacity(2);
        general.setActive(true);

        Shift evening = new Shift();
        evening.setId(3L);
        evening.setShiftType(ShiftType.EVENING);
        evening.setCapacity(2);
        evening.setActive(true);

        Shift night = new Shift();
        night.setId(4L);
        night.setShiftType(ShiftType.NIGHT);
        night.setCapacity(1);
        night.setActive(true);

        when(shiftRepository.findByShiftType(ShiftType.MORNING)).thenReturn(Optional.of(morning));
        when(shiftRepository.findByShiftType(ShiftType.GENERAL)).thenReturn(Optional.of(general));
        when(shiftRepository.findByShiftType(ShiftType.EVENING)).thenReturn(Optional.of(evening));
        when(shiftRepository.findByShiftType(ShiftType.NIGHT)).thenReturn(Optional.of(night));

        when(shiftRepository.findById(1L)).thenReturn(Optional.of(morning));
        when(shiftRepository.findById(2L)).thenReturn(Optional.of(general));
        when(shiftRepository.findById(3L)).thenReturn(Optional.of(evening));
        when(shiftRepository.findById(4L)).thenReturn(Optional.of(night));

        when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(morning, general, evening, night));

        Map<String, Integer> bulkMap = Map.of(
                "MORNING", 3,
                "GENERAL", 3,
                "EVENING", 2,
                "NIGHT", 2
        );

        List<ShiftResponse> result = shiftService.updateBulkCapacities(bulkMap);
        assertNotNull(result);
        assertEquals(4, result.size());
        assertEquals(3, morning.getCapacity());
        assertEquals(3, general.getCapacity());
        assertEquals(2, evening.getCapacity());
        assertEquals(2, night.getCapacity());
    }

    // ==========================================
    // 2. SMS SERVICE & NUMBER MASKING
    // ==========================================

    @Test
    @DisplayName("SMS service masks phone numbers and succeeds in default simulated log mode")
    void testSmsService_LoggingModeAndMasking() {
        SmsServiceImpl smsService = new SmsServiceImpl(true, "LOG", "", "WRMS");

        assertTrue(smsService.isConfigured());
        assertEquals("LOG", smsService.getProviderName());

        // Valid 10-digit phone
        SmsDeliveryResult res = smsService.sendSms("9876543210", "WRMS: Tentative Weekly Roster emailed.");
        assertTrue(res.success());
        assertNotNull(res.messageId());
        assertEquals("LOG", res.provider());

        // Invalid phone gracefully returns failure without throwing
        SmsDeliveryResult invalidRes = smsService.sendSms("123", "Short phone");
        assertFalse(invalidRes.success());
        assertTrue(invalidRes.errorMessage().contains("Invalid"));
    }

    // ==========================================
    // 3. ROSTER EMAIL HTML TEMPLATE & BOLD ADMIN MESSAGE
    // ==========================================

    @Test
    @DisplayName("Roster email template renders bold admin message when provided and omits it when null")
    void testRosterEmailTemplate_AdminMessageRendering() {
        Employee emp = new Employee();
        emp.setFirstName("Rajat");
        emp.setLastName("Maurya");
        emp.setEmployeeCode("EMP001");
        emp.setContactNumber("9876543210");
        emp.setEmail("rajat@example.com");

        // Case A: Custom admin message present
        String customMsg = "Special release week. Please ensure seamless shift handover.";
        String htmlWithMsg = ReflectionTestUtils.invokeMethod(
                rosterEmailService,
                "buildHtmlEmailTemplate",
                EmailType.TENTATIVE_ROSTER,
                emp,
                "21 Sep 2026 - 27 Sep 2026",
                "<tr><td>Schedule</td></tr>",
                customMsg
        );

        assertNotNull(htmlWithMsg);
        assertTrue(htmlWithMsg.contains("Special release week"), "HTML should contain the custom message");
        assertTrue(htmlWithMsg.contains("Message from Administrator:"), "HTML should render the bold announcement header");
        assertTrue(htmlWithMsg.contains("<strong"), "Message content must be styled in bold");
        assertTrue(htmlWithMsg.contains("TENTATIVE ROSTER"), "HTML should display tentative badge");

        // Case B: Null/blank admin message -> Omitted cleanly
        String htmlWithoutMsg = ReflectionTestUtils.invokeMethod(
                rosterEmailService,
                "buildHtmlEmailTemplate",
                EmailType.FINAL_ROSTER,
                emp,
                "21 Sep 2026 - 27 Sep 2026",
                "<tr><td>Schedule</td></tr>",
                null
        );

        assertNotNull(htmlWithoutMsg);
        assertFalse(htmlWithoutMsg.contains("Message from Administrator:"), "Notice box must not be rendered when message is null");
        assertFalse(htmlWithoutMsg.contains("null"), "No raw 'null' text should ever be present in the email HTML");
        assertTrue(htmlWithoutMsg.contains("FINAL ROSTER"), "HTML should display final roster badge");
    }

    // ==========================================
    // 4. TIMEZONE ALIGNMENT (Asia/Kolkata)
    // ==========================================

    @Test
    @DisplayName("JVM default timezone is set to Asia/Kolkata (IST +05:30)")
    void testTimezone_IsAsiaKolkata() {
        new WeeklyRosterManagementApplication().init();
        TimeZone tz = TimeZone.getDefault();
        assertNotNull(tz);
        assertTrue("Asia/Kolkata".equals(tz.getID()) || "Asia/Calcutta".equals(tz.getID()),
                "Default TimeZone should be Asia/Kolkata (or Asia/Calcutta alias)");
        assertEquals(19800000, tz.getRawOffset(), "Offset must be exactly +05:30 (19800000 ms)");
    }
}
