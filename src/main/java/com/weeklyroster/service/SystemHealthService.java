package com.weeklyroster.service;

import com.weeklyroster.dto.response.ComponentHealth;
import com.weeklyroster.dto.response.SystemHealthResponse;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.ShiftRepository;
import com.weeklyroster.service.email.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class SystemHealthService {

    private final DataSource dataSource;
    private final EmployeeRepository employeeRepository;
    private final ShiftRepository shiftRepository;
    private final RosterCycleRepository cycleRepository;
    private final SseEmitterService sseEmitterService;
    private final EmailService emailService;
    private final Environment environment;

    @Value("${roster.auto-generation.timezone:Asia/Kolkata}")
    private String timezone;

    @Autowired
    public SystemHealthService(DataSource dataSource,
                               EmployeeRepository employeeRepository,
                               ShiftRepository shiftRepository,
                               RosterCycleRepository cycleRepository,
                               @Autowired(required = false) SseEmitterService sseEmitterService,
                               @Autowired(required = false) EmailService emailService,
                               Environment environment) {
        this.dataSource = dataSource;
        this.employeeRepository = employeeRepository;
        this.shiftRepository = shiftRepository;
        this.cycleRepository = cycleRepository;
        this.sseEmitterService = sseEmitterService;
        this.emailService = emailService;
        this.environment = environment;
    }

    public SystemHealthResponse getSystemHealth() {
        List<ComponentHealth> components = new ArrayList<>();
        boolean hasFailure = false;
        boolean hasWarning = false;

        // 1. Backend Core & JVM
        long maxMem = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        long freeMem = Runtime.getRuntime().freeMemory() / (1024 * 1024);
        components.add(new ComponentHealth("Backend Runtime", "HEALTHY", "JVM 17+ active in timezone " + timezone, "Max Memory: " + maxMem + " MB, Free: " + freeMem + " MB"));

        // 2. Database Connectivity & Dynamic Engine Identification
        String dbType = "UNKNOWN";
        try (Connection conn = dataSource.getConnection()) {
            if (conn.isValid(2)) {
                String productName = conn.getMetaData().getDatabaseProductName();
                String productVersion = conn.getMetaData().getDatabaseProductVersion();
                if (productName != null && productName.toLowerCase().contains("sqlite")) {
                    dbType = "SQLite (Local File)";
                    components.add(new ComponentHealth("Database Connection", "HEALTHY", "SQLite 3 Local Database Active", "Engine: " + productName + " " + productVersion));
                } else {
                    dbType = "MySQL (Production)";
                    components.add(new ComponentHealth("Database Connection", "HEALTHY", "MySQL Production Database Active", "Catalog: " + conn.getCatalog() + " (" + productVersion + ")"));
                }
            } else {
                hasFailure = true;
                components.add(new ComponentHealth("Database Connection", "FAILED", "Connection validation failed", "Timeout"));
            }
        } catch (Exception e) {
            hasFailure = true;
            components.add(new ComponentHealth("Database Connection", "FAILED", "Database error: " + e.getMessage(), "Disconnected"));
        }

        // 3. Required Master Data (Employees & Shifts)
        long empCount = 0;
        long shiftCount = 0;
        if (!hasFailure) {
            try {
                empCount = employeeRepository.count();
                shiftCount = shiftRepository.count();
            } catch (Exception e) {
                hasWarning = true;
            }
        }
        if (empCount >= 7 && shiftCount >= 4) {
            components.add(new ComponentHealth("Master Data Integrity", "HEALTHY", "Staff and Shifts properly populated", "Employees: " + empCount + ", Shifts: " + shiftCount));
        } else {
            hasWarning = true;
            components.add(new ComponentHealth("Master Data Integrity", "WARNING", "Low staff or shift definitions", "Employees: " + empCount + ", Shifts: " + shiftCount));
        }

        // 4. Notification Transport (SSE)
        int sseConnections = sseEmitterService != null ? sseEmitterService.getActiveConnectionCount() : 0;
        components.add(new ComponentHealth("Real-Time Notification SSE", "HEALTHY", "Server-Sent Events emitter active", "Active browser streams: " + sseConnections));

        // 5. Transactional Email Distribution Engine
        String emailProviderStatus = "Unconfigured";
        if (emailService != null) {
            String activeProvider = emailService.getActiveProviderName();
            boolean isConfigured = emailService.isConfigured();
            emailProviderStatus = activeProvider + (isConfigured ? " (Configured)" : " (Standby)");
            if (isConfigured) {
                components.add(new ComponentHealth("Email Distribution Engine", "HEALTHY", activeProvider + " Transactional Email Provider Active", "Status: Ready for dispatch"));
            } else {
                components.add(new ComponentHealth("Email Distribution Engine", "HEALTHY", activeProvider + " Standby (Simulation/Logging active)", "Status: Simulation Active"));
            }
        } else {
            components.add(new ComponentHealth("Email Distribution Engine", "WARNING", "Email service uninitialized", "Inactive"));
        }

        // 6. Roster Scheduling Engine & Active Cycles
        long cycleCount = 0;
        if (!hasFailure) {
            try {
                cycleCount = cycleRepository.count();
            } catch (Exception e) {
                hasWarning = true;
            }
        }
        components.add(new ComponentHealth("Roster Scheduling Engine", "HEALTHY", "Automated Constraint Solver ready", "Total historical cycles: " + cycleCount));

        // Active Profile resolution
        String[] activeProfiles = environment.getActiveProfiles();
        String currentProfile = (activeProfiles != null && activeProfiles.length > 0)
                ? String.join(", ", activeProfiles)
                : "local";

        String overall = hasFailure ? "FAILED" : (hasWarning ? "WARNING" : "HEALTHY");
        return new SystemHealthResponse(
                overall,
                LocalDateTime.now(),
                "2.8.5",
                currentProfile,
                dbType,
                emailProviderStatus,
                timezone,
                components
        );
    }
}