package com.weeklyroster.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.dto.response.RosterHealthReport;
import com.weeklyroster.dto.response.RosterVersionResponse;
import com.weeklyroster.dto.response.RollbackPreviewResponse;
import com.weeklyroster.dto.response.VersionAssignmentDiff;
import com.weeklyroster.dto.response.VersionComparisonResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.LeaveRequestRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.RosterVersionRepository;
import com.weeklyroster.repository.ShiftRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.*;

@Service
@Transactional
public class RosterVersionService {

    private final RosterVersionRepository versionRepository;
    private final RosterCycleRepository cycleRepository;
    private final RosterAssignmentRepository assignmentRepository;
    private final ShiftRepository shiftRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final AuditService auditService;
    private final RosterHealthService healthService;
    private final ObjectMapper objectMapper;

    @Autowired
    public RosterVersionService(RosterVersionRepository versionRepository,
                                RosterCycleRepository cycleRepository,
                                RosterAssignmentRepository assignmentRepository,
                                ShiftRepository shiftRepository,
                                @Autowired(required = false) LeaveRequestRepository leaveRequestRepository,
                                @Autowired(required = false) AuditService auditService,
                                @Autowired(required = false) RosterHealthService healthService,
                                ObjectMapper objectMapper) {
        this.versionRepository = versionRepository;
        this.cycleRepository = cycleRepository;
        this.assignmentRepository = assignmentRepository;
        this.shiftRepository = shiftRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.auditService = auditService;
        this.healthService = healthService;
        this.objectMapper = objectMapper;
    }

    public RosterVersion recordVersionSnapshot(RosterCycle cycle, String action, String actionReason, String username) {
        return recordVersionSnapshot(cycle, action, actionReason, username, null, null);
    }

    public RosterVersion recordVersionSnapshot(RosterCycle cycle, String action, String actionReason, String username, Integer explicitHealth, String impactSummary) {
        if (cycle == null) return null;

        List<RosterAssignment> assignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.getId());
        int currentCount = versionRepository.countByCycleId(cycle.getId());
        int nextVersion = currentCount + 1;

