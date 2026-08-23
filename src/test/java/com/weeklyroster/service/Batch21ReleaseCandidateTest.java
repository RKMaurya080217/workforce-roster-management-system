package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;

import com.weeklyroster.dto.request.*;
import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.*;
import java.time.LocalDate;
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
class Batch21ReleaseCandidateTest {

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
    private DevCredentialMirrorService devCredentialMirrorService;

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
    @DisplayName("Batch 21 â€” 5 & 32: Employee Email Immutability - rkmaurya080217@gmail.com is Authoritative")
    void testReleaseCandidateEmailImmutability() {
        String testUser = "emp001";
        String expectedEmail = "rkmaurya080217@gmail.com";

        // 1. Employee fetches profile
        authenticateEmployee(testUser);
        EmployeeResponse profile = employeeService.getMyProfile();
        assertEquals(expectedEmail, profile.email());

        // 2. Trigger Scheduler
        authenticateAdmin();
        LocalDate targetMonday = schedulerService.calculateTargetMonday(null);
        schedulerService.executeAutoGeneration(targetMonday);

        // 3. Trigger CSV mirror update
        Employee emp = employeeRepository.findByUserUsername(testUser).orElseThrow();
        if (devCredentialMirrorService != null) {
            devCredentialMirrorService.updateProfile(emp, null);
        }

        // 4. Verify DB entity remains untouched
        Employee dbEmp = employeeRepository.findByUserUsername(testUser).orElseThrow();
        assertEquals(expectedEmail, dbEmp.getEmail(), "Persisted email must never be modified by automatic processes");
    }

    @Test
    @DisplayName("Batch 21 â€” 10 & 11: Auto-Generation Hard Lock & Manual Priority")
    void testAutoGenerationHardLockAndManualPriority() {
        authenticateAdmin();
        LocalDate targetMonday = schedulerService.calculateTargetMonday(null);

        // 1. Auto-generate target Monday (immediate upcoming cycle)
        RosterCycleResponse autoCycle = schedulerService.executeAutoGeneration(targetMonday);
        assertNotNull(autoCycle.id());

        // 2. Attempt next-next week auto-generation -> MUST THROW BusinessException
        LocalDate nextNextMonday = targetMonday.plusWeeks(1);
        assertThrows(BusinessException.class, () -> schedulerService.executeAutoGeneration(nextNextMonday),
                "Automatic generation of next-next week must be strictly prohibited");

        // 3. Repeated scheduler call on existing cycle skips generation (DO NOTHING)
        RosterCycleResponse skipped = schedulerService.executeAutoGeneration(targetMonday);
        assertEquals(autoCycle.id(), skipped.id());
    }

    @Test
    @DisplayName("Batch 21 â€” 13 & 14: Security Release Audit & Server-Side IDOR Protection")
    void testSecurityAndIdorProtection() {
        // Authenticated as emp001
        authenticateEmployee("emp001");
        Employee emp1 = employeeRepository.findByUserUsername("emp001").orElseThrow();

        // emp001 updating own profile
        EmployeeResponse res = employeeService.updateMyProfile(
                new UpdateMyProfileRequest("Rajat", "Maurya", "rkmaurya080217@gmail.com", "8565005534")
        );
        assertEquals("rkmaurya080217@gmail.com", res.email());

        // Verify other employee records remain completely isolated
        Employee emp2 = employeeRepository.findByUserUsername("emp002").orElseThrow();
        assertNotEquals("rkmaurya080217@gmail.com", emp2.getEmail());
    }

    @Test
    @DisplayName("Batch 21 â€” 22: Version History Safety - Immutable History & Version Snapshots")
    void testVersionHistorySafety() {
        authenticateAdmin();
        LocalDate monday = LocalDate.of(2027, 2, 1);

        // Generate and publish cycle
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL);
        rosterService.publishRoster(cycle.id());

        List<RosterVersionResponse> versions = versionService.getCycleVersions(cycle.id());
        assertTrue(versions.size() >= 2);
        assertTrue(versions.stream().anyMatch(v -> "GENERATED".equals(v.action())));
        assertTrue(versions.stream().anyMatch(v -> "PUBLISHED".equals(v.action())));
    }
}