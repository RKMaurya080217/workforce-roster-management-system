package com.weeklyroster.service;

import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wrms.dev.credential-mirror.enabled=false",
        "spring.datasource.url=jdbc:sqlite:target/test-wrms-b115-repro.db?busy_timeout=30000&journal_mode=WAL"
})
public class Batch115ReproductionTest {

    static {
        try {
            System.setProperty("spring.profiles.active", "test");
            System.setProperty("spring.datasource.url", "jdbc:sqlite:target/test-wrms-b115-repro.db?busy_timeout=30000&journal_mode=WAL");
            File targetDir = new File("target");
            if (!targetDir.exists()) targetDir.mkdirs();
            File realDb = new File("data/wrms.db");
            File testDb = new File("target/test-wrms-b115-repro.db");
            if (realDb.exists()) {
                Files.copy(realDb.toPath(), testDb.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterAssignmentRepository rosterAssignmentRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeePreferenceRepository employeePreferenceRepository;

    @Autowired
    private RecurringShiftPreferenceRepository recurringShiftPreferenceRepository;

    @Autowired
    private DateShiftPreferenceRepository dateShiftPreferenceRepository;

    @Autowired
    private HolidayRepository holidayRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "admin",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    @DisplayName("Reproduction: Nov 09 to Nov 15 Weekly Off Count Check")
    void testReproduction_Nov09ToNov15_WeeklyOffCount() {
        System.out.println("=== PREFERENCES IN DATABASE ===");
        recurringShiftPreferenceRepository.findAll().forEach(rp -> 
            System.out.println("Recurring: " + rp.getEmployee().getFirstName() + " " + rp.getDayOfWeek() + " -> " + rp.getShiftType() + " active=" + rp.isActive())
        );
        dateShiftPreferenceRepository.findAll().forEach(dp -> 
            System.out.println("Date: " + dp.getEmployee().getFirstName() + " " + dp.getPreferenceDate() + " -> " + dp.getShiftType())
        );
        leaveRequestRepository.findAll().forEach(lr -> 
            System.out.println("Leave: " + lr.getEmployee().getFirstName() + " " + lr.getStartDate() + " to " + lr.getEndDate() + " status=" + lr.getStatus())
        );
        holidayRepository.findAll().forEach(h -> 
            System.out.println("Holiday: " + h.getHolidayDate() + " -> " + h.getName())
        );

        // First ensure 02-Nov to 08-Nov is generated so cross-cycle history exists
        LocalDate prevWeek = LocalDate.of(2026, 11, 2);
        RosterCycleResponse cycle1 = rosterService.generateWeeklyRoster(prevWeek, GenerationMode.AUTOMATIC, 6);

        System.out.println("=== WEEK 02-Nov-2026 to 08-Nov-2026 ===");
        List<RosterAssignment> a1 = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle1.id());
        Map<String, List<RosterAssignment>> empMap1 = new HashMap<>();
        for (RosterAssignment a : a1) {
            String name = a.getEmployee().getFirstName() + " " + a.getEmployee().getLastName();
            empMap1.computeIfAbsent(name.trim(), k -> new ArrayList<>()).add(a);
        }
        for (Map.Entry<String, List<RosterAssignment>> entry : empMap1.entrySet()) {
            long offCount = entry.getValue().stream().filter(RosterAssignment::isWeeklyOff).count();
            long workCount = entry.getValue().stream().filter(x -> !x.isWeeklyOff() && !x.isOnLeave()).count();
            System.out.printf("%-20s: Work=%d, Off=%d%n", entry.getKey(), workCount, offCount);
            for (RosterAssignment a : entry.getValue()) {
                String status = a.isOnLeave() ? "LEAVE" : (a.isWeeklyOff() ? "OFF" : (a.getShift() != null ? a.getShift().getShiftType().name() : "NONE"));
                System.out.printf("   %s (%s): %s%n", a.getRosterDate(), a.getRosterDate().getDayOfWeek(), status);
            }
        }

        LocalDate cycleStart = LocalDate.of(2026, 11, 9);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(cycleStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
        Map<String, List<RosterAssignment>> empMap = new HashMap<>();
        for (RosterAssignment a : assignments) {
            String name = a.getEmployee().getFirstName() + " " + a.getEmployee().getLastName();
            empMap.computeIfAbsent(name.trim(), k -> new ArrayList<>()).add(a);
        }

        System.out.println("=== REPRODUCTION RESULTS FOR WEEK 09-Nov-2026 to 15-Nov-2026 ===");
        for (Map.Entry<String, List<RosterAssignment>> entry : empMap.entrySet()) {
            long offCount = entry.getValue().stream().filter(RosterAssignment::isWeeklyOff).count();
            long leaveCount = entry.getValue().stream().filter(RosterAssignment::isOnLeave).count();
            long workCount = entry.getValue().stream().filter(a -> !a.isWeeklyOff() && !a.isOnLeave()).count();
            System.out.printf("%-20s: Work=%d, Off=%d, Leave=%d%n", entry.getKey(), workCount, offCount, leaveCount);
            for (RosterAssignment a : entry.getValue()) {
                String status = a.isOnLeave() ? "LEAVE" : (a.isWeeklyOff() ? "OFF" : (a.getShift() != null ? a.getShift().getShiftType().name() : "NONE"));
                System.out.printf("   %s (%s): %s%n", a.getRosterDate(), a.getRosterDate().getDayOfWeek(), status);
            }
        }

        for (Map.Entry<String, List<RosterAssignment>> entry : empMap.entrySet()) {
            long offCount = entry.getValue().stream().filter(RosterAssignment::isWeeklyOff).count();
            assertEquals(1, offCount, "Employee " + entry.getKey() + " must have EXACTLY 1 Weekly Off!");
        }
    }
}
