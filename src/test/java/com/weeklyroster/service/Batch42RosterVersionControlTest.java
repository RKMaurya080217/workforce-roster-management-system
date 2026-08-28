package com.weeklyroster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.dto.request.RollbackRequest;
import com.weeklyroster.dto.response.RosterVersionResponse;
import com.weeklyroster.dto.response.RollbackPreviewResponse;
import com.weeklyroster.dto.response.VersionComparisonResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class Batch42RosterVersionControlTest {

    @Mock
    private RosterVersionRepository versionRepository;
    @Mock
    private RosterCycleRepository cycleRepository;
    @Mock
    private RosterAssignmentRepository assignmentRepository;
    @Mock
    private ShiftRepository shiftRepository;
    @Mock
    private LeaveRequestRepository leaveRequestRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private RosterHealthService healthService;

    private ObjectMapper objectMapper = new ObjectMapper();
    private RosterVersionService versionService;

    private RosterCycle cycle;
    private Employee emp1;
    private Employee emp2;
    private Shift morningShift;
    private Shift generalShift;
    private Shift offShift;

    @BeforeEach
    void setUp() {
        versionService = new RosterVersionService(
                versionRepository,
                cycleRepository,
                assignmentRepository,
                shiftRepository,
                leaveRequestRepository,
                auditService,
                healthService,
                objectMapper
        );

        cycle = new RosterCycle();
        cycle.setId(100L);
        cycle.setStartDate(LocalDate.of(2026, 8, 31));
        cycle.setEndDate(LocalDate.of(2026, 9, 6));
        cycle.setStatus(RosterStatus.TENTATIVE);

        emp1 = new Employee();
        emp1.setId(1L);
        emp1.setEmployeeCode("EMP001");
        emp1.setFirstName("Rajat");
        emp1.setLastName("Maurya");
        emp1.setGender(Gender.MALE);
        emp1.setActive(true);

        emp2 = new Employee();
        emp2.setId(2L);
        emp2.setEmployeeCode("EMP002");
        emp2.setFirstName("Sapna");
        emp2.setLastName("Pandey");
        emp2.setGender(Gender.FEMALE);
        emp2.setActive(true);

        morningShift = new Shift();
        morningShift.setId(1L);
        morningShift.setShiftType(ShiftType.MORNING);
        morningShift.setActive(true);

        generalShift = new Shift();
        generalShift.setId(2L);
        generalShift.setShiftType(ShiftType.GENERAL);
        generalShift.setActive(true);

        offShift = new Shift();
        offShift.setId(5L);
        offShift.setShiftType(ShiftType.OFF);
        offShift.setActive(true);

        when(cycleRepository.findById(100L)).thenReturn(Optional.of(cycle));
        when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(morningShift, generalShift, offShift));
    }

    @Test
    void test1_InitialGenerationCreatesV1() {
        RosterAssignment a1 = new RosterAssignment();
        a1.setId(10L);
        a1.setCycle(cycle);
        a1.setEmployee(emp1);
        a1.setRosterDate(LocalDate.of(2026, 8, 31));
        a1.setShift(morningShift);
        a1.setWeeklyOff(false);

        when(assignmentRepository.findByCycleIdOrderByRosterDateAsc(100L)).thenReturn(List.of(a1));
        when(versionRepository.countByCycleId(100L)).thenReturn(0);
        when(versionRepository.save(any(RosterVersion.class))).thenAnswer(inv -> {
            RosterVersion rv = inv.getArgument(0);
            rv.setId(1L);
            return rv;
        });

        RosterVersion v = versionService.recordVersionSnapshot(cycle, "INITIAL_GENERATION", "Initial tentative roster generated", "system", 94, "Coverage: Safe");

        assertNotNull(v);
        assertEquals(1, v.getVersionNumber());
        assertEquals("INITIAL_GENERATION", v.getAction());
        assertEquals(94, v.getHealthScore());
        assertTrue(v.getSnapshotData().contains("EMP001"));
        assertTrue(v.getSnapshotData().contains("MORNING"));
    }

    @Test
    void test2_AdminModificationCreatesNewVersionV2() {
        when(versionRepository.countByCycleId(100L)).thenReturn(1);
        when(versionRepository.save(any(RosterVersion.class))).thenAnswer(inv -> {
            RosterVersion rv = inv.getArgument(0);
            rv.setId(2L);
            return rv;
        });

        RosterVersion v2 = versionService.recordVersionSnapshot(cycle, "ADMIN_MODIFICATION", "Shift change on 2026-08-31", "admin", 96, "Rest: Safe");

        assertNotNull(v2);
        assertEquals(2, v2.getVersionNumber());
        assertEquals("ADMIN_MODIFICATION", v2.getAction());
        assertEquals(96, v2.getHealthScore());
    }

    @Test
    void test3_EmployeeRequestCreatesV3() {
        when(versionRepository.countByCycleId(100L)).thenReturn(2);
        when(versionRepository.save(any(RosterVersion.class))).thenAnswer(inv -> {
            RosterVersion rv = inv.getArgument(0);
            rv.setId(3L);
            return rv;
        });

        RosterVersion v3 = versionService.recordVersionSnapshot(cycle, "EMPLOYEE_REQUEST", "Shift change requested by Rajat Maurya", "admin", 95, "Preference: Improved");

        assertNotNull(v3);
        assertEquals(3, v3.getVersionNumber());
        assertEquals("EMPLOYEE_REQUEST", v3.getAction());
    }

    @Test
    void test4_CompareVersionsCalculatesDiffsAndHealthDelta() {
        RosterVersion v1 = new RosterVersion();
        v1.setId(1L);
        v1.setCycle(cycle);
        v1.setVersionNumber(1);
        v1.setAction("INITIAL_GENERATION");
        v1.setCreatedTimestamp(LocalDateTime.now().minusHours(2));
        v1.setHealthScore(92);
        v1.setSnapshotData("[{\"employeeCode\":\"EMP001\",\"employeeName\":\"Rajat Maurya\",\"date\":\"2026-08-31\",\"shiftType\":\"GENERAL\",\"isOff\":false,\"isOnLeave\":false}]");

        RosterVersion v2 = new RosterVersion();
        v2.setId(2L);
        v2.setCycle(cycle);
        v2.setVersionNumber(2);
        v2.setAction("ADMIN_MODIFICATION");
        v2.setCreatedTimestamp(LocalDateTime.now().minusHours(1));
        v2.setHealthScore(95);
        v2.setSnapshotData("[{\"employeeCode\":\"EMP001\",\"employeeName\":\"Rajat Maurya\",\"date\":\"2026-08-31\",\"shiftType\":\"MORNING\",\"isOff\":false,\"isOnLeave\":false}]");

        when(versionRepository.findByCycleIdAndVersionNumber(100L, 1)).thenReturn(Optional.of(v1));
        when(versionRepository.findByCycleIdAndVersionNumber(100L, 2)).thenReturn(Optional.of(v2));

        VersionComparisonResponse diff = versionService.compareVersions(100L, 1, 2);

        assertNotNull(diff);
        assertEquals(1, diff.version1Number());
        assertEquals(2, diff.version2Number());
        assertEquals(1, diff.totalChanges());
        assertEquals(1, diff.affectedEmployeesCount());
        assertEquals(3, diff.healthDelta()); // 95 - 92 = +3
        assertEquals(1, diff.diffs().size());
        assertEquals("GENERAL", diff.diffs().get(0).v1Shift());
        assertEquals("MORNING", diff.diffs().get(0).v2Shift());
    }

    @Test
    void test5_RollbackPreviewSafe() {
        RosterVersion v1 = new RosterVersion();
        v1.setId(1L);
        v1.setCycle(cycle);
        v1.setVersionNumber(1);
        v1.setHealthScore(94);
        v1.setSnapshotData("[{\"employeeCode\":\"EMP001\",\"employeeName\":\"Rajat Maurya\",\"date\":\"2026-08-31\",\"shiftType\":\"MORNING\",\"isOff\":false,\"isOnLeave\":false}]");

        RosterVersion v2 = new RosterVersion();
        v2.setId(2L);
        v2.setCycle(cycle);
        v2.setVersionNumber(2);
        v2.setHealthScore(91);

        RosterAssignment curA = new RosterAssignment();
        curA.setId(10L);
        curA.setCycle(cycle);
        curA.setEmployee(emp1);
        curA.setRosterDate(LocalDate.of(2026, 8, 31));
        curA.setShift(generalShift);
        curA.setWeeklyOff(false);
        curA.setOnLeave(false);

        when(versionRepository.findByCycleIdAndVersionNumber(100L, 1)).thenReturn(Optional.of(v1));
        when(versionRepository.findTopByCycleIdOrderByVersionNumberDesc(100L)).thenReturn(Optional.of(v2));
        when(assignmentRepository.findByCycleIdOrderByRosterDateAsc(100L)).thenReturn(List.of(curA));

        RollbackPreviewResponse preview = versionService.previewRollback(100L, 1);

        assertNotNull(preview);
        assertTrue(preview.canRollback());
        assertEquals("SAFE", preview.verdict());
        assertEquals(1, preview.affectedAssignmentsCount());
        assertEquals(1, preview.affectedEmployeesCount());
        assertEquals(3, preview.healthDelta()); // 94 - 91 = +3
    }

    @Test
    void test6_RollbackBlockedByApprovedLeaveConflict() {
        // Target version has emp1 working on 2026-08-31, but emp1 is now on approved leave!
        RosterVersion v1 = new RosterVersion();
        v1.setId(1L);
        v1.setCycle(cycle);
        v1.setVersionNumber(1);
        v1.setHealthScore(94);
        v1.setSnapshotData("[{\"employeeCode\":\"EMP001\",\"employeeName\":\"Rajat Maurya\",\"date\":\"2026-08-31\",\"shiftType\":\"MORNING\",\"isOff\":false,\"isOnLeave\":false}]");

        RosterAssignment curA = new RosterAssignment();
        curA.setId(10L);
        curA.setCycle(cycle);
        curA.setEmployee(emp1);
        curA.setRosterDate(LocalDate.of(2026, 8, 31));
        curA.setShift(offShift);
        curA.setWeeklyOff(false);
        curA.setOnLeave(true); // Approved Leave!

        when(versionRepository.findByCycleIdAndVersionNumber(100L, 1)).thenReturn(Optional.of(v1));
        when(versionRepository.findTopByCycleIdOrderByVersionNumberDesc(100L)).thenReturn(Optional.of(v1));
        when(assignmentRepository.findByCycleIdOrderByRosterDateAsc(100L)).thenReturn(List.of(curA));

        RollbackPreviewResponse preview = versionService.previewRollback(100L, 1);

        assertNotNull(preview);
        assertFalse(preview.canRollback());
        assertEquals("BLOCKED", preview.verdict());
        assertFalse(preview.blockers().isEmpty());
        assertTrue(preview.blockers().get(0).contains("approved leave"));
    }

    @Test
    void test7_RollbackCreatesNewVersionAndDoesNotDeleteHistory() {
        RosterVersion v1 = new RosterVersion();
        v1.setId(1L);
        v1.setCycle(cycle);
        v1.setVersionNumber(1);
        v1.setHealthScore(94);
        v1.setSnapshotData("[{\"employeeCode\":\"EMP001\",\"employeeName\":\"Rajat Maurya\",\"date\":\"2026-08-31\",\"shiftType\":\"MORNING\",\"isOff\":false,\"isOnLeave\":false}]");

        RosterVersion v2 = new RosterVersion();
        v2.setId(2L);
        v2.setCycle(cycle);
        v2.setVersionNumber(2);
        v2.setHealthScore(90);

        RosterAssignment curA = new RosterAssignment();
        curA.setId(10L);
        curA.setCycle(cycle);
        curA.setEmployee(emp1);
        curA.setRosterDate(LocalDate.of(2026, 8, 31));
        curA.setShift(generalShift);
        curA.setWeeklyOff(false);
        curA.setOnLeave(false);

        when(versionRepository.findByCycleIdAndVersionNumber(100L, 1)).thenReturn(Optional.of(v1));
        when(versionRepository.findTopByCycleIdOrderByVersionNumberDesc(100L)).thenReturn(Optional.of(v2));
        when(versionRepository.countByCycleId(100L)).thenReturn(2);
        when(assignmentRepository.findByCycleIdOrderByRosterDateAsc(100L)).thenReturn(List.of(curA));
        when(versionRepository.save(any(RosterVersion.class))).thenAnswer(inv -> {
            RosterVersion rv = inv.getArgument(0);
            rv.setId(3L);
            return rv;
        });

        RosterVersionResponse result = versionService.rollbackVersion(100L, 1, "Rollback test", "admin");

        assertNotNull(result);
        assertEquals(3, result.versionNumber()); // V3 created!
        assertEquals("ROLLBACK", result.action());
        verify(versionRepository, never()).delete(any());
        verify(versionRepository, never()).deleteByCycleIdNative(anyLong());
        verify(auditService, atLeastOnce()).log(eq(AuditAction.ROSTER_ROLLBACK), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void test8_RollbackOnFinalOrLockedCycleBlocked() {
        cycle.setStatus(RosterStatus.LOCKED);

        assertThrows(BusinessException.class, () -> {
            versionService.previewRollback(100L, 1);
        });

        assertThrows(BusinessException.class, () -> {
            versionService.rollbackVersion(100L, 1, "Locked test", "admin");
        });
    }

    @Test
    void test9_FemaleSafetyViolationInRollbackBlocked() {
        // Target version assigned Female staff to NIGHT shift!
        RosterVersion v1 = new RosterVersion();
        v1.setId(1L);
        v1.setCycle(cycle);
        v1.setVersionNumber(1);
        v1.setHealthScore(94);
        v1.setSnapshotData("[{\"employeeCode\":\"EMP002\",\"employeeName\":\"Sapna Pandey\",\"date\":\"2026-08-31\",\"shiftType\":\"NIGHT\",\"isOff\":false,\"isOnLeave\":false}]");

        RosterAssignment curA = new RosterAssignment();
        curA.setId(20L);
        curA.setCycle(cycle);
        curA.setEmployee(emp2); // Female!
        curA.setRosterDate(LocalDate.of(2026, 8, 31));
        curA.setShift(generalShift);
        curA.setWeeklyOff(false);
        curA.setOnLeave(false);

        when(versionRepository.findByCycleIdAndVersionNumber(100L, 1)).thenReturn(Optional.of(v1));
        when(versionRepository.findTopByCycleIdOrderByVersionNumberDesc(100L)).thenReturn(Optional.of(v1));
        when(assignmentRepository.findByCycleIdOrderByRosterDateAsc(100L)).thenReturn(List.of(curA));

        RollbackPreviewResponse preview = versionService.previewRollback(100L, 1);

        assertNotNull(preview);
        assertFalse(preview.canRollback());
        assertEquals("BLOCKED", preview.verdict());
        assertTrue(preview.blockers().get(0).contains("Female safety policy violation"));
    }

    @Test
    void test10_GetCycleVersionsReturnsDescendingList() {
        RosterVersion v1 = new RosterVersion();
        v1.setId(1L);
        v1.setCycle(cycle);
        v1.setVersionNumber(1);
        v1.setAction("INITIAL_GENERATION");

        RosterVersion v2 = new RosterVersion();
        v2.setId(2L);
        v2.setCycle(cycle);
        v2.setVersionNumber(2);
        v2.setAction("ADMIN_MODIFICATION");

        when(versionRepository.findByCycleIdOrderByVersionNumberDesc(100L)).thenReturn(List.of(v2, v1));

        List<RosterVersionResponse> list = versionService.getCycleVersions(100L);

        assertEquals(2, list.size());
        assertEquals(2, list.get(0).versionNumber());
        assertEquals(1, list.get(1).versionNumber());
    }
}