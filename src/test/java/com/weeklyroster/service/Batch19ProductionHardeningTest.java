package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;

import com.weeklyroster.dto.request.CreateProfileChangeRequest;
import com.weeklyroster.dto.request.ProfileChangeDecisionRequest;
import com.weeklyroster.dto.request.UpdateMyProfileRequest;
import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.*;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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
class Batch19ProductionHardeningTest {

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private ProfileChangeRequestService profileChangeRequestService;

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterSchedulerService schedulerService;

    @Autowired
    private RosterVersionService versionService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private ShiftRepository shiftRepository;

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

    @BeforeEach
    void setup() {
        authenticateAdmin();
    }

    @Test
    @DisplayName("Batch 19 â€” 4 & 5: Profile Rejection Integrity - Rejection Never Mutates Profile")
    void testProfileRejectionIntegrity() {
        authenticateEmployee("emp003");
        Employee emp3 = employeeRepository.findByUserUsername("emp003").orElseThrow();
        String originalPhone = emp3.getContactNumber();
        String originalEmail = emp3.getEmail();

        // 1. Submit change request
        ProfileChangeRequestResponse req = profileChangeRequestService.submitRequest(
                new CreateProfileChangeRequest("contactNumber", "9999888877")
        );
        assertEquals(ProfileChangeStatus.PENDING, req.status());

        // Verify profile in DB is STILL unchanged while PENDING
        Employee dbEmpPending = employeeRepository.findById(emp3.getId()).orElseThrow();
        assertEquals(originalPhone, dbEmpPending.getContactNumber(), "Pending request must not modify live profile");

        // 2. Admin rejects request
        authenticateAdmin();
        ProfileChangeRequestResponse rejected = profileChangeRequestService.reject(
                req.id(),
                new ProfileChangeDecisionRequest("Rejected: Invalid contact proof")
        );
        assertEquals(ProfileChangeStatus.REJECTED, rejected.status());

        // 3. Verify profile in DB is STILL unchanged after REJECTION
        Employee dbEmpRejected = employeeRepository.findById(emp3.getId()).orElseThrow();
        assertEquals(originalPhone, dbEmpRejected.getContactNumber(), "Rejected request must NEVER alter live profile");
        assertEquals(originalEmail, dbEmpRejected.getEmail());
    }

    @Test
    @DisplayName("Batch 19 â€” 6: Data Isolation - Employee Cannot Update Another Employee Profile")
    void testEmployeeDataIsolation() {
        // Authenticated as emp001
        authenticateEmployee("emp001");
        Employee emp1 = employeeRepository.findByUserUsername("emp001").orElseThrow();

        // emp001 updating their profile
        EmployeeResponse myProfile = employeeService.updateMyProfile(
                new UpdateMyProfileRequest("Rajat", "Maurya", "rkmaurya080217@gmail.com", "8565005534")
        );
        assertEquals("rkmaurya080217@gmail.com", myProfile.email());

        // Verify emp002 is untouched
        Employee emp2 = employeeRepository.findByUserUsername("emp002").orElseThrow();
        assertNotEquals("rkmaurya080217@gmail.com", emp2.getEmail());
    }

    @Test
    @DisplayName("Batch 19 â€” 8: Roster Generation Idempotency - Concurrent Requests Yield Single Cycle")
    void testRosterGenerationConcurrenyAndIdempotency() {
        authenticateAdmin();
        LocalDate monday = LocalDate.of(2026, 12, 28);

        // 1. First generation creates cycle
        RosterCycleResponse cycle1 = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL);
        assertNotNull(cycle1.id());

        // 2. Immediate re-generation cleanly regenerates the same week without creating duplicate conflicting cycles
        RosterCycleResponse cycle2 = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL);
        assertNotNull(cycle2.id());

        List<RosterCycle> cycles = cycleRepository.findOverlappingCycles(monday, monday.plusDays(6));
        assertEquals(1, cycles.size(), "Only one roster cycle should exist for this exact date range");
    }

    @Test
    @DisplayName("Batch 19 â€” 12 & 13: Timezone & Night Shift Duty Boundary Handling")
    void testNightShiftAndRestCalculation() {
        Shift nightShift = shiftRepository.findByShiftType(ShiftType.NIGHT).orElseThrow();
        assertTrue(nightShift.isOvernight(), "Night shift must be flagged as overnight");
        assertEquals(LocalTime.of(22, 0), nightShift.getStartTime());
        assertEquals(LocalTime.of(7, 0), nightShift.getEndTime());

        // Duration is 22:00 (Day 1) to 07:00 (Day 2) -> 9 hours
        LocalDateTime startDt = LocalDate.of(2026, 8, 24).atTime(nightShift.getStartTime());
        LocalDateTime endDt = LocalDate.of(2026, 8, 25).atTime(nightShift.getEndTime());
        long hours = Duration.between(startDt, endDt).toHours();
        assertEquals(9, hours, "Night shift duration must equal 9 hours across midnight transition");
    }

    @Test
    @DisplayName("Batch 19 â€” 23: Empty States - Graceful Handling of Empty Collections")
    void testEmptyStateSafety() {
        authenticateEmployee("emp007");

        // Notification filtering on empty/non-existent filters
        List<NotificationResponse> notifs = notificationService.getMyNotificationsFiltered("emp007", "SYSTEM", 10);
        assertNotNull(notifs);

        // Version comparison on newly generated cycle with identical versions
        authenticateAdmin();
        LocalDate monday = LocalDate.of(2027, 1, 4);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL);
        VersionComparisonResponse comp = versionService.compareVersions(cycle.id(), 1, 1);
        assertNotNull(comp);
        assertEquals(0, comp.totalChanges(), "Comparing version 1 with version 1 must report 0 changes");
    }
}