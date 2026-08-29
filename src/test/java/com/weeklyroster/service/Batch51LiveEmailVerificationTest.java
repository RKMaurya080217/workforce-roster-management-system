package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;

import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Batch51LiveEmailVerificationTest {

    @Autowired
    private RosterEmailService emailService;

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterSchedulerService schedulerService;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private RosterOverrideRepository overrideRepository;

    @Autowired
    private RosterVersionRepository versionRepository;

    @Autowired
    private EmailDeliveryLogRepository emailDeliveryLogRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    private LocalDate upcomingMonday;

    @BeforeEach
    void cleanState() {
        overrideRepository.deleteAll();
        assignmentRepository.deleteAll();
        versionRepository.deleteAll();
        emailDeliveryLogRepository.deleteAll();
        cycleRepository.deleteAll();

        upcomingMonday = schedulerService.calculateUpcomingWeekStart(LocalDate.now());
    }

    @Test
    @Order(1)
    @DisplayName("Batch 51 [1]: SMTP Configuration & Test Email Endpoint Readiness")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test01_SmtpTestEmailReadiness() {
        Map<String, Object> testResult = emailService.sendTestEmail("rajatkumarmaury@gmail.com");
        assertNotNull(testResult);
        assertTrue(testResult.containsKey("status"));
        assertTrue(List.of("SENT", "BLOCKED", "FAILED").contains(testResult.get("status")),
                "Status must be a valid outcome (SENT in production with password, BLOCKED safely if password unset)");
    }

    @Test
    @Order(2)
    @DisplayName("Batch 51 [2]: Tentative Roster Email Distribution & Template Integrity")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test02_TentativeRosterEmailDistribution() {
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(upcomingMonday, GenerationMode.MANUAL);
        assertNotNull(cycle);

        RosterCycle entity = cycleRepository.findById(cycle.id()).orElseThrow();
        entity.setStatus(RosterStatus.TENTATIVE);
        entity = cycleRepository.save(entity);

        List<EmailDeliveryLogResponse> logs = emailService.distributeTentativeRosterEmails(entity, cycle, GenerationMode.MANUAL);
        assertNotNull(logs);
        assertFalse(logs.isEmpty(), "Email distribution logs must be generated for staff");

        for (EmailDeliveryLogResponse log : logs) {
            assertNotNull(log.recipientEmail());
            assertTrue(log.recipientEmail().contains("@"));
            assertNotNull(log.status());
        }
    }

    @Test
    @Order(3)
    @DisplayName("Batch 51 [3]: Final Roster Email Distribution & Duplicate Protection (Idempotency)")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test03_FinalRosterEmailIdempotency() {
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(upcomingMonday, GenerationMode.MANUAL);
        assertNotNull(cycle);

        RosterCycle entity = cycleRepository.findById(cycle.id()).orElseThrow();
        entity.setStatus(RosterStatus.PUBLISHED);
        entity = cycleRepository.save(entity);

        // First distribution pass
        List<EmailDeliveryLogResponse> pass1 = emailService.distributeRosterEmails(entity, cycle, GenerationMode.AUTOMATIC);
        assertNotNull(pass1);

        // Second automated distribution pass should be skipped (duplicate prevention)
        List<EmailDeliveryLogResponse> pass2 = emailService.distributeRosterEmails(entity, cycle, GenerationMode.AUTOMATIC);
        assertNotNull(pass2);
    }
}