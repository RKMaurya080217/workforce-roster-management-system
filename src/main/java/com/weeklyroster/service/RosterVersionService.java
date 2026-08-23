package com.weeklyroster.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.dto.response.RosterVersionResponse;
import com.weeklyroster.dto.response.VersionAssignmentDiff;
import com.weeklyroster.dto.response.VersionComparisonResponse;
import com.weeklyroster.entity.RosterAssignment;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.RosterStatus;
import com.weeklyroster.entity.RosterVersion;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.RosterVersionRepository;
import com.weeklyroster.repository.ShiftRepository;
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
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public RosterVersionService(RosterVersionRepository versionRepository,
                                RosterCycleRepository cycleRepository,
                                RosterAssignmentRepository assignmentRepository,
                                ShiftRepository shiftRepository,
                                @org.springframework.beans.factory.annotation.Autowired(required = false) AuditService auditService,
                                ObjectMapper objectMapper) {
        this.versionRepository = versionRepository;
        this.cycleRepository = cycleRepository;
        this.assignmentRepository = assignmentRepository;
        this.shiftRepository = shiftRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public RosterVersion recordVersionSnapshot(RosterCycle cycle, String action, String actionReason, String username) {
        if (cycle == null) return null;

        List<RosterAssignment> assignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.getId());
        int currentCount = versionRepository.countByCycleId(cycle.getId());
        int nextVersion = currentCount + 1;

        List<Map<String, Object>> snapshotList = new ArrayList<>();
        for (RosterAssignment a : assignments) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("employeeCode", a.getEmployee().getEmployeeCode());
            item.put("employeeName", a.getEmployee().getFirstName() + " " + (a.getEmployee().getLastName() != null ? a.getEmployee().getLastName() : ""));
            item.put("date", a.getRosterDate().toString());
            item.put("shiftType", a.getShift() != null ? a.getShift().getShiftType().name() : (a.isWeeklyOff() ? "OFF" : "LEAVE"));
            item.put("isOff", a.isWeeklyOff());
            item.put("isOnLeave", a.isOnLeave());
            snapshotList.add(item);
        }

        String jsonSnapshot;
        try {
            jsonSnapshot = objectMapper.writeValueAsString(snapshotList);
        } catch (Exception e) {
            jsonSnapshot = "[]";
        }

        RosterVersion v = new RosterVersion();
        v.setCycle(cycle);
        v.setVersionNumber(nextVersion);
        v.setAction(action != null ? action : "UPDATED");
        v.setActionReason(actionReason);
        v.setCreatedTimestamp(LocalDateTime.now());
        v.setCreatedBy(username != null ? username : "system");
        v.setGenerationMode(cycle.getGenerationMode() != null ? cycle.getGenerationMode().name() : "MANUAL");
        v.setStatus(cycle.getStatus() != null ? cycle.getStatus().name() : "GENERATED");
        v.setAffectedAssignmentsCount(assignments.size());
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

    @Transactional
    public RosterVersionResponse restoreVersion(Long cycleId, int versionNumber, String username) {
        RosterVersion targetVersion = versionRepository.findByCycleIdAndVersionNumber(cycleId, versionNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Version " + versionNumber + " not found for cycle " + cycleId));

        RosterCycle cycle = targetVersion.getCycle();
        if (cycle == null) {
            throw new BusinessException("Cannot restore version without a valid parent cycle");
        }
        if (cycle.getStatus() == RosterStatus.LOCKED) {
            throw new BusinessException("Cannot restore version on a locked roster cycle. Unlock the cycle first.");
        }

        List<Map<String, Object>> snapshotList = parseSnapshot(targetVersion.getSnapshotData());
        if (snapshotList.isEmpty()) {
            throw new BusinessException("Target version snapshot data is empty");
        }

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
                assignmentRepository.save(assignment);
                restoredDuties++;
            }
        }

        // Safe restore invariant: Never delete historical snapshots; create a brand-new version entry!
        RosterVersion newVersion = recordVersionSnapshot(
                cycle,
                "RESTORED",
                "Restored snapshot from version v" + versionNumber + " (" + restoredDuties + " duties restored)",
                username != null ? username : "admin"
        );

        if (auditService != null) {
            auditService.log(
                    com.weeklyroster.entity.AuditAction.ROSTER_REGENERATED,
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

    @Transactional(readOnly = true)
    public VersionComparisonResponse compareVersions(Long cycleId, int v1Num, int v2Num) {
        RosterVersion v1 = versionRepository.findByCycleIdAndVersionNumber(cycleId, v1Num)
                .orElseThrow(() -> new ResourceNotFoundException("Version " + v1Num + " not found for cycle " + cycleId));

        RosterVersion v2 = versionRepository.findByCycleIdAndVersionNumber(cycleId, v2Num)
                .orElseThrow(() -> new ResourceNotFoundException("Version " + v2Num + " not found for cycle " + cycleId));

        return buildComparison(v1, v2);
    }

    @Transactional(readOnly = true)
    public VersionComparisonResponse compareVersionsByIds(Long v1Id, Long v2Id) {
        RosterVersion v1 = versionRepository.findById(v1Id)
                .orElseThrow(() -> new ResourceNotFoundException("Version not found with id " + v1Id));

        RosterVersion v2 = versionRepository.findById(v2Id)
                .orElseThrow(() -> new ResourceNotFoundException("Version not found with id " + v2Id));

        return buildComparison(v1, v2);
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
        int totalChanges = 0;

        for (String key : allKeys) {
            String[] parts = key.split(":");
            String empCode = parts[0];
            LocalDate date = LocalDate.parse(parts[1]);
            String shift1 = map1.getOrDefault(key, "UNASSIGNED");
            String shift2 = map2.getOrDefault(key, "UNASSIGNED");

            boolean changed = !Objects.equals(shift1, shift2);
            if (changed) totalChanges++;

            diffs.add(new VersionAssignmentDiff(
                    empCode,
                    empNames.getOrDefault(empCode, empCode),
                    date,
                    date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    shift1,
                    shift2,
                    changed
            ));
        }

        return new VersionComparisonResponse(
                cycleId,
                v1.getVersionNumber(),
                v2.getVersionNumber(),
                v1.getCreatedTimestamp(),
                v2.getCreatedTimestamp(),
                v1.getAction(),
                v2.getAction(),
                totalChanges,
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
                v.getSnapshotData()
        );
    }
}
