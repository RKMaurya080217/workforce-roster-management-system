package com.weeklyroster.export;

import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

public class RosterImageExporter {

    private static final DateTimeFormatter DISPLAY_DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter SHORT_DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM");

    public static byte[] exportToImage(RosterCycleResponse cycle, List<Shift> shifts) throws IOException {
        int width = 1600;
        int headerHeight = 140;
        int tableHeaderHeight = 60;
        int footerHeight = 60;

        // Group assignments
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

        // Calculate dynamic row heights based on max employees in any cell on that day
        Map<LocalDate, Integer> rowHeightMap = new LinkedHashMap<>();
        int totalTableHeight = 0;
        for (Map.Entry<LocalDate, Map<String, List<String>>> entry : dayMap.entrySet()) {
            Map<String, List<String>> m = entry.getValue();
            int maxItems = 1;
            for (List<String> list : m.values()) {
                if (list.size() > maxItems) {
                    maxItems = list.size();
                }
            }
            int rowH = Math.max(68, maxItems * 26 + 18);
            rowHeightMap.put(entry.getKey(), rowH);
            totalTableHeight += rowH;
        }

        int height = headerHeight + tableHeaderHeight + totalTableHeight + footerHeight + 40;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Background
        g.setColor(new Color(248, 250, 252));
        g.fillRect(0, 0, width, height);

        // Header Card (Navy Gradient / Solid)
        g.setColor(new Color(15, 23, 42)); // Slate 900
        g.fillRoundRect(20, 20, width - 40, headerHeight - 30, 16, 16);

        // Header Title
        g.setFont(new Font("Segoe UI", Font.BOLD, 26));
        g.setColor(Color.WHITE);
        g.drawString("WRMS — Weekly Roster Schedule", 45, 62);

        // Cycle Subtitle
        g.setFont(new Font("Segoe UI", Font.PLAIN, 15));
        g.setColor(new Color(148, 163, 184));
        String sub = "Cycle: " + start.format(DISPLAY_DATE_FMT) + " – " + end.format(DISPLAY_DATE_FMT)
                + "  |  Generated: " + (cycle.generatedAt() != null ? cycle.generatedAt().toString().replace("T", " ") : "N/A");
        g.drawString(sub, 45, 95);

        // Mode Badge
        String modeStr = cycle.generationMode() != null ? cycle.generationMode().name() : "MANUAL";
        g.setColor(new Color(30, 58, 138));
        g.fillRoundRect(width - 200, 42, 140, 32, 8, 8);
        g.setFont(new Font("Segoe UI", Font.BOLD, 13));
        g.setColor(new Color(191, 219, 254));
        g.drawString("MODE: " + modeStr, width - 185, 63);

        // Table Coordinates & Column Widths
        int tableX = 20;
        int tableY = headerHeight + 10;
        int tableW = width - 40;

        int colDateW = 140;
        int colDayW = 130;
        int remainingW = tableW - colDateW - colDayW;
        int colShiftW = remainingW / 6; // Morning, General, Evening, Night, Off, Leave

        int[] colWidths = {colDateW, colDayW, colShiftW, colShiftW, colShiftW, colShiftW, colShiftW, tableW - (colDateW + colDayW + 5 * colShiftW)};
        String[] headers = {
                "Date",
                "Day",
                getShiftTimingHeader(shifts, ShiftType.MORNING),
                getShiftTimingHeader(shifts, ShiftType.GENERAL),
                getShiftTimingHeader(shifts, ShiftType.EVENING),
                getShiftTimingHeader(shifts, ShiftType.NIGHT),
                "Off",
                "Leave"
        };
        Color[] headerColors = {
                new Color(51, 65, 85),  // Date
                new Color(51, 65, 85),  // Day
                new Color(37, 99, 235), // Morning (Blue)
                new Color(13, 148, 136),// General (Teal)
                new Color(217, 119, 6), // Evening (Amber)
                new Color(79, 70, 229), // Night (Indigo)
                new Color(100, 116, 139),// Off (Slate)
                new Color(225, 29, 72)  // Leave (Rose)
        };

        // Draw Table Header
        int currX = tableX;
        for (int i = 0; i < headers.length; i++) {
            g.setColor(headerColors[i]);
            g.fillRect(currX, tableY, colWidths[i], tableHeaderHeight);

            g.setColor(new Color(255, 255, 255, 80));
            g.drawRect(currX, tableY, colWidths[i], tableHeaderHeight);

            // Draw header text
            g.setColor(Color.WHITE);
            g.setFont(new Font("Segoe UI", Font.BOLD, 13));
            String hText = headers[i];
            drawWrappedHeader(g, hText, currX + 10, tableY + 22, colWidths[i] - 20);

            currX += colWidths[i];
        }

        // Draw Table Rows
        int currY = tableY + tableHeaderHeight;
        int rowCount = 0;

        for (Map.Entry<LocalDate, Map<String, List<String>>> entry : dayMap.entrySet()) {
            LocalDate date = entry.getKey();
            Map<String, List<String>> m = entry.getValue();
            int rHeight = rowHeightMap.get(date);
            boolean isAlt = (rowCount % 2 == 1);

            // Row background
            g.setColor(isAlt ? new Color(241, 245, 249) : Color.WHITE);
            g.fillRect(tableX, currY, tableW, rHeight);

            // Cell borders
            g.setColor(new Color(226, 232, 240));
            g.setStroke(new BasicStroke(1));

            int cellX = tableX;
            for (int i = 0; i < colWidths.length; i++) {
                g.drawRect(cellX, currY, colWidths[i], rHeight);
                cellX += colWidths[i];
            }

            // Date cell
            g.setFont(new Font("Segoe UI", Font.BOLD, 13));
            g.setColor(new Color(15, 23, 42));
            g.drawString(date.format(SHORT_DATE_FMT), tableX + 15, currY + rHeight / 2 - 2);
            g.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            g.setColor(new Color(100, 116, 139));
            g.drawString(date.format(DateTimeFormatter.ofPattern("yyyy")), tableX + 15, currY + rHeight / 2 + 14);

            // Day cell
            String dayName = date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            g.setFont(new Font("Segoe UI", Font.BOLD, 13));
            g.setColor(new Color(30, 41, 59));
            g.drawString(dayName, tableX + colWidths[0] + 15, currY + rHeight / 2 + 4);

            // Shift cells
            int colOffset = colWidths[0] + colWidths[1];
            drawStaffChips(g, m.get("MORNING"), tableX + colOffset, currY, colWidths[2], rHeight, new Color(239, 246, 255), new Color(30, 64, 175));
            colOffset += colWidths[2];

            drawStaffChips(g, m.get("GENERAL"), tableX + colOffset, currY, colWidths[3], rHeight, new Color(240, 253, 250), new Color(17, 94, 89));
            colOffset += colWidths[3];

            drawStaffChips(g, m.get("EVENING"), tableX + colOffset, currY, colWidths[4], rHeight, new Color(254, 243, 199), new Color(146, 64, 14));
            colOffset += colWidths[4];

            drawStaffChips(g, m.get("NIGHT"), tableX + colOffset, currY, colWidths[5], rHeight, new Color(238, 242, 255), new Color(55, 48, 163));
            colOffset += colWidths[5];

            drawStaffChips(g, m.get("OFF"), tableX + colOffset, currY, colWidths[6], rHeight, new Color(241, 245, 249), new Color(71, 85, 105));
            colOffset += colWidths[6];

            drawStaffChips(g, m.get("LEAVE"), tableX + colOffset, currY, colWidths[7], rHeight, new Color(255, 228, 230), new Color(159, 18, 57));

            currY += rHeight;
            rowCount++;
        }

        // Table Outer Border
        g.setColor(new Color(203, 213, 225));
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(tableX, tableY, tableW, tableHeaderHeight + totalTableHeight, 4, 4);

        // Footer Summary
        int footerY = currY + 28;
        g.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        g.setColor(new Color(100, 116, 139));
        g.drawString("Official Weekly Roster Management System (WRMS) Schedule. Generated strictly adhering to 12h rest & night safety rules.", 40, footerY);

        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    private static void drawWrappedHeader(Graphics2D g, String text, int x, int y, int maxW) {
        if (text.contains(" (")) {
            String[] parts = text.split(" \\(", 2);
            g.drawString(parts[0], x, y);
            g.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            g.setColor(new Color(241, 245, 249));
            g.drawString("(" + parts[1], x, y + 16);
        } else {
            g.drawString(text, x, y + 8);
        }
    }

    private static void drawStaffChips(Graphics2D g, List<String> staffList, int x, int y, int w, int h, Color bg, Color textCol) {
        if (staffList == null || staffList.isEmpty()) {
            g.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            g.setColor(new Color(148, 163, 184));
            g.drawString("—", x + w / 2 - 4, y + h / 2 + 4);
            return;
        }

        int startY = y + 8;
        int chipH = 22;
        int chipSpacing = 4;

        for (String name : staffList) {
            g.setColor(bg);
            g.fillRoundRect(x + 6, startY, w - 12, chipH, 6, 6);

            g.setColor(new Color(textCol.getRed(), textCol.getGreen(), textCol.getBlue(), 60));
            g.drawRoundRect(x + 6, startY, w - 12, chipH, 6, 6);

            g.setFont(new Font("Segoe UI", Font.BOLD, 11));
            g.setColor(textCol);
            g.drawString(name, x + 12, startY + 15);

            startY += chipH + chipSpacing;
        }
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
}
