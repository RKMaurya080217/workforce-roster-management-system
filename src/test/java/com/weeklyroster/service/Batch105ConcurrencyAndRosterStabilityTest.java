package com.weeklyroster.service;

import com.weeklyroster.dto.request.ManualAssignmentRequest;
import com.weeklyroster.dto.response.ManualRosterMatrixResponse;
import com.weeklyroster.dto.response.RosterCandidateResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.ShiftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wrms.dev.credential-mirror.enabled=false",
        "spring.datasource.url=jdbc:sqlite:target/test-wrms.db?busy_timeout=30000&journal_mode=WAL"
})
public class Batch105ConcurrencyAndRosterStabilityTest {

    static {
        try {
            System.setProperty("spring.profiles.active", "test");
            System.setProperty("spring.datasource.url", "jdbc:sqlite:target/test-wrms.db?busy_timeout=30000&journal_mode=WAL");
            java.io.File targetDir = new java.io.File("target");
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
            java.io.File realDb = new java.io.File("data/wrms.db");
            java.io.File testDb = new java.io.File("target/test-wrms.db");
            if (realDb.exists()) {
                java.nio.file.Files.copy(realDb.toPath(), testDb.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[Batch 105] Isolated replica database created at target/test-wrms.db from data/wrms.db");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterCycleRepository rosterCycleRepository;

    @Autowired
    private RosterAssignmentRepository rosterAssignmentRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private VisitorAnalyticsService visitorAnalyticsService;

    @Autowired
    private AuditService auditService;

    private LocalDate nextMonday;

    @BeforeEach
    public void setUp() {
        nextMonday = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
                .plusWeeks(2); // Use future week to isolate test data
    }

    @Test
    @DisplayName("Test 1: One generation request completes normally with valid assignments")
    public void test1_singleGenerationCompletesNormally() {
        LocalDate monday = nextMonday.plusWeeks(1);
        RosterCycleResponse response = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL, 6);

        assertNotNull(response);
        assertNotNull(response.id());
        assertEquals(monday, response.startDate());
        assertEquals(monday.plusDays(6), response.endDate());
        assertFalse(response.assignments().isEmpty(), "Assignments must be generated");

        List<RosterCycle> cycles = rosterCycleRepository.findOverlappingCycles(monday, monday.plusDays(6));
        assertEquals(1, cycles.size(), "Exactly one cycle should exist in DB");
    }

    @Test
    @DisplayName("Test 2: Rapid repeated Generate calls do not duplicate cycles")
    public void test2_rapidRepeatedGenerationDoesNotDuplicateCycles() {
        LocalDate monday = nextMonday.plusWeeks(2);

        for (int i = 0; i < 5; i++) {
            RosterCycleResponse resp = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL, 6);
            assertNotNull(resp);
        }

        List<RosterCycle> cycles = rosterCycleRepository.findOverlappingCycles(monday, monday.plusDays(6));
        assertEquals(1, cycles.size(), "Rapid sequential calls must not create duplicate cycles");
    }

    @Test
    @DisplayName("Test 3: Simultaneous generation requests handled safely without SQLITE_BUSY_SNAPSHOT")
    public void test3_simultaneousGenerationsHandledSafelyWithoutSnapshotError() throws Exception {
        LocalDate monday = nextMonday.plusWeeks(3);
        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<RosterCycleResponse>> futures = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await(5, TimeUnit.SECONDS);
                    return rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL, 6);
                } catch (Exception e) {
                    failures.incrementAndGet();
                    throw new RuntimeException(e);
                }
            }));
        }

        // Fire all threads simultaneously
        startLatch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS));

        assertEquals(0, failures.get(), "Zero exceptions expected during concurrent generation");

        for (Future<RosterCycleResponse> future : futures) {
            RosterCycleResponse res = future.get();
            assertNotNull(res);
            assertEquals(monday, res.startDate());
        }

        List<RosterCycle> cycles = rosterCycleRepository.findOverlappingCycles(monday, monday.plusDays(6));
        assertEquals(1, cycles.size(), "Exactly 1 cycle must be present despite simultaneous requests");
    }

    @Test
    @DisplayName("Test 4: Concurrent reads do not corrupt or duplicate data")
    public void test4_concurrentReadsDoNotCorruptData() throws Exception {
        LocalDate monday = nextMonday.plusWeeks(4);
        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch startLatch = new CountDownLatch(1);

        // 1 Writer
        Future<RosterCycleResponse> writerFuture = executor.submit(() -> {
            startLatch.await(5, TimeUnit.SECONDS);
            return rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL, 6);
        });

        // 5 Concurrent Readers
        List<Future<ManualRosterMatrixResponse>> readerFutures = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            readerFutures.add(executor.submit(() -> {
                startLatch.await(5, TimeUnit.SECONDS);
                return rosterService.getManualRosterMatrix(null, monday);
            }));
        }

        startLatch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS));

        assertNotNull(writerFuture.get());
        for (Future<ManualRosterMatrixResponse> rf : readerFutures) {
            ManualRosterMatrixResponse matrix = rf.get();
            assertNotNull(matrix);
        }

        List<RosterCycle> cycles = rosterCycleRepository.findOverlappingCycles(monday, monday.plusDays(6));
        assertEquals(1, cycles.size());
    }

    @Test
    @DisplayName("Test 5: Concurrent simulated visitor writes and heartbeats do not corrupt Admin generation")
    public void test5_concurrentVisitorWritesDoNotCorruptGeneration() throws Exception {
        LocalDate monday = nextMonday.plusWeeks(5);
        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger backgroundWriteCount = new AtomicInteger(0);

        // Generation task
        Future<RosterCycleResponse> genFuture = executor.submit(() -> {
            startLatch.await(5, TimeUnit.SECONDS);
            return rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL, 6);
        });

        // Background visitor writes
        List<Future<?>> bgFutures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int id = i;
            bgFutures.add(executor.submit(() -> {
                try {
                    startLatch.await(5, TimeUnit.SECONDS);
                    for (int j = 0; j < 3; j++) {
                        visitorAnalyticsService.recordHeartbeat("test_user_" + id + "_" + j);
                        backgroundWriteCount.incrementAndGet();
                        Thread.sleep(50);
                    }
                } catch (Exception ignored) {}
            }));
        }

        startLatch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS));

        RosterCycleResponse genRes = genFuture.get();
        assertNotNull(genRes);
        assertTrue(backgroundWriteCount.get() > 0, "Background writes must have executed concurrently");

        List<RosterCycle> cycles = rosterCycleRepository.findOverlappingCycles(monday, monday.plusDays(6));
        assertEquals(1, cycles.size(), "Roster cycle must persist cleanly without snapshot error");
    }

    @Test
    @DisplayName("Test 6: Failed generation leaves no corrupt/partial draft")
    public void test6_failedGenerationLeavesNoCorruptDraft() {
        LocalDate tuesday = nextMonday.plusWeeks(50).plusDays(1); // Far future invalid non-Monday

        assertThrows(BusinessException.class, () -> {
            rosterService.generateWeeklyRoster(tuesday, GenerationMode.MANUAL, 6);
        });

        var cycle = rosterCycleRepository.findByStartDateAndEndDate(tuesday, tuesday.plusDays(6));
        assertTrue(cycle.isEmpty(), "No cycle should be left in database after validation failure");
    }

    @Test
    @DisplayName("Test 7: Genuine unrecoverable error produces clear message")
    public void test7_lockedCycleProducesClearMessage() {
        LocalDate monday = nextMonday.plusWeeks(7);
        rosterCycleRepository.findByStartDateAndEndDate(monday, monday.plusDays(6))
                .ifPresent(c -> {
                    c.setStatus(RosterStatus.GENERATED);
                    rosterCycleRepository.save(c);
                });

        rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL, 6);

        RosterCycle cycle = rosterCycleRepository.findByStartDateAndEndDate(monday, monday.plusDays(6))
                .orElseThrow();
        cycle.setStatus(RosterStatus.LOCKED);
        rosterCycleRepository.save(cycle);

        try {
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL, 6);
            });

            assertTrue(ex.getMessage().toLowerCase().contains("locked"),
                    "Exception message must clearly state the cycle is locked: " + ex.getMessage());
        } finally {
            cycle.setStatus(RosterStatus.GENERATED);
            rosterCycleRepository.save(cycle);
        }
    }

    @Test
    @DisplayName("Test 8: Subsequent valid generation succeeds after transient conflict")
    public void test8_subsequentGenerationSucceedsAfterUnlock() {
        LocalDate monday = nextMonday.plusWeeks(8);
        rosterCycleRepository.findByStartDateAndEndDate(monday, monday.plusDays(6))
                .ifPresent(c -> {
                    c.setStatus(RosterStatus.GENERATED);
                    rosterCycleRepository.save(c);
                });

        rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL, 6);

        RosterCycle cycle = rosterCycleRepository.findByStartDateAndEndDate(monday, monday.plusDays(6))
                .orElseThrow();
        cycle.setStatus(RosterStatus.LOCKED);
        rosterCycleRepository.save(cycle);

        // Fails while locked
        assertThrows(BusinessException.class, () -> {
            rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL, 6);
        });

        // Unlock and regenerate: succeeds
        cycle.setStatus(RosterStatus.GENERATED);
        rosterCycleRepository.save(cycle);

        RosterCycleResponse regenerated = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL, 6);
        assertNotNull(regenerated);
        assertEquals(monday, regenerated.startDate());
    }

    @Test
    @DisplayName("Test 9: Existing historical cycles remain intact")
    public void test9_existingHistoricalCyclesRemainIntact() {
        LocalDate historicalMonday = nextMonday.minusWeeks(10);
        RosterCycleResponse historicalResp = rosterService.generateWeeklyRoster(historicalMonday, GenerationMode.MANUAL, 6);
        assertNotNull(historicalResp);

        int historicalAssignmentCount = rosterAssignmentRepository.findByCycleId(historicalResp.id()).size();
        assertTrue(historicalAssignmentCount > 0);

        // Generate a new cycle for upcoming week
        LocalDate upcomingMonday = nextMonday.plusWeeks(9);
        RosterCycleResponse newResp = rosterService.generateWeeklyRoster(upcomingMonday, GenerationMode.MANUAL, 6);
        assertNotNull(newResp);

        // Verify historical cycle is intact
        RosterCycle historicalCycleAfter = rosterCycleRepository.findById(historicalResp.id()).orElse(null);
        assertNotNull(historicalCycleAfter, "Historical cycle must exist");
        int countAfter = rosterAssignmentRepository.findByCycleId(historicalResp.id()).size();
        assertEquals(historicalAssignmentCount, countAfter, "Historical assignments must remain unaltered");
    }

    @Test
    @DisplayName("Test 10: Strict manual assignments survive generation and candidate selection")
    public void test10_strictManualAssignmentSurvivesGeneration() {
        LocalDate monday = nextMonday.plusWeeks(10);
        List<Employee> activeEmployees = employeeRepository.findByActiveTrueOrderByIdAsc();
        assertFalse(activeEmployees.isEmpty());
        Employee targetEmployee = activeEmployees.get(0);
        LocalDate targetDate = monday.plusDays(2); // Wednesday

        // 1. Admin explicitly sets a manual assignment
        rosterService.saveManualAssignment(
                new ManualAssignmentRequest(null, monday, targetEmployee.getId(), targetDate, ShiftType.NIGHT, false, "VIP Shift Request"),
                "Admin"
        );

        // Verify it was saved as manual
        List<RosterAssignment> preCheck = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(targetEmployee.getId(), targetDate);
        assertFalse(preCheck.isEmpty());
        assertTrue(preCheck.get(0).isManualAssignment(), "Must be marked manualAssignment=true");
        assertEquals(ShiftType.NIGHT, preCheck.get(0).getShift().getShiftType());

        // 2. Trigger automatic candidate generation & candidate 1 persistence
        List<RosterCandidateResponse> candidates = rosterService.generateMultipleCandidates(monday, 6, 3);
        assertFalse(candidates.isEmpty());

        // 3. Verify manual assignment was preserved in DB
        List<RosterAssignment> postCheck = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(targetEmployee.getId(), targetDate);
        assertFalse(postCheck.isEmpty());
        RosterAssignment preserved = postCheck.get(0);
        assertEquals(ShiftType.NIGHT, preserved.getShift().getShiftType(), "Manual shift must NOT be overwritten by generator");
        assertTrue(preserved.isManualAssignment(), "Manual flag must be preserved");
    }
}
