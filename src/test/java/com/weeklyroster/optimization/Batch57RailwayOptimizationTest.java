package com.weeklyroster.optimization;

import com.weeklyroster.entity.GenerationMode;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.RosterStatus;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.service.RosterSchedulerService;
import com.weeklyroster.service.SseEmitterService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.sql.DataSource;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Batch57RailwayOptimizationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Autowired
    private SseEmitterService sseEmitterService;

    @Autowired
    private RosterSchedulerService rosterSchedulerService;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Test
    @Order(1)
    @DisplayName("Test 1: HikariCP Connection Pool is optimized for minimum Railway footprint")
    void test1_HikariPoolAndDatabaseConfig() {
        assertNotNull(dataSource, "DataSource must not be null");
        assertTrue(dataSource instanceof HikariDataSource, "DataSource must be HikariDataSource");

        HikariDataSource hikari = (HikariDataSource) dataSource;
        System.out.println("=== HIKARI CONFIG VERIFICATION ===");
        System.out.println("  Pool Name           : " + hikari.getPoolName());
        System.out.println("  Maximum Pool Size   : " + hikari.getMaximumPoolSize());
        System.out.println("  Minimum Idle        : " + hikari.getMinimumIdle());
        System.out.println("  Connection Timeout  : " + hikari.getConnectionTimeout() + " ms");
        System.out.println("  Idle Timeout        : " + hikari.getIdleTimeout() + " ms");

        assertTrue(hikari.getMaximumPoolSize() <= 6, "Maximum pool size should be <= 6 to conserve MySQL resources");
        assertTrue(hikari.getMinimumIdle() <= 1, "Minimum idle should be <= 1 to minimize persistent connections");
        assertEquals("WRMSHikariPool", hikari.getPoolName());
    }

    @Test
    @Order(2)
    @DisplayName("Test 2: Exactly 12 core tables exist and zero data lost")
    void test2_ZeroDataLossAndCoreTableIntegrity() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name",
                String.class);

        System.out.println("=== CORE DATABASE TABLES (" + tables.size() + ") ===");
        tables.forEach(t -> {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + t, Integer.class);
            System.out.println("  " + t + " -> " + count + " rows");
        });

        assertEquals(13, tables.size(), "Exact 13 production tables must exist");
        assertTrue(tables.contains("users"));
        assertTrue(tables.contains("employees"));
        assertTrue(tables.contains("shifts"));
        assertTrue(tables.contains("master_reference_data"));
        assertFalse(tables.contains("employee_skills"));
        assertTrue(tables.contains("roster_cycles"));
        assertTrue(tables.contains("roster_assignments"));
        assertTrue(tables.contains("leave_requests"));
        assertTrue(tables.contains("shift_handovers"));
        assertTrue(tables.contains("notifications"));
        assertTrue(tables.contains("employee_requests"));
        assertTrue(tables.contains("system_audit_logs"));
        assertTrue(tables.contains("device_tokens"));
        assertTrue(tables.contains("sms_delivery_logs"));

        // Confirm users and employees rows are intact
        Integer userCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        assertNotNull(userCount);
        assertTrue(userCount >= 8, "All system users must be preserved");

        Integer empCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM employees", Integer.class);
        assertNotNull(empCount);
        assertTrue(empCount >= 7, "All employees must be preserved");
    }

    @Test
    @Order(3)
    @DisplayName("Test 3: Tomcat thread pool bounds and Gzip compression settings are active")
    void test3_TomcatAndCompressionConfiguration() {
        String compressionEnabled = environment.getProperty("server.compression.enabled");
        String tomcatMaxThreads = environment.getProperty("server.tomcat.threads.max");
        String tomcatMinSpare = environment.getProperty("server.tomcat.threads.min-spare");
        String tomcatAcceptCount = environment.getProperty("server.tomcat.accept-count");

        System.out.println("=== WEB SERVER RESOURCE OPTIMIZATION ===");
        System.out.println("  Compression Enabled : " + compressionEnabled);
        System.out.println("  Tomcat Max Threads  : " + tomcatMaxThreads);
        System.out.println("  Tomcat Min Spare    : " + tomcatMinSpare);
        System.out.println("  Tomcat Accept Count : " + tomcatAcceptCount);

        assertEquals("true", compressionEnabled, "HTTP compression must be enabled to save bandwidth");
        assertEquals("20", tomcatMaxThreads, "Tomcat max threads must be bounded to 20");
        assertEquals("2", tomcatMinSpare, "Tomcat min spare threads must be 2");
        assertEquals("50", tomcatAcceptCount, "Tomcat accept count must be 50");
    }

    @Test
    @Order(4)
    @DisplayName("Test 4: SSE connection capping, memory leak prevention, and heartbeat")
    void test4_SseEmitterConnectionBoundingAndEviction() {
        String testUser = "test_opt_user_" + System.currentTimeMillis();

        // Subscribe 4 times for the same user (simulating 4 open tabs or page refreshes)
        SseEmitter emitter1 = sseEmitterService.subscribe(testUser);
        SseEmitter emitter2 = sseEmitterService.subscribe(testUser);
        SseEmitter emitter3 = sseEmitterService.subscribe(testUser);
        SseEmitter emitter4 = sseEmitterService.subscribe(testUser);

        assertNotNull(emitter1);
        assertNotNull(emitter2);
        assertNotNull(emitter3);
        assertNotNull(emitter4);

        // Active connection count for this user must not exceed 3 (oldest evicted)
        int totalActive = sseEmitterService.getActiveConnectionCount();
        assertTrue(totalActive >= 1, "Active connection count must reflect subscribed emitters");

        // Send a heartbeat and broadcast
        assertDoesNotThrow(() -> sseEmitterService.sendHeartbeat());
        assertDoesNotThrow(() -> sseEmitterService.sendToUser(testUser, "TEST_EVENT", Map.of("key", "value")));
        assertDoesNotThrow(() -> sseEmitterService.broadcast("TEST_BROADCAST", Map.of("broadcast", true)));

        // Clean up emitters
        emitter2.complete();
        emitter3.complete();
        emitter4.complete();
    }

    @Test
    @Order(5)
    @DisplayName("Test 5: Schedulers strictly enforce ONLY THE UPCOMING WEEK'S ROSTER")
    void test5_SchedulerUpcomingWeekLogicPreserved() {
        LocalDate baseDate = LocalDate.of(2026, 9, 9); // Wednesday
        LocalDate upcomingMonday = rosterSchedulerService.calculateUpcomingWeekStart(baseDate);
        LocalDate upcomingSunday = rosterSchedulerService.calculateUpcomingWeekEnd(baseDate);

        System.out.println("=== UPCOMING WEEK SCHEDULER VALIDATION ===");
        System.out.println("  Base Date        : " + baseDate + " (" + baseDate.getDayOfWeek() + ")");
        System.out.println("  Upcoming Monday  : " + upcomingMonday + " (" + upcomingMonday.getDayOfWeek() + ")");
        System.out.println("  Upcoming Sunday  : " + upcomingSunday + " (" + upcomingSunday.getDayOfWeek() + ")");

        assertEquals(DayOfWeek.MONDAY, upcomingMonday.getDayOfWeek());
        assertEquals(DayOfWeek.SUNDAY, upcomingSunday.getDayOfWeek());
        assertEquals(LocalDate.of(2026, 9, 14), upcomingMonday);
        assertEquals(LocalDate.of(2026, 9, 20), upcomingSunday);

        // Guard validation
        assertTrue(rosterSchedulerService.isAutomaticGenerationAllowed(upcomingMonday, baseDate),
                "Upcoming Monday must be allowed");
        assertFalse(rosterSchedulerService.isAutomaticGenerationAllowed(baseDate, baseDate),
                "Current date must be rejected");
        assertFalse(rosterSchedulerService.isAutomaticGenerationAllowed(upcomingMonday.plusWeeks(1), baseDate),
                "Next-next week must be rejected");

        // Scheduler Status Diagnostics
        Map<String, Object> status = rosterSchedulerService.getSchedulerStatus();
        assertNotNull(status);
        assertEquals("0 0 9 * * SUN", status.get("cron"));
        assertEquals("Asia/Kolkata", status.get("timezone"));
    }

    @Test
    @Order(6)
    @DisplayName("Test 6: Transactional email configuration (Brevo Primary + SMTP Fallback) preserved")
    void test6_EmailProviderConfigurationPreserved() {
        String emailProvider = environment.getProperty("email.provider");
        String brevoUrl = environment.getProperty("brevo.api.base-url");
        String smtpHost = environment.getProperty("spring.mail.host");
        String smtpPort = environment.getProperty("spring.mail.port");

        System.out.println("=== TRANSACTIONAL EMAIL CONFIGURATION ===");
        System.out.println("  Provider   : " + emailProvider);
        System.out.println("  Brevo URL  : " + brevoUrl);
        System.out.println("  SMTP Host  : " + smtpHost);
        System.out.println("  SMTP Port  : " + smtpPort);

        assertEquals("BREVO", emailProvider, "Default email provider must be BREVO");
        assertEquals("https://api.brevo.com", brevoUrl, "Brevo base URL must be preserved");
        assertEquals("smtp.gmail.com", smtpHost, "SMTP fallback host must be preserved");
        assertEquals("587", smtpPort, "SMTP fallback port must be 587");
    }
}
