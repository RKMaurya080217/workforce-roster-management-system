package com.weeklyroster.config;

import static org.junit.jupiter.api.Assertions.*;

import com.weeklyroster.dto.response.SystemHealthResponse;
import com.weeklyroster.service.SystemHealthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "MYSQLHOST=localhost",
    "MYSQLPORT=3306",
    "MYSQLDATABASE=weekly_roster_db",
    "MYSQLUSER=root",
    "MYSQLPASSWORD=root",
    "PORT=8080"
})
public class RailwayProductionStartupTest {

    @Autowired
    private SystemHealthService systemHealthService;

    @Autowired
    private DatabaseStartupDiagnosticLogger diagnosticLogger;

    @Test
    @DisplayName("Railway Production Startup: Context loads cleanly with Railway-style environment variables")
    void testContextLoadsWithRailwayVariables() {
        assertNotNull(diagnosticLogger);
        assertNotNull(systemHealthService);

        SystemHealthResponse health = systemHealthService.getSystemHealth();
        assertNotNull(health);
        assertNotNull(health.overallStatus());
        assertFalse(health.components().isEmpty());

        // Verify Database Connection is healthy
        boolean dbHealthy = health.components().stream()
                .anyMatch(c -> c.component().equals("Database Connection") && c.status().equals("HEALTHY"));
        assertTrue(dbHealthy, "Database connection must be established successfully using Railway-style variables");
    }
}