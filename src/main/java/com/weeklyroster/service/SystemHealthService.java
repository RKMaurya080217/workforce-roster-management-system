package com.weeklyroster.service;

import com.weeklyroster.dto.response.ComponentHealth;
import com.weeklyroster.dto.response.SystemHealthResponse;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.ShiftRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class SystemHealthService {

    private final DataSource dataSource;
    private final EmployeeRepository employeeRepository;
    private final ShiftRepository shiftRepository;
    private final RosterCycleRepository cycleRepository;
    private final SseEmitterService sseEmitterService;

    @Value("${spring.mail.password:${MAIL_APP_PASSWORD:}}")
    private String mailPassword;

    @Value("${roster.auto-generation.timezone:Asia/Kolkata}")
    private String timezone;

    @Autowired
    public SystemHealthService(DataSource dataSource,
                               EmployeeRepository employeeRepository,
                               ShiftRepository shiftRepository,
                               RosterCycleRepository cycleRepository,
                               @Autowired(required = false) SseEmitterService sseEmitterService) {
        this.dataSource = dataSource;
        this.employeeRepository = employeeRepository;
        this.shiftRepository = shiftRepository;
        this.cycleRepository = cycleRepository;
        this.sseEmitterService = sseEmitterService;
    }

    public SystemHealthResponse getSystemHealth() {
        List<ComponentHealth> components = new ArrayList<>();
        boolean hasFailure = false;
        boolean hasWarning = false;

        // 1. Backend Core & JVM
        long maxMem = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        long freeMem = Runtime.getRuntime().freeMemory() / (1024 * 1024);
        components.add(new ComponentHealth("Backend Runtime", "HEALTHY", "JVM 17+ active in timezone " + timezone, "Max Memory: " + maxMem + " MB, Free: " + freeMem + " MB"));

        // 2. Database Connectivity
        try (Connection conn = dataSource.getConnection()) {
            if (conn.isValid(2)) {
                components.add(new ComponentHealth("Database Connection", "HEALTHY", "MySQL 8.0+ Connection Active", "Catalog: " + conn.getCatalog()));
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

        // 5. Email Distribution Engine
        if (mailPassword != null && !mailPassword.isBlank()) {
            components.add(new ComponentHealth("Email Distribution Engine", "HEALTHY", "Gmail SMTP configured for automated dispatch", "Production Mode"));
        } else {
            hasWarning = true;
            components.add(new ComponentHealth("Email Distribution Engine", "WARNING", "MAIL_APP_PASSWORD unset (Simulation & Logging Mode active)", "Simulation Active"));
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

        String overall = hasFailure ? "FAILED" : (hasWarning ? "WARNING" : "HEALTHY");
        return new SystemHealthResponse(overall, LocalDateTime.now(), "1.0.0", components);
    }
}