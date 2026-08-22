package com.weeklyroster.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.dto.response.RosterVersionResponse;
import com.weeklyroster.dto.response.VersionAssignmentDiff;
import com.weeklyroster.dto.response.VersionComparisonResponse;
import com.weeklyroster.entity.RosterAssignment;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.RosterVersion;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.RosterVersionRepository;
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
    private final ObjectMapper objectMapper;

    public RosterVersionService(RosterVersionRepository versionRepository,
                                RosterCycleRepository cycleRepository,
                                RosterAssignmentRepository assignmentRepository,
                                ObjectMapper objectMapper) {
        this.versionRepository = versionRepository;
        this.cycleRepository = cycleRepository;
        this.assignmentRepository = assignmentRepository;
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
        v.setSnapshotData(jsonSnapshot);

        return versionRepository.save(v);
    }

    @Transactional(readOnly = true)
    public List<RosterVersionResponse> getCycleVersions(Long cycleId) {
        return versionRepository.findByCycleIdOrderByVersionNumberDesc(cycleId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public VersionComparisonResponse compareVersions(Long cycleId, int v1Num, int v2Num) {
        RosterVersion v1 = versionRepository.findByCycleIdAndVersionNumber(cycleId, v1Num)
                .orElseThrow(() -> new ResourceNotFoundException("Version " + v1Num + " not found for cycle " + cycleId));

        RosterVersion v2 = versionRepository.findByCycleIdAndVersionNumber(cycleId, v2Num)
                .orElseThrow(() -> new ResourceNotFoundException("Version " + v2Num + " not found for cycle " + cycleId));

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
                v.getSnapshotData()
        );
    }
}
