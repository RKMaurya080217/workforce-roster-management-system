package com.weeklyroster.service;

import com.weeklyroster.dto.request.ExportReportRequest;
import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@Transactional(readOnly = true)
public class ExportCenterService {

    private final RosterCycleRepository cycleRepository;
    private final RosterAssignmentRepository assignmentRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final AuditLogRepository auditLogRepository;
    private final WorkloadAnalyticsService workloadAnalyticsService;
    private final RosterValidatorService rosterValidatorService;
    private final ShiftRepository shiftRepository;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ExportCenterService(RosterCycleRepository cycleRepository,
                               RosterAssignmentRepository assignmentRepository,
                               EmployeeRepository employeeRepository,
                               LeaveRequestRepository leaveRequestRepository,
                               AuditLogRepository auditLogRepository,
                               WorkloadAnalyticsService workloadAnalyticsService,
                               RosterValidatorService rosterValidatorService,
                               ShiftRepository shiftRepository) {
        this.cycleRepository = cycleRepository;
        this.assignmentRepository = assignmentRepository;
        this.employeeRepository = employeeRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.auditLogRepository = auditLogRepository;
        this.workloadAnalyticsService = workloadAnalyticsService;
        this.rosterValidatorService = rosterValidatorService;
        this.shiftRepository = shiftRepository;
    }

    public byte[] generateExport(ExportReportRequest req) {
        String reportType = req.reportType() != null ? req.reportType().toUpperCase() : "WEEKLY_ROSTER";
        String format = req.format() != null ? req.format().toLowerCase() : "xlsx";

        LocalDate start = req.startDate();
        LocalDate end = req.endDate();

        if (req.cycleId() != null) {
            RosterCycle cycle = cycleRepository.findById(req.cycleId()).orElse(null);
            if (cycle != null) {
                if (start == null) start = cycle.getStartDate();
                if (end == null) end = cycle.getEndDate();
            }
        }

        if (start == null || end == null) {
            List<RosterCycle> allCycles = cycleRepository.findAll();
            if (!allCycles.isEmpty()) {
                RosterCycle latestCycle = allCycles.stream()
                        .filter(c -> c.getStatus() == RosterStatus.ACTIVE || c.getStatus() == RosterStatus.PUBLISHED)
                        .reduce((first, second) -> second)
                        .orElse(allCycles.get(allCycles.size() - 1));
                if (start == null) start = latestCycle.getStartDate();
                if (end == null) end = latestCycle.getEndDate();
            }
        }

        if (start == null) start = LocalDate.now().minusWeeks(1);
        if (end == null) end = LocalDate.now().plusWeeks(1);

        List<String[]> rows = new ArrayList<>();
        String title;

        switch (reportType) {
            case "WEEKLY_ROSTER" -> {
                title = "WRMS Weekly Roster Export (" + start + " to " + end + ")";
                rows.add(new String[]{"Date", "Employee Code", "Employee Name", "Gender", "Shift Type", "Shift Timing", "Status", "Overridden"});
                List<RosterAssignment> assignments = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(start, end);
                if (assignments.isEmpty() && req.cycleId() == null) {
                    List<RosterCycle> cycles = cycleRepository.findAll();
                    for (int i = cycles.size() - 1; i >= 0; i--) {
                        RosterCycle c = cycles.get(i);
                        List<RosterAssignment> cycleAssignments = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(c.getStartDate(), c.getEndDate());
                        if (!cycleAssignments.isEmpty()) {
                            assignments = cycleAssignments;
                            break;
                        }
                    }
                    if (assignments.isEmpty()) {
                        assignments = assignmentRepository.findAll();
                    }
                }
                for (RosterAssignment a : assignments) {
                    Employee e = a.getEmployee();
                    Shift s = a.getShift();
                    String statusStr = a.isOnLeave() ? "ON_LEAVE" : (a.isWeeklyOff() ? "WEEKLY_OFF" : "WORKING");
                    String timingStr = (s != null && !a.isWeeklyOff() && !a.isOnLeave()) ? s.getTimingDisplay() : "-";
                    rows.add(new String[]{
                            a.getRosterDate() != null ? a.getRosterDate().toString() : "-",
                            e != null ? e.getEmployeeCode() : "-",
                            e != null ? e.getFirstName() + " " + (e.getLastName() != null ? e.getLastName() : "") : "-",
                            e != null && e.getGender() != null ? e.getGender().name() : "-",
                            s != null ? s.getShiftType().name() : "-",
                            timingStr,
                            statusStr,
                            a.isOverridden() ? "YES" : "NO"
                    });
                }
            }
            case "EMPLOYEE_SCHEDULE" -> {
                title = "WRMS Employee Work Schedule (" + start + " to " + end + ")";
                rows.add(new String[]{"Employee Code", "Employee Name", "Email", "Date", "Shift", "Working/Off", "Notes"});
                List<RosterAssignment> assignments = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(start, end);
                if (assignments.isEmpty() && req.cycleId() == null) {
                    List<RosterCycle> cycles = cycleRepository.findAll();
                    for (int i = cycles.size() - 1; i >= 0; i--) {
                        RosterCycle c = cycles.get(i);
                        List<RosterAssignment> cycleAssignments = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(c.getStartDate(), c.getEndDate());
                        if (!cycleAssignments.isEmpty()) {
                            assignments = cycleAssignments;
                            break;
                        }
                    }
                    if (assignments.isEmpty()) {
                        assignments = assignmentRepository.findAll();
                    }
                }
                for (RosterAssignment a : assignments) {
                    if (req.employeeId() == null || (a.getEmployee() != null && a.getEmployee().getId().equals(req.employeeId()))) {
                        Employee e = a.getEmployee();
                        Shift s = a.getShift();
                        rows.add(new String[]{
                                e != null ? e.getEmployeeCode() : "-",
                                e != null ? e.getFirstName() + " " + (e.getLastName() != null ? e.getLastName() : "") : "-",
                                e != null && e.getEmail() != null ? e.getEmail() : "-",
                                a.getRosterDate() != null ? a.getRosterDate().toString() : "-",
                                s != null ? s.getShiftType().name() : (a.isWeeklyOff() ? "OFF" : "LEAVE"),
                                a.isWeeklyOff() ? "OFF" : (a.isOnLeave() ? "LEAVE" : "DUTY"),
                                a.isOverridden() ? "Manual Override" : "Standard Assignment"
                        });
                    }
                }
            }
            case "EMPLOYEE_MASTER" -> {
                title = "WRMS Employee Master Directory";
                rows.add(new String[]{"Employee Code", "Full Name", "Email", "Gender", "Contact Number", "System Role", "Status"});
                List<Employee> employees = employeeRepository.findAll();
                for (Employee e : employees) {
                    String roleStr = (e.getUser() != null && e.getUser().getRole() != null) ? e.getUser().getRole().name() : "ROLE_EMPLOYEE";
                    rows.add(new String[]{
                            e.getEmployeeCode(),
                            e.getFirstName() + " " + (e.getLastName() != null ? e.getLastName() : ""),
                            e.getEmail() != null ? e.getEmail() : "-",
                            e.getGender() != null ? e.getGender().name() : "-",
                            e.getContactNumber() != null ? e.getContactNumber() : "-",
                            roleStr,
                            e.isActive() ? "ACTIVE" : "INACTIVE"
                    });
                }
            }
            case "LEAVE_REGISTER", "LEAVE_REPORT" -> {
                title = "WRMS Leave Register (" + start + " to " + end + ")";
                rows.add(new String[]{"Leave ID", "Employee Code", "Employee Name", "Start Date", "End Date", "Reason", "Status", "Requested At", "Reviewed At"});
                List<LeaveRequest> leaves = leaveRequestRepository.findByStartDateLessThanEqualAndEndDateGreaterThanEqual(end, start);
                if (leaves.isEmpty()) {
                    leaves = leaveRequestRepository.findAll();
                }
                for (LeaveRequest l : leaves) {
                    if (req.employeeId() == null || (l.getEmployee() != null && l.getEmployee().getId().equals(req.employeeId()))) {
                        Employee e = l.getEmployee();
                        rows.add(new String[]{
                                "#" + l.getId(),
                                e != null ? e.getEmployeeCode() : "-",
                                e != null ? e.getFirstName() + " " + (e.getLastName() != null ? e.getLastName() : "") : "-",
                                l.getStartDate() != null ? l.getStartDate().toString() : "-",
                                l.getEndDate() != null ? l.getEndDate().toString() : "-",
                                l.getReason() != null ? l.getReason() : "-",
                                l.getStatus() != null ? l.getStatus().name() : "-",
                                l.getRequestedAt() != null ? l.getRequestedAt().format(TIME_FMT) : "-",
                                l.getReviewedAt() != null ? l.getReviewedAt().format(TIME_FMT) : "-"
                        });
                    }
                }
            }
            case "WORKLOAD_REPORT" -> {
                title = "WRMS Employee Workload Analysis Report (" + start + " to " + end + ")";
                rows.add(new String[]{"Employee Code", "Employee Name", "Working Days", "OFF Days", "Morning", "General", "Evening", "Night", "Weekend Duties", "Holiday Duties", "Consecutive Days", "Workload Score", "Rating"});
                WorkloadReportResponse wr = workloadAnalyticsService.calculateWorkload(start, end, req.employeeId());
                if (wr != null && wr.employeeWorkloads() != null) {
                    for (EmployeeWorkloadMetric m : wr.employeeWorkloads()) {
                        rows.add(new String[]{
                                m.employeeCode(),
                                m.employeeName(),
                                String.valueOf(m.workingDays()),
                                String.valueOf(m.offDays()),
                                String.valueOf(m.morningShifts()),
                                String.valueOf(m.generalShifts()),
                                String.valueOf(m.eveningShifts()),
                                String.valueOf(m.nightShifts()),
                                String.valueOf(m.weekendDuties()),
                                String.valueOf(m.holidayDuties()),
                                String.valueOf(m.maxConsecutiveWorkDays()),
                                String.valueOf(m.workloadScore()),
                                m.workloadRating()
                        });
                    }
                }
            }
            case "AUDIT_REPORT" -> {
                title = "WRMS System Audit Trail Report";
                rows.add(new String[]{"Log ID", "Action", "Entity Name", "Entity ID", "Employee", "Old Value", "New Value", "Reason", "Source", "Timestamp"});
                List<AuditLog> logs = auditLogRepository.findRecentLogs();
                if (logs.isEmpty()) {
                    logs = auditLogRepository.findAll();
                }
                for (AuditLog l : logs) {
                    rows.add(new String[]{
                            "#" + l.getId(),
                            l.getAction() != null ? l.getAction().name() : "-",
                            l.getEntityType() != null ? l.getEntityType() : "-",
                            l.getEntityId() != null ? String.valueOf(l.getEntityId()) : "-",
                            l.getEmployeeName() != null ? l.getEmployeeName() : "-",
                            l.getOldValue() != null ? l.getOldValue() : "-",
                            l.getNewValue() != null ? l.getNewValue() : "-",
                            l.getReason() != null ? l.getReason() : "-",
                            l.getSource() != null ? l.getSource() : "-",
                            l.getTimestamp() != null ? l.getTimestamp().format(TIME_FMT) : "-"
                    });
                }
            }
            case "SHIFT_CAPACITY" -> {
                title = "WRMS Operational Shift Definitions & Capacity Config";
                rows.add(new String[]{"Shift ID", "Shift Type", "Capacity", "Timing", "Overnight", "Status"});
                List<Shift> shifts = shiftRepository.findAll();
                for (Shift s : shifts) {
                    rows.add(new String[]{
                            "#" + s.getId(),
                            s.getShiftType() != null ? s.getShiftType().name() : "-",
                            String.valueOf(s.getCapacity()),
                            s.getTimingDisplay() != null ? s.getTimingDisplay() : "-",
                            s.isOvernight() ? "YES" : "NO",
                            s.isActive() ? "ACTIVE" : "INACTIVE"
                    });
                }
            }
            case "VALIDATION_REPORT" -> {
                title = "WRMS Roster Validation Audit Report";
                rows.add(new String[]{"Rule Code", "Rule Description", "Severity", "Employee", "Date", "Message", "Guidance"});
                Long cid = req.cycleId();
                if (cid == null) {
                    List<RosterCycle> cycles = cycleRepository.findAll();
                    if (!cycles.isEmpty()) {
                        cid = cycles.get(cycles.size() - 1).getId();
                    }
                }
                if (cid != null) {
                    RosterValidationResponse vr = rosterValidatorService.validateRoster(cid);
                    if (vr != null && vr.findings() != null) {
                        for (RosterValidationFinding f : vr.findings()) {
                            rows.add(new String[]{
                                    f.ruleCode(),
                                    f.ruleName(),
                                    f.severity() != null ? f.severity().name() : "-",
                                    f.employeeName() != null ? f.employeeName() + " (" + f.employeeCode() + ")" : "All Personnel",
                                    f.date() != null ? f.date().toString() : "Whole Cycle",
                                    f.message(),
                                    f.details()
                            });
                        }
                    }
                }
                if (rows.size() == 1) {
                    rows.add(new String[]{
                            "CLEAN",
                            "No Policy Violations",
                            "INFO",
                            "All Personnel",
                            start.toString() + " to " + end.toString(),
                            "The selected roster passed all compliance rules and operational constraints with 0 violations.",
                            "Compliant Roster"
                    });
                }
            }
            default -> {
                title = "WRMS " + reportType + " Export";
                rows.add(new String[]{"Item", "Value"});
            }
        }

        if (rows.size() <= 1) {
            throw new BusinessException("No data available for the selected criteria.");
        }

        try {
            if ("csv".equalsIgnoreCase(format)) {
                return buildCsv(title, rows);
            } else if ("pdf".equalsIgnoreCase(format)) {
                return com.weeklyroster.export.EnterprisePdfExporter.generatePdf(title, rows);
            } else if ("png".equalsIgnoreCase(format) || "jpg".equalsIgnoreCase(format) || "jpeg".equalsIgnoreCase(format)) {
                return com.weeklyroster.export.EnterpriseImageExporter.generateImage(title, rows, format);
            } else {
                return buildExcelXml(title, rows);
            }
        } catch (Exception e) {
            throw new BusinessException("Failed to generate " + format.toUpperCase() + " export: " + e.getMessage());
        }
    }

    private byte[] buildCsv(String title, List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append('\ufeff');
        sb.append("# ").append(title).append("\n");
        sb.append("# Generated on: ").append(LocalDateTime.now().format(TIME_FMT)).append("\n\n");

        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                String val = row[i] != null ? row[i] : "";
                if (val.contains(",") || val.contains("\"") || val.contains("\n")) {
                    sb.append("\"").append(val.replace("\"", "\"\"")).append("\"");
                } else {
                    sb.append(val);
                }
                if (i < row.length - 1) sb.append(",");
            }
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 2) + "..";
    }

    private byte[] buildExcelXml(String title, List<String[]> rows) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            addZip(zos, "[Content_Types].xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n"
                    + "  <Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n"
                    + "  <Default Extension=\"xml\" ContentType=\"application/xml\"/>\n"
                    + "  <Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>\n"
                    + "  <Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>\n"
                    + "  <Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>\n"
                    + "</Types>");

            addZip(zos, "_rels/.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n"
                    + "  <Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>\n"
                    + "</Relationships>");

            addZip(zos, "xl/_rels/workbook.xml.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n"
                    + "  <Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>\n"
                    + "  <Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>\n"
                    + "</Relationships>");

            addZip(zos, "xl/workbook.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">\n"
                    + "  <sheets>\n"
                    + "    <sheet name=\"Report\" sheetId=\"1\" r:id=\"rId1\"/>\n"
                    + "  </sheets>\n"
                    + "</workbook>");

            addZip(zos, "xl/styles.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n"
                    + "  <fonts count=\"2\">\n"
                    + "    <font><sz val=\"10\"/><name val=\"Arial\"/></font>\n"
                    + "    <font><b/><sz val=\"10\"/><name val=\"Arial\"/></font>\n"
                    + "  </fonts>\n"
                    + "  <fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>\n"
                    + "  <borders count=\"1\"><border><left/><right/><top/><bottom/></border></borders>\n"
                    + "  <cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>\n"
                    + "  <cellXfs count=\"2\">\n"
                    + "    <xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>\n"
                    + "    <xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>\n"
                    + "  </cellXfs>\n"
                    + "</styleSheet>");

            StringBuilder sheetXml = new StringBuilder();
            sheetXml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
            sheetXml.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n");
            sheetXml.append("  <sheetData>\n");

            int rowIdx = 1;
            for (String[] row : rows) {
                sheetXml.append("    <row r=\"").append(rowIdx).append("\">\n");
                for (int colIdx = 0; colIdx < row.length; colIdx++) {
                    String colLetter = getColLetter(colIdx + 1);
                    String cellRef = colLetter + rowIdx;
                    String val = escapeXml(row[colIdx] != null ? row[colIdx] : "");
                    int style = (rowIdx == 1) ? 1 : 0;
                    sheetXml.append("      <c r=\"").append(cellRef).append("\" t=\"inlineStr\" s=\"").append(style).append("\"><is><t>")
                            .append(val).append("</t></is></c>\n");
                }
                sheetXml.append("    </row>\n");
                rowIdx++;
            }

            sheetXml.append("  </sheetData>\n");
            sheetXml.append("</worksheet>");

            addZip(zos, "xl/worksheets/sheet1.xml", sheetXml.toString());
        }
        return baos.toByteArray();
    }

    private String getColLetter(int col) {
        StringBuilder sb = new StringBuilder();
        while (col > 0) {
            int rem = (col - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            col = (col - 1) / 26;
        }
        return sb.toString();
    }

    private void addZip(ZipOutputStream zos, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
