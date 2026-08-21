package com.weeklyroster.export;

import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class RosterExcelExporter {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DISPLAY_DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    public static byte[] exportToExcel(RosterCycleResponse cycle, List<Shift> shifts) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // 1. [Content_Types].xml
            addZipEntry(zos, "[Content_Types].xml", buildContentTypesXml());

            // 2. _rels/.rels
            addZipEntry(zos, "_rels/.rels", buildRootRelsXml());

            // 3. xl/_rels/workbook.xml.rels
            addZipEntry(zos, "xl/_rels/workbook.xml.rels", buildWorkbookRelsXml());

            // 4. xl/workbook.xml
            addZipEntry(zos, "xl/workbook.xml", buildWorkbookXml());

            // 5. xl/styles.xml
            addZipEntry(zos, "xl/styles.xml", buildStylesXml());

            // 6. xl/worksheets/sheet1.xml
            addZipEntry(zos, "xl/worksheets/sheet1.xml", buildSheetXml(cycle, shifts));
        }
        return baos.toByteArray();
    }

    private static void addZipEntry(ZipOutputStream zos, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String buildContentTypesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n"
                + "  <Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n"
                + "  <Default Extension=\"xml\" ContentType=\"application/xml\"/>\n"
                + "  <Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>\n"
                + "  <Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>\n"
                + "  <Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>\n"
                + "</Types>";
    }

    private static String buildRootRelsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n"
                + "  <Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>\n"
                + "</Relationships>";
    }

    private static String buildWorkbookRelsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n"
                + "  <Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>\n"
                + "  <Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>\n"
                + "</Relationships>";
    }

    private static String buildWorkbookXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">\n"
                + "  <sheets>\n"
                + "    <sheet name=\"Weekly Roster\" sheetId=\"1\" r:id=\"rId1\"/>\n"
                + "  </sheets>\n"
                + "</workbook>";
    }

    private static String buildStylesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n"
                + "  <fonts count=\"4\">\n"
                + "    <font><sz val=\"10\"/><color rgb=\"FF1E293B\"/><name val=\"Calibri\"/></font>\n"
                + "    <font><b/><sz val=\"14\"/><color rgb=\"FF0F172A\"/><name val=\"Calibri\"/></font>\n"
                + "    <font><b/><sz val=\"10\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font>\n"
                + "    <font><b/><sz val=\"10\"/><color rgb=\"FF0F172A\"/><name val=\"Calibri\"/></font>\n"
                + "  </fonts>\n"
                + "  <fills count=\"5\">\n"
                + "    <fill><patternFill patternType=\"none\"/></fill>\n"
                + "    <fill><patternFill patternType=\"gray125\"/></fill>\n"
                + "    <fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1E3A8A\"/></patternFill></fill>\n"
                + "    <fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFF8FAFC\"/></patternFill></fill>\n"
                + "    <fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFF1F5F9\"/></patternFill></fill>\n"
                + "  </fills>\n"
                + "  <borders count=\"2\">\n"
                + "    <border><left/><right/><top/><bottom/></border>\n"
                + "    <border>\n"
                + "      <left style=\"thin\"><color rgb=\"FFCBD5E1\"/></left>\n"
                + "      <right style=\"thin\"><color rgb=\"FFCBD5E1\"/></right>\n"
                + "      <top style=\"thin\"><color rgb=\"FFCBD5E1\"/></top>\n"
                + "      <bottom style=\"thin\"><color rgb=\"FFCBD5E1\"/></bottom>\n"
                + "    </border>\n"
                + "  </borders>\n"
                + "  <cellStyleXfs count=\"1\">\n"
                + "    <xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>\n"
                + "  </cellStyleXfs>\n"
                + "  <cellXfs count=\"6\">\n"
                + "    <xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>\n" // 0: Normal
                + "    <xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>\n" // 1: Title
                + "    <xf numFmtId=\"0\" fontId=\"2\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>\n" // 2: Header (Navy)
                + "    <xf numFmtId=\"0\" fontId=\"0\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>\n" // 3: Data (White/Clean)
                + "    <xf numFmtId=\"0\" fontId=\"0\" fillId=\"4\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>\n" // 4: Data (Zebra Alt)
                + "    <xf numFmtId=\"0\" fontId=\"3\" fillId=\"4\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>\n" // 5: Date/Day center
                + "  </cellXfs>\n"
                + "</styleSheet>";
    }

    private static String buildSheetXml(RosterCycleResponse cycle, List<Shift> shifts) {
        // Find shift timings
        Map<ShiftType, String> shiftTimingMap = new LinkedHashMap<>();
        for (ShiftType type : List.of(ShiftType.MORNING, ShiftType.GENERAL, ShiftType.EVENING, ShiftType.NIGHT)) {
            shiftTimingMap.put(type, getShiftTimingHeader(shifts, type));
        }

        // Group assignments by date -> ShiftType/Off/Leave -> list of employee names
        Map<LocalDate, Map<String, List<String>>> dayMap = new LinkedHashMap<>();
        LocalDate start = cycle.startDate();
        LocalDate end = cycle.endDate();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            Map<String, List<String>> m = new LinkedHashMap<>();
            m.put("MORNING", new ArrayList<>());
            m.put("GENERAL", new ArrayList<>());
            m.put("EVENING", new ArrayList<>());
            m.put("NIGHT", new ArrayList<>());
            m.put("OFF", new ArrayList<>());
            m.put("LEAVE", new ArrayList<>());
            dayMap.put(d, m);
        }

        if (cycle.assignments() != null) {
            for (RosterAssignmentResponse a : cycle.assignments()) {
                Map<String, List<String>> m = dayMap.get(a.rosterDate());
                if (m != null) {
                    String empName = a.employeeName();
                    if (a.onLeave()) {
                        m.get("LEAVE").add(empName);
                    } else if (a.weeklyOff()) {
                        m.get("OFF").add(empName);
                    } else if (a.shiftType() != null) {
                        String key = a.shiftType().name();
                        if (m.containsKey(key)) {
                            m.get(key).add(empName);
                        } else {
                            m.get("OFF").add(empName);
                        }
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n");
        sb.append("  <sheetViews>\n");
        sb.append("    <sheetView tabSelected=\"1\" workbookViewId=\"0\">\n");
        sb.append("      <pane ySplit=\"4\" topLeftCell=\"A5\" activePane=\"bottomLeft\" state=\"frozen\"/>\n");
        sb.append("    </sheetView>\n");
        sb.append("  </sheetViews>\n");

        // Column widths
        sb.append("  <cols>\n");
        sb.append("    <col min=\"1\" max=\"1\" width=\"15\" customWidth=\"1\"/>\n"); // Date
        sb.append("    <col min=\"2\" max=\"2\" width=\"14\" customWidth=\"1\"/>\n"); // Day
        sb.append("    <col min=\"3\" max=\"3\" width=\"26\" customWidth=\"1\"/>\n"); // Morning
        sb.append("    <col min=\"4\" max=\"4\" width=\"26\" customWidth=\"1\"/>\n"); // General
        sb.append("    <col min=\"5\" max=\"5\" width=\"26\" customWidth=\"1\"/>\n"); // Evening
        sb.append("    <col min=\"6\" max=\"6\" width=\"26\" customWidth=\"1\"/>\n"); // Night
        sb.append("    <col min=\"7\" max=\"7\" width=\"22\" customWidth=\"1\"/>\n"); // Off
        sb.append("    <col min=\"8\" max=\"8\" width=\"22\" customWidth=\"1\"/>\n"); // Leave
        sb.append("  </cols>\n");

        sb.append("  <sheetData>\n");

        // Row 1: Title
        sb.append("    <row r=\"1\" ht=\"28\" customHeight=\"1\">\n");
        sb.append("      <c r=\"A1\" s=\"1\" t=\"inlineStr\"><is><t>WRMS — Weekly Roster Schedule</t></is></c>\n");
        sb.append("    </row>\n");

        // Row 2: Subtitle / Cycle info
        String subtitle = "Cycle: " + start.format(DISPLAY_DATE_FMT) + " to " + end.format(DISPLAY_DATE_FMT)
                + " | Generated: " + (cycle.generatedAt() != null ? cycle.generatedAt().toString() : "N/A")
                + " | Mode: " + (cycle.generationMode() != null ? cycle.generationMode().name() : "MANUAL");
        sb.append("    <row r=\"2\" ht=\"20\" customHeight=\"1\">\n");
        sb.append("      <c r=\"A2\" s=\"0\" t=\"inlineStr\"><is><t>").append(escapeXml(subtitle)).append("</t></is></c>\n");
        sb.append("    </row>\n");

        // Row 3: Empty spacing
        sb.append("    <row r=\"3\" ht=\"10\" customHeight=\"1\"/>\n");

        // Row 4: Table Headers (Navy style)
        sb.append("    <row r=\"4\" ht=\"32\" customHeight=\"1\">\n");
        sb.append("      <c r=\"A4\" s=\"2\" t=\"inlineStr\"><is><t>Date</t></is></c>\n");
        sb.append("      <c r=\"B4\" s=\"2\" t=\"inlineStr\"><is><t>Day</t></is></c>\n");
        sb.append("      <c r=\"C4\" s=\"2\" t=\"inlineStr\"><is><t>").append(escapeXml(shiftTimingMap.get(ShiftType.MORNING))).append("</t></is></c>\n");
        sb.append("      <c r=\"D4\" s=\"2\" t=\"inlineStr\"><is><t>").append(escapeXml(shiftTimingMap.get(ShiftType.GENERAL))).append("</t></is></c>\n");
        sb.append("      <c r=\"E4\" s=\"2\" t=\"inlineStr\"><is><t>").append(escapeXml(shiftTimingMap.get(ShiftType.EVENING))).append("</t></is></c>\n");
        sb.append("      <c r=\"F4\" s=\"2\" t=\"inlineStr\"><is><t>").append(escapeXml(shiftTimingMap.get(ShiftType.NIGHT))).append("</t></is></c>\n");
        sb.append("      <c r=\"G4\" s=\"2\" t=\"inlineStr\"><is><t>Off</t></is></c>\n");
        sb.append("      <c r=\"H4\" s=\"2\" t=\"inlineStr\"><is><t>Leave</t></is></c>\n");
        sb.append("    </row>\n");

        // Rows 5..11: 7-day data rows
        int rowIdx = 5;
        for (Map.Entry<LocalDate, Map<String, List<String>>> entry : dayMap.entrySet()) {
            LocalDate date = entry.getKey();
            Map<String, List<String>> m = entry.getValue();
            String dayName = date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);

            String morningText = String.join("\n", m.get("MORNING"));
            String generalText = String.join("\n", m.get("GENERAL"));
            String eveningText = String.join("\n", m.get("EVENING"));
            String nightText = String.join("\n", m.get("NIGHT"));
            String offText = String.join("\n", m.get("OFF"));
            String leaveText = String.join("\n", m.get("LEAVE"));

            int maxStaffCount = Math.max(1, Math.max(m.get("MORNING").size(), Math.max(m.get("GENERAL").size(), Math.max(m.get("EVENING").size(), Math.max(m.get("OFF").size(), m.get("LEAVE").size())))));
            int rowHeight = Math.max(28, maxStaffCount * 18 + 10);
            int dataStyle = (rowIdx % 2 == 0) ? 4 : 3;

            sb.append("    <row r=\"").append(rowIdx).append("\" ht=\"").append(rowHeight).append("\" customHeight=\"1\">\n");
            sb.append("      <c r=\"A").append(rowIdx).append("\" s=\"5\" t=\"inlineStr\"><is><t>").append(date.format(DATE_FMT)).append("</t></is></c>\n");
            sb.append("      <c r=\"B").append(rowIdx).append("\" s=\"5\" t=\"inlineStr\"><is><t>").append(dayName).append("</t></is></c>\n");
            sb.append("      <c r=\"C").append(rowIdx).append("\" s=\"").append(dataStyle).append("\" t=\"inlineStr\"><is><t>").append(escapeXml(morningText)).append("</t></is></c>\n");
            sb.append("      <c r=\"D").append(rowIdx).append("\" s=\"").append(dataStyle).append("\" t=\"inlineStr\"><is><t>").append(escapeXml(generalText)).append("</t></is></c>\n");
            sb.append("      <c r=\"E").append(rowIdx).append("\" s=\"").append(dataStyle).append("\" t=\"inlineStr\"><is><t>").append(escapeXml(eveningText)).append("</t></is></c>\n");
            sb.append("      <c r=\"F").append(rowIdx).append("\" s=\"").append(dataStyle).append("\" t=\"inlineStr\"><is><t>").append(escapeXml(nightText)).append("</t></is></c>\n");
            sb.append("      <c r=\"G").append(rowIdx).append("\" s=\"").append(dataStyle).append("\" t=\"inlineStr\"><is><t>").append(escapeXml(offText)).append("</t></is></c>\n");
            sb.append("      <c r=\"H").append(rowIdx).append("\" s=\"").append(dataStyle).append("\" t=\"inlineStr\"><is><t>").append(escapeXml(leaveText)).append("</t></is></c>\n");
            sb.append("    </row>\n");
            rowIdx++;
        }

        sb.append("  </sheetData>\n");
        sb.append("  <pageSetup orientation=\"landscape\" paperSize=\"9\" fitToPage=\"1\" fitToWidth=\"1\" fitToHeight=\"1\"/>\n");
        sb.append("</worksheet>");

        return sb.toString();
    }

    private static String getShiftTimingHeader(List<Shift> shifts, ShiftType type) {
        if (shifts != null) {
            for (Shift s : shifts) {
                if (s.getShiftType() == type && s.isActive()) {
                    if (s.getStartTime() != null && s.getEndTime() != null) {
                        String suffix = s.isOvernight() ? " (next day)" : "";
                        return capitalize(type.name()) + " (" + s.getStartTime() + "–" + s.getEndTime() + suffix + ")";
                    }
                }
            }
        }
        // Fallback default
        return switch (type) {
            case MORNING -> "Morning (07:00–15:00)";
            case GENERAL -> "General (09:30–18:00)";
            case EVENING -> "Evening (14:00–22:00)";
            case NIGHT -> "Night (22:00–07:00 next day)";
            default -> capitalize(type.name());
        };
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return "";
        return str.substring(0, 1).toUpperCase(Locale.ENGLISH) + str.substring(1).toLowerCase(Locale.ENGLISH);
    }

    private static String escapeXml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
