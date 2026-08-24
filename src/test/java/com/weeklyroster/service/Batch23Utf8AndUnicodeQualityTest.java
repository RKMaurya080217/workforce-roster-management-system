package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;

import com.weeklyroster.dto.request.ApplyLeaveRequest;
import com.weeklyroster.dto.request.UpdateMyProfileRequest;
import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class Batch23Utf8AndUnicodeQualityTest {

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private NotificationService notificationService;

    private void authenticateAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "N/A", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
    }

    private void authenticateEmployee(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "N/A", List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
        );
    }

    @Test
    @DisplayName("Batch 23 â€” 13: Unicode & Hindi Text Persistence - Accents, Rupee Symbol and International Characters")
    void testUnicodeAndSpecialCharacterHandling() {
        authenticateEmployee("emp001");
        Employee emp = employeeRepository.findByUserUsername("emp001").orElseThrow();

        // 1. Apply leave with Unicode Hindi reason & special symbols
        String unicodeReason = "Family function â‚¹5000 & à¤¶à¥à¤­ à¤µà¤¿à¤µà¤¾à¤¹ / Medical Care (Dr. FranÃ§ois & MÃ¼ller)";
        LocalDate leaveDate = LocalDate.of(2027, 2, 15);
        LeaveResponse leaveResp = leaveService.apply(
                new ApplyLeaveRequest(emp.getId(), leaveDate, leaveDate, unicodeReason)
        );

        assertNotNull(leaveResp.id());
        assertEquals(unicodeReason, leaveResp.reason(), "Unicode text with Hindi & currency symbols must be preserved exactly");

        // 2. Verify bytes are standard UTF-8 without byte corruption
        byte[] utf8Bytes = leaveResp.reason().getBytes(StandardCharsets.UTF_8);
        String decoded = new String(utf8Bytes, StandardCharsets.UTF_8);
        assertEquals(unicodeReason, decoded);
    }

    @Test
    @DisplayName("Batch 23 â€” 14: Employee Email Persistence - rkmaurya080217@gmail.com Unchanged")
    void testEmailPersistenceUnderUtf8() {
        authenticateEmployee("emp001");
        EmployeeResponse profile = employeeService.getMyProfile();
        assertNotNull(profile);
        assertEquals("rkmaurya080217@gmail.com", profile.email());
    }

    @Test
    @DisplayName("Batch 23 â€” 17: Notification Unicode Formatting & Emoji Safety")
    void testNotificationUnicodeFormatting() {
        authenticateAdmin();
        String testTitle = "ðŸ“Š Schedule Published & ðŸ”” Alert";
        String testMessage = "Weekly duty finalized. All 4 shift types are compliant. âš–ï¸ 100% fair.";

        notificationService.createNotification(
                "emp001",
                1L,
                testTitle,
                testMessage,
                NotificationType.ROSTER_PUBLISHED,
                "ROSTER",
                1L
        );

        authenticateEmployee("emp001");
        List<NotificationResponse> notifs = notificationService.getMyNotifications("emp001");
        assertTrue(notifs.stream().anyMatch(n -> n.title().contains("ðŸ“Š") && n.message().contains("âš–ï¸")));
    }
}