package com.weeklyroster.export;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.imageio.ImageIO;

public class EnterpriseImageExporter {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static byte[] generateImage(String title, List<String[]> rows, String format) throws IOException {
        if (rows == null || rows.isEmpty()) {
            rows = List.of(new String[]{"Report"}, new String[]{"No data available"});
        }

        String[] headerRow = rows.get(0);
        int colCount = headerRow.length;

        int rowHeight = 36;
        int headerBannerH = 110;
        int colHeaderH = 44;
        int footerH = 50;
        int totalRows = rows.size() - 1;

        int tableW = Math.max(1200, colCount * 180);
        int totalW = tableW + 80;
        int totalH = headerBannerH + colHeaderH + (totalRows * rowHeight) + footerH + 40;

        // Ensure reasonable bounds
        totalH = Math.max(300, totalH);

        BufferedImage img = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Canvas Background
        g.setColor(new Color(248, 250, 252)); // Slate 50
        g.fillRect(0, 0, totalW, totalH);

        int marginX = 40;
        int currentY = 30;

        // 1. Header Card
        g.setColor(new Color(15, 23, 42)); // Slate 900
        g.fillRoundRect(marginX, currentY, tableW, 90, 16, 16);

        // Header Title
        g.setFont(new Font("Segoe UI", Font.BOLD, 22));
        g.setColor(Color.WHITE);
        g.drawString(title, marginX + 24, currentY + 40);

        // Subtitle
        g.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        g.setColor(new Color(148, 163, 184));
        g.drawString("Weekly Roster Management System (WRMS) Enterprise Export  |  Generated: " + LocalDateTime.now().format(TIME_FMT) + "  |  Rows: " + totalRows, marginX + 24, currentY + 68);

        currentY += 105;

        // 2. Column Widths
        int colW = tableW / colCount;

        // Column Header Background
        g.setColor(new Color(30, 41, 59)); // Slate 800
        g.fillRoundRect(marginX, currentY, tableW, colHeaderH, 8, 8);

        g.setFont(new Font("Segoe UI", Font.BOLD, 13));
        g.setColor(Color.WHITE);
        for (int c = 0; c < colCount; c++) {
            int cellX = marginX + (c * colW);
            String hText = c < headerRow.length ? headerRow[c] : "";
            g.drawString(truncate(hText, 22), cellX + 12, currentY + 27);
        }

        currentY += colHeaderH;

        // 3. Table Rows
        g.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        for (int r = 1; r < rows.size(); r++) {
            String[] rowData = rows.get(r);

            // Alternate Row Colors
            if (r % 2 == 1) {
                g.setColor(Color.WHITE);
            } else {
                g.setColor(new Color(241, 245, 249)); // Slate 100
            }
            g.fillRect(marginX, currentY, tableW, rowHeight);

            // Row Bottom Border
            g.setColor(new Color(226, 232, 240));
            g.drawLine(marginX, currentY + rowHeight, marginX + tableW, currentY + rowHeight);

            // Cell text
            for (int c = 0; c < colCount; c++) {
                int cellX = marginX + (c * colW);
                String val = (c < rowData.length && rowData[c] != null) ? rowData[c] : "";

                // Highlighting
                if ("WORKING".equalsIgnoreCase(val) || "APPROVED".equalsIgnoreCase(val) || "ACTIVE".equalsIgnoreCase(val) || "YES".equalsIgnoreCase(val)) {
                    g.setColor(new Color(22, 101, 52)); // Green
                    g.setFont(new Font("Segoe UI", Font.BOLD, 13));
                } else if ("ON_LEAVE".equalsIgnoreCase(val) || "REJECTED".equalsIgnoreCase(val) || "CRITICAL".equalsIgnoreCase(val)) {
                    g.setColor(new Color(185, 28, 28)); // Red
                    g.setFont(new Font("Segoe UI", Font.BOLD, 13));
                } else if ("WEEKLY_OFF".equalsIgnoreCase(val) || "OFF".equalsIgnoreCase(val) || "PENDING".equalsIgnoreCase(val) || "LOCKED".equalsIgnoreCase(val)) {
                    g.setColor(new Color(180, 83, 9)); // Amber
                    g.setFont(new Font("Segoe UI", Font.BOLD, 13));
                } else {
                    g.setColor(new Color(51, 65, 85)); // Normal text
                    g.setFont(new Font("Segoe UI", Font.PLAIN, 13));
                }

                g.drawString(truncate(val, 24), cellX + 12, currentY + 23);

                // Column dividers
                if (c > 0) {
                    g.setColor(new Color(226, 232, 240));
                    g.drawLine(cellX, currentY, cellX, currentY + rowHeight);
                }
            }
            currentY += rowHeight;
        }

        // Table Outer Border
        g.setColor(new Color(203, 213, 225));
        g.drawRect(marginX, headerBannerH + 25, tableW, colHeaderH + (totalRows * rowHeight));

        // 4. Footer
        g.setColor(new Color(148, 163, 184));
        g.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        g.drawString("Confidential & Proprietary  |  Weekly Roster Management System (WRMS)", marginX, currentY + 30);

        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        String imgFormat = "png";
        if ("jpg".equalsIgnoreCase(format) || "jpeg".equalsIgnoreCase(format)) {
            imgFormat = "jpeg";
        }
        ImageIO.write(img, imgFormat, baos);
        return baos.toByteArray();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 2) + "..";
    }
}