        // Capture assignment states in compact JSON
        List<Map<String, Object>> snapshotList = new ArrayList<>();
        for (RosterAssignment a : assignments) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("employeeCode", a.getEmployee() != null ? a.getEmployee().getEmployeeCode() : "");
            item.put("employeeName", a.getEmployee() != null ? (a.getEmployee().getFirstName() + " " + (a.getEmployee().getLastName() != null ? a.getEmployee().getLastName() : "")).trim() : "");
            item.put("employeeId", a.getEmployee() != null ? a.getEmployee().getId() : null);
            item.put("date", a.getRosterDate().toString());
            item.put("shiftType", a.getShift() != null ? a.getShift().getShiftType().name() : (a.isWeeklyOff() ? "OFF" : "LEAVE"));
            item.put("isOff", a.isWeeklyOff());
            item.put("isOnLeave", a.isOnLeave());
            item.put("reason", a.getAssignmentReason() != null ? a.getAssignmentReason() : "");
            snapshotList.add(item);
        }

        String jsonSnapshot;
        try {
            jsonSnapshot = objectMapper.writeValueAsString(snapshotList);
        } catch (Exception e) {
            jsonSnapshot = "[]";
        }

        Integer health = explicitHealth;
        if (health == null && healthService != null && !assignments.isEmpty()) {
            try {
                RosterHealthReport hr = healthService.evaluateHealth(cycle, assignments);
                health = (int) Math.round(hr.healthScore());
            } catch (Exception ignored) {
                health = 94;
            }
        }
        if (health == null) health = 94;

        RosterVersion v = new RosterVersion();
        v.setCycle(cycle);
        v.setVersionNumber(nextVersion);
        v.setAction(action != null ? action : "UPDATED");
        v.setActionReason(actionReason);
        v.setCreatedTimestamp(LocalDateTime.now());
        v.setCreatedBy(username != null ? username : "system");
        v.setGenerationMode(cycle.getGenerationMode() != null ? cycle.getGenerationMode().name() : "MANUAL");
        v.setStatus(cycle.getStatus() != null ? cycle.getStatus().name() : "TENTATIVE");
        v.setAffectedAssignmentsCount(assignments.size());
        v.setHealthScore(health);
        v.setImpactSummary(impactSummary);
        v.setSnapshotData(jsonSnapshot);

        return versionRepository.save(v);
    }

    public void deleteByCycleId(Long cycleId) {
        if (cycleId != null) {
            versionRepository.deleteByCycleIdNative(cycleId);
        }
    }

    @Transactional(readOnly = true)
    public List<RosterVersionResponse> getCycleVersions(Long cycleId) {
        return versionRepository.findByCycleIdOrderByVersionNumberDesc(cycleId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RosterVersionResponse getVersionDetails(Long cycleId, int versionNumber) {
        RosterVersion v = versionRepository.findByCycleIdAndVersionNumber(cycleId, versionNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Version " + versionNumber + " not found for cycle " + cycleId));
        return toResponse(v);
    }

    @Transactional(readOnly = true)
    public VersionComparisonResponse compareVersions(Long cycleId, int v1Num, int v2Num) {
        RosterVersion v1 = versionRepository.findByCycleIdAndVersionNumber(cycleId, v1Num)
                .orElseThrow(() -> new ResourceNotFoundException("Version " + v1Num + " not found for cycle " + cycleId));

        RosterVersion v2 = versionRepository.findByCycleIdAndVersionNumber(cycleId, v2Num)
                .orElseThrow(() -> new ResourceNotFoundException("Version " + v2Num + " not found for cycle " + cycleId));

        return buildComparison(v1, v2);
    }

    @Transactional(readOnly = true)
    public RollbackPreviewResponse previewRollback(Long cycleId, int targetVersionNumber) {
        RosterCycle cycle = cycleRepository.findById(cycleId)
                .orElseThrow(() -> new ResourceNotFoundException("Roster cycle not found with id: " + cycleId));

        if (cycle.getStatus() == RosterStatus.LOCKED) {
            throw new BusinessException("Final rosters cannot be rolled back through the normal workflow.");
        }

        RosterVersion targetVersion = versionRepository.findByCycleIdAndVersionNumber(cycleId, targetVersionNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Target version " + targetVersionNumber + " not found for cycle " + cycleId));

        RosterVersion latestVersion = versionRepository.findTopByCycleIdOrderByVersionNumberDesc(cycleId).orElse(targetVersion);
        int currentVersionNum = latestVersion.getVersionNumber();

        List<Map<String, Object>> targetList = parseSnapshot(targetVersion.getSnapshotData());
        List<RosterAssignment> currentAssignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycleId);

        Map<String, RosterAssignment> currentMap = new HashMap<>();
        for (RosterAssignment a : currentAssignments) {
            if (a.getEmployee() != null && a.getRosterDate() != null) {
                currentMap.put(a.getEmployee().getEmployeeCode() + ":" + a.getRosterDate(), a);
            }
        }

        List<VersionAssignmentDiff> diffs = new ArrayList<>();
        Set<String> affectedEmpCodes = new HashSet<>();
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        int changedCount = 0;
        int shiftChangesCount = 0;
        int offChangesCount = 0;

        for (Map<String, Object> item : targetList) {
            String empCode = (String) item.get("employeeCode");
            String empName = (String) item.get("employeeName");
            String dateStr = (String) item.get("date");
            LocalDate date = LocalDate.parse(dateStr);
            String targetShift = (String) item.get("shiftType");
            Boolean isOff = (Boolean) item.get("isOff");
            Boolean isLeave = (Boolean) item.get("isOnLeave");
            String reason = (String) item.getOrDefault("reason", "");

            RosterAssignment currentA = currentMap.get(empCode + ":" + dateStr);
            String curShift = currentA != null ? (currentA.getShift() != null ? currentA.getShift().getShiftType().name() : (currentA.isWeeklyOff() ? "OFF" : "LEAVE")) : "UNASSIGNED";
            String tgtShift = (Boolean.TRUE.equals(isOff) || "OFF".equalsIgnoreCase(targetShift)) ? "OFF" : targetShift;

            boolean changed = !Objects.equals(curShift, tgtShift);
            if (changed) {
                changedCount++;
                affectedEmpCodes.add(empCode);
                if ("OFF".equalsIgnoreCase(tgtShift) || "OFF".equalsIgnoreCase(curShift)) offChangesCount++;
                else shiftChangesCount++;

                // Constraint Check 1: Approved Leave Conflict
                if (currentA != null && currentA.isOnLeave() && !"OFF".equalsIgnoreCase(tgtShift) && !Boolean.TRUE.equals(isLeave)) {
                    blockers.add(empName + " has approved leave on " + date + ". Target version assigns working duty " + tgtShift + ".");
                }

                // Constraint Check 2: Female Evening / Night Safety Rule
                if (currentA != null && currentA.getEmployee() != null && currentA.getEmployee().getGender() == Gender.FEMALE) {
                    if ("EVENING".equalsIgnoreCase(tgtShift) || "NIGHT".equalsIgnoreCase(tgtShift)) {
                        blockers.add("Female safety policy violation: " + empName + " cannot be assigned " + tgtShift + " on " + date + ".");
                    }
                }
            }

            diffs.add(new VersionAssignmentDiff(
                    empCode,
                    empName != null ? empName : empCode,
                    date,
                    date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    curShift,
                    tgtShift,
                    getShiftTimingDisplay(curShift),
                    getShiftTimingDisplay(tgtShift),
                    reason,
                    changed
            ));
        }

        Integer currentHealth = latestVersion.getHealthScore() != null ? latestVersion.getHealthScore() : 94;
        Integer targetHealth = targetVersion.getHealthScore() != null ? targetVersion.getHealthScore() : 94;
        int healthDelta = targetHealth - currentHealth;

        boolean canRollback = blockers.isEmpty();
        String verdict = !canRollback ? "BLOCKED" : (warnings.isEmpty() ? "SAFE" : "WARNING");
        String verdictBadgeLabel = !canRollback ? "🔴 ROLLBACK BLOCKED" : (warnings.isEmpty() ? "🟢 SAFE TO ROLLBACK" : "🟠 ROLLBACK WITH WARNINGS");

        return new RollbackPreviewResponse(
                cycleId,
                currentVersionNum,
                targetVersionNumber,
                changedCount,
                affectedEmpCodes.size(),
                currentHealth,
                targetHealth,
                healthDelta,
                canRollback,
                verdict,
                verdictBadgeLabel,
                blockers,
                warnings,
                diffs
        );
    }

    @Transactional
    public RosterVersionResponse restoreVersion(Long cycleId, int versionNumber, String username) {
        RosterCycle cycle = cycleRepository.findById(cycleId)
                .orElseThrow(() -> new ResourceNotFoundException("Roster cycle not found with id: " + cycleId));

        if (cycle.getStatus() == RosterStatus.LOCKED) {
            throw new BusinessException("Cannot restore version on a locked roster cycle. Unlock the cycle first.");
        }

        RosterVersion targetVersion = versionRepository.findByCycleIdAndVersionNumber(cycleId, versionNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Version " + versionNumber + " not found for cycle " + cycleId));

        List<Map<String, Object>> snapshotList = parseSnapshot(targetVersion.getSnapshotData());
        List<RosterAssignment> currentAssignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycleId);
        Map<String, RosterAssignment> currentMap = new HashMap<>();
        for (RosterAssignment a : currentAssignments) {
            if (a.getEmployee() != null && a.getRosterDate() != null) {
                currentMap.put(a.getEmployee().getEmployeeCode() + ":" + a.getRosterDate(), a);
            }
        }

        Map<ShiftType, Shift> shiftMap = new EnumMap<>(ShiftType.class);
        if (shiftRepository != null) {
            shiftRepository.findByActiveTrueOrderByIdAsc().forEach(s -> shiftMap.put(s.getShiftType(), s));
        }

        int restoredDuties = 0;
        for (Map<String, Object> item : snapshotList) {
            String code = (String) item.get("employeeCode");
            String dateStr = (String) item.get("date");
            String shiftTypeName = (String) item.get("shiftType");
            Boolean isOff = (Boolean) item.get("isOff");
            Boolean isLeave = (Boolean) item.get("isOnLeave");

            String key = code + ":" + dateStr;
            RosterAssignment assignment = currentMap.get(key);
            if (assignment != null) {
                if (assignment.isOnLeave()) continue;
                boolean off = Boolean.TRUE.equals(isOff) || "OFF".equalsIgnoreCase(shiftTypeName);
                boolean leave = Boolean.TRUE.equals(isLeave) || "LEAVE".equalsIgnoreCase(shiftTypeName);
                assignment.setWeeklyOff(off);
                assignment.setOnLeave(leave);
                if (off || leave) {
                    assignment.setShift(shiftMap.get(ShiftType.OFF));
                } else if (shiftTypeName != null) {
                    try {
                        ShiftType st = ShiftType.valueOf(shiftTypeName);
                        assignment.setShift(shiftMap.get(st));
                    } catch (Exception ignored) {
                        assignment.setShift(shiftMap.get(ShiftType.GENERAL));
                    }
                }
                assignment.setOverridden(true);
                assignment.setAssignmentReason("Restored snapshot from version v" + versionNumber);
                assignmentRepository.save(assignment);
                restoredDuties++;
            }
        }

        RosterVersion newVersion = recordVersionSnapshot(
                cycle,
                "RESTORED",
                "Restored snapshot from version v" + versionNumber + " (" + restoredDuties + " duties restored)",
                username != null ? username : "admin",
                targetVersion.getHealthScore() != null ? targetVersion.getHealthScore() : 94,
                "Restored snapshot from version v" + versionNumber
        );

        if (auditService != null) {
            auditService.log(
                    AuditAction.ROSTER_REGENERATED,
                    "ROSTER_CYCLE",
                    cycleId,
                    cycleId,
                    null,
                    null,
                    "v" + versionNumber,
                    "v" + newVersion.getVersionNumber(),
                    "Admin " + username + " restored roster to version v" + versionNumber,
                    "MANUAL"
            );
        }

        return toResponse(newVersion);
    }

    @Transactional
    public RosterVersionResponse rollbackVersion(Long cycleId, int targetVersionNumber, String reason, String username) {
        RosterCycle cycle = cycleRepository.findById(cycleId)
                .orElseThrow(() -> new ResourceNotFoundException("Roster cycle not found with id: " + cycleId));

        if (cycle.getStatus() == RosterStatus.LOCKED) {
            throw new BusinessException("Final rosters cannot be rolled back through the normal workflow.");
        }

        RollbackPreviewResponse preview = previewRollback(cycleId, targetVersionNumber);
        if (!preview.canRollback()) {
            String firstBlocker = !preview.blockers().isEmpty() ? preview.blockers().get(0) : "Constraint violations detected.";
            throw new BusinessException("Target version no longer satisfies current roster constraints: " + firstBlocker);
        }

        RosterVersion targetVersion = versionRepository.findByCycleIdAndVersionNumber(cycleId, targetVersionNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Version " + targetVersionNumber + " not found for cycle " + cycleId));

        List<Map<String, Object>> snapshotList = parseSnapshot(targetVersion.getSnapshotData());
        List<RosterAssignment> currentAssignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycleId);
        Map<String, RosterAssignment> currentMap = new HashMap<>();
        for (RosterAssignment a : currentAssignments) {
            if (a.getEmployee() != null && a.getRosterDate() != null) {
                currentMap.put(a.getEmployee().getEmployeeCode() + ":" + a.getRosterDate(), a);
            }
        }

        Map<ShiftType, Shift> shiftMap = new EnumMap<>(ShiftType.class);
        if (shiftRepository != null) {
            shiftRepository.findByActiveTrueOrderByIdAsc().forEach(s -> shiftMap.put(s.getShiftType(), s));
        }

        int restoredDuties = 0;
        for (Map<String, Object> item : snapshotList) {
            String code = (String) item.get("employeeCode");
            String dateStr = (String) item.get("date");
            String shiftTypeName = (String) item.get("shiftType");
            Boolean isOff = (Boolean) item.get("isOff");
            Boolean isLeave = (Boolean) item.get("isOnLeave");

            String key = code + ":" + dateStr;
            RosterAssignment assignment = currentMap.get(key);
            if (assignment != null) {
                // If assignment has approved active leave, preserve leave status!
                if (assignment.isOnLeave()) {
                    continue;
                }

                boolean off = Boolean.TRUE.equals(isOff) || "OFF".equalsIgnoreCase(shiftTypeName);
                boolean leave = Boolean.TRUE.equals(isLeave) || "LEAVE".equalsIgnoreCase(shiftTypeName);
                assignment.setWeeklyOff(off);
                assignment.setOnLeave(leave);
                if (off || leave) {
                    assignment.setShift(shiftMap.get(ShiftType.OFF));
                } else if (shiftTypeName != null) {
                    try {
                        ShiftType st = ShiftType.valueOf(shiftTypeName);
                        assignment.setShift(shiftMap.get(st));
                    } catch (Exception ignored) {
                        assignment.setShift(shiftMap.get(ShiftType.GENERAL));
                    }
                }
                assignment.setOverridden(true);
                assignment.setAssignmentReason("Rollback to V" + targetVersionNumber + (reason != null ? ": " + reason : ""));
                assignmentRepository.save(assignment);
                restoredDuties++;
            }
        }

        // Create Brand-New Rollback Version (V_new) — never delete historical entries!
        String effectiveReason = "Rollback to V" + targetVersionNumber + (reason != null && !reason.isBlank() ? " (" + reason + ")" : "");
        RosterVersion newVersion = recordVersionSnapshot(
                cycle,
                "ROLLBACK",
                effectiveReason,
                username != null ? username : "admin",
                preview.projectedHealthScore(),
                "Rollback applied: " + restoredDuties + " duties restored to V" + targetVersionNumber
        );

        if (auditService != null) {
            auditService.log(
                    AuditAction.ROSTER_ROLLBACK,
                    "ROSTER_CYCLE",
                    cycleId,
                    cycleId,
                    null,
                    null,
                    "V" + preview.currentVersionNumber(),
                    "V" + newVersion.getVersionNumber(),
                    "Admin " + (username != null ? username : "admin") + " rolled back roster from V" + preview.currentVersionNumber() + " to V" + targetVersionNumber + ". Reason: " + effectiveReason,
                    "MANUAL"
            );
        }

        return toResponse(newVersion);
    }

    private VersionComparisonResponse buildComparison(RosterVersion v1, RosterVersion v2) {
        Long cycleId = v1.getCycle() != null ? v1.getCycle().getId() : (v2.getCycle() != null ? v2.getCycle().getId() : null);
        List<Map<String, Object>> list1 = parseSnapshot(v1.getSnapshotData());
        List<Map<String, Object>> list2 = parseSnapshot(v2.getSnapshotData());

        Map<String, String> map1 = new HashMap<>();
        Map<String, String> empNames = new HashMap<>();
        for (Map<String, Object> m : list1) {
            String code = (String) m.get("employeeCode");
            String date = (String) m.get("date");
            String shift = (String) m.get("shiftType");
            map1.put(code + ":" + date, shift != null ? shift : "OFF");
            empNames.put(code, (String) m.get("employeeName"));
        }

        Map<String, String> map2 = new HashMap<>();
        for (Map<String, Object> m : list2) {
            String code = (String) m.get("employeeCode");
            String date = (String) m.get("date");
            String shift = (String) m.get("shiftType");
            map2.put(code + ":" + date, shift != null ? shift : "OFF");
            if (!empNames.containsKey(code)) {
                empNames.put(code, (String) m.get("employeeName"));
            }
        }

        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(map1.keySet());
        allKeys.addAll(map2.keySet());

        List<VersionAssignmentDiff> diffs = new ArrayList<>();
        Set<String> affectedEmpCodes = new HashSet<>();
        int totalChanges = 0;
        int shiftChangesCount = 0;
        int offChangesCount = 0;

        for (String key : allKeys) {
            String[] parts = key.split(":");
            String empCode = parts[0];
            LocalDate date = LocalDate.parse(parts[1]);
            String shift1 = map1.getOrDefault(key, "UNASSIGNED");
            String shift2 = map2.getOrDefault(key, "UNASSIGNED");

            boolean changed = !Objects.equals(shift1, shift2);
            if (changed) {
                totalChanges++;
                affectedEmpCodes.add(empCode);
                if ("OFF".equalsIgnoreCase(shift1) || "OFF".equalsIgnoreCase(shift2)) offChangesCount++;
                else shiftChangesCount++;
            }

            diffs.add(new VersionAssignmentDiff(
                    empCode,
                    empNames.getOrDefault(empCode, empCode),
                    date,
                    date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    shift1,
                    shift2,
                    getShiftTimingDisplay(shift1),
                    getShiftTimingDisplay(shift2),
                    v2.getActionReason() != null ? v2.getActionReason() : "Roster modification",
                    changed
            ));
        }

        Integer h1 = v1.getHealthScore() != null ? v1.getHealthScore() : 94;
        Integer h2 = v2.getHealthScore() != null ? v2.getHealthScore() : 94;
        int hDelta = h2 - h1;

        return new VersionComparisonResponse(
                cycleId,
                v1.getVersionNumber(),
                v2.getVersionNumber(),
                v1.getCreatedTimestamp(),
                v2.getCreatedTimestamp(),
                v1.getAction(),
                v2.getAction(),
                h1,
                h2,
                hDelta,
                totalChanges,
                affectedEmpCodes.size(),
                shiftChangesCount,
                offChangesCount,
                diffs
        );
    }

    private List<Map<String, Object>> parseSnapshot(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String getShiftTimingDisplay(String shiftType) {
        if ("MORNING".equalsIgnoreCase(shiftType)) return "07:00 - 15:00";
        if ("GENERAL".equalsIgnoreCase(shiftType)) return "09:30 - 18:00";
        if ("EVENING".equalsIgnoreCase(shiftType)) return "14:00 - 22:00";
        if ("NIGHT".equalsIgnoreCase(shiftType)) return "22:00 - 07:00 (Next Day)";
        return "Rest Day (No duty)";
    }

    private RosterVersionResponse toResponse(RosterVersion v) {
        return new RosterVersionResponse(
                v.getId(),
                v.getCycle() != null ? v.getCycle().getId() : null,
                v.getVersionNumber(),
                v.getAction(),
                v.getActionReason(),
                v.getCreatedTimestamp(),
                v.getCreatedBy(),
                v.getGenerationMode(),
                v.getStatus(),
                v.getAffectedAssignmentsCount() != null ? v.getAffectedAssignmentsCount() : 0,
                v.getHealthScore() != null ? v.getHealthScore() : 94,
                v.getImpactSummary(),
                v.getSnapshotData()
        );
    }
}