package com.weeklyroster.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class EnterprisePdfExporter {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static byte[] generatePdf(String title, List<String[]> rows) {
        if (rows == null) {
            rows = new ArrayList<>();
        }

        final float pageWidth = 792f;
        final float pageHeight = 612f;
        final float margin = 36f;
        final float printableWidth = pageWidth - (2 * margin);

        int colCount = 1;
        if (!rows.isEmpty() && rows.get(0) != null && rows.get(0).length > 0) {
            colCount = rows.get(0).length;
        }

        float colWidth = printableWidth / colCount;
        float rowHeight = 22f;
        float headerHeight = 26f;

        float availableTableHeight = pageHeight - (margin * 2) - 80f;
        int rowsPerPage = Math.max(1, (int) (availableTableHeight / rowHeight));

        List<List<String[]>> pagesData = new ArrayList<>();
        String[] headerRow = rows.isEmpty() ? new String[]{"Data"} : rows.get(0);

        if (rows.size() <= 1) {
            pagesData.add(new ArrayList<>());
        } else {
            List<String[]> currentPage = new ArrayList<>();
            for (int i = 1; i < rows.size(); i++) {
                currentPage.add(rows.get(i));
                if (currentPage.size() >= rowsPerPage) {
                    pagesData.add(currentPage);
                    currentPage = new ArrayList<>();
                }
            }
            if (!currentPage.isEmpty() || pagesData.isEmpty()) {
                pagesData.add(currentPage);
            }
        }

        int totalPages = pagesData.size();
        List<String> pageStreams = new ArrayList<>();
        String generatedAt = LocalDateTime.now().format(TIME_FMT);

        for (int p = 0; p < totalPages; p++) {
            List<String[]> pageRows = pagesData.get(p);
            StringBuilder sb = new StringBuilder();

            // 1. Draw Page Header Background Banner (Slate 900)
            sb.append("0.06 0.09 0.16 rg\n");
            sb.append(margin).append(" ").append(pageHeight - margin - 45f).append(" ")
              .append(printableWidth).append(" 45 re f\n");

            // Header Title Text
            sb.append("BT /F2 14 Tf 1 1 1 rg ")
              .append(margin + 12f).append(" ").append(pageHeight - margin - 22f).append(" Td (")
              .append(escapePdf(title)).append(") Tj ET\n");

            // Header Subtitle Text
            sb.append("BT /F1 9 Tf 0.58 0.64 0.72 rg ")
              .append(margin + 12f).append(" ").append(pageHeight - margin - 37f).append(" Td (")
              .append("Weekly Roster Management System (WRMS) Enterprise Report  |  Generated: ").append(escapePdf(generatedAt))
              .append("  |  Page ").append(p + 1).append(" of ").append(totalPages)
              .append(") Tj ET\n");

            // 2. Table Column Headers
            float currentY = pageHeight - margin - 45f - headerHeight;
            sb.append("0.12 0.16 0.24 rg\n");
            sb.append(margin).append(" ").append(currentY).append(" ")
              .append(printableWidth).append(" ").append(headerHeight).append(" re f\n");

            sb.append("0.8 0.84 0.88 RG 1 w\n");
            sb.append(margin).append(" ").append(currentY).append(" ")
              .append(printableWidth).append(" ").append(headerHeight).append(" re S\n");

            for (int c = 0; c < colCount; c++) {
                float cellX = margin + (c * colWidth);
                String headerText = c < headerRow.length ? headerRow[c] : "";
                sb.append("BT /F2 9 Tf 1 1 1 rg ")
                  .append(cellX + 6f).append(" ").append(currentY + 8f).append(" Td (")
                  .append(escapePdf(truncate(headerText, (int) (colWidth / 5.5f)))).append(") Tj ET\n");

                if (c > 0) {
                    sb.append("0.25 0.32 0.44 RG 0.5 w ")
                      .append(cellX).append(" ").append(currentY).append(" m ")
                      .append(cellX).append(" ").append(currentY + headerHeight).append(" l S\n");
                }
            }

            // 3. Table Rows
            for (int r = 0; r < pageRows.size(); r++) {
                String[] rowData = pageRows.get(r);
                currentY -= rowHeight;

                if (r % 2 == 1) {
                    sb.append("0.96 0.97 0.99 rg\n");
                    sb.append(margin).append(" ").append(currentY).append(" ")
                      .append(printableWidth).append(" ").append(rowHeight).append(" re f\n");
                } else {
                    sb.append("1 1 1 rg\n");
                    sb.append(margin).append(" ").append(currentY).append(" ")
                      .append(printableWidth).append(" ").append(rowHeight).append(" re f\n");
                }

                sb.append("0.89 0.91 0.94 RG 0.5 w ")
                  .append(margin).append(" ").append(currentY).append(" m ")
                  .append(margin + printableWidth).append(" ").append(currentY).append(" l S\n");

                for (int c = 0; c < colCount; c++) {
                    float cellX = margin + (c * colWidth);
                    String cellVal = (c < rowData.length && rowData[c] != null) ? rowData[c] : "";

                    if ("WORKING".equalsIgnoreCase(cellVal) || "APPROVED".equalsIgnoreCase(cellVal) || "ACTIVE".equalsIgnoreCase(cellVal) || "YES".equalsIgnoreCase(cellVal)) {
                        sb.append("BT /F2 8 Tf 0.08 0.5 0.24 rg ");
                    } else if ("ON_LEAVE".equalsIgnoreCase(cellVal) || "REJECTED".equalsIgnoreCase(cellVal) || "CRITICAL".equalsIgnoreCase(cellVal)) {
                        sb.append("BT /F2 8 Tf 0.75 0.15 0.15 rg ");
                    } else if ("WEEKLY_OFF".equalsIgnoreCase(cellVal) || "OFF".equalsIgnoreCase(cellVal) || "PENDING".equalsIgnoreCase(cellVal) || "LOCKED".equalsIgnoreCase(cellVal)) {
                        sb.append("BT /F2 8 Tf 0.7 0.45 0.05 rg ");
                    } else {
                        sb.append("BT /F1 8 Tf 0.15 0.2 0.28 rg ");
                    }

                    sb.append(cellX + 6f).append(" ").append(currentY + 6f).append(" Td (")
                      .append(escapePdf(truncate(cellVal, (int) (colWidth / 5.2f)))).append(") Tj ET\n");

                    if (c > 0) {
                        sb.append("0.89 0.91 0.94 RG 0.5 w ")
                          .append(cellX).append(" ").append(currentY).append(" m ")
                          .append(cellX).append(" ").append(currentY + rowHeight).append(" l S\n");
                    }
                }
            }

            // Table Outer Border
            float tableBottomY = currentY;
            float totalDrawnTableH = (pageHeight - margin - 45f - headerHeight) - tableBottomY;
            sb.append("0.8 0.84 0.88 RG 1 w ")
              .append(margin).append(" ").append(tableBottomY).append(" ")
              .append(printableWidth).append(" ").append(totalDrawnTableH).append(" re S\n");

            // Footer Text
            sb.append("BT /F1 8 Tf 0.58 0.64 0.72 rg ")
              .append(margin).append(" ").append(margin - 18f > 10f ? margin - 18f : 15f).append(" Td (")
              .append("Confidential & Proprietary  |  Generated by Weekly Roster Management System (WRMS Enterprise)")
              .append(") Tj ET\n");

            pageStreams.add(sb.toString());
        }

        return assemblePdfDocument(pageWidth, pageHeight, pageStreams);
    }

    private static byte[] assemblePdfDocument(float pageWidth, float pageHeight, List<String> pageStreams) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        List<Long> offsets = new ArrayList<>();
        offsets.add(0L);

        try {
            baos.write(new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '4', '\n', '%', (byte)0xE2, (byte)0xE3, (byte)0xCF, (byte)0xD3, '\n'});

            int totalPages = pageStreams.size();

            offsets.add((long) baos.size());
            writeString(baos, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

            offsets.add((long) baos.size());
            StringBuilder kids = new StringBuilder();
            for (int i = 0; i < totalPages; i++) {
                int pageObjId = 3 + (i * 2);
                kids.append(pageObjId).append(" 0 R ");
            }
            writeString(baos, "2 0 obj\n<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + totalPages + " >>\nendobj\n");

            for (int i = 0; i < totalPages; i++) {
                int pageObjId = 3 + (i * 2);
                int streamObjId = pageObjId + 1;
                byte[] streamBytes = pageStreams.get(i).getBytes(StandardCharsets.ISO_8859_1);

                offsets.add((long) baos.size());
                writeString(baos, pageObjId + " 0 obj\n"
                        + "<< /Type /Page /Parent 2 0 R\n"
                        + "   /MediaBox [0 0 " + pageWidth + " " + pageHeight + "]\n"
                        + "   /Resources << /Font << /F1 " + (3 + totalPages * 2) + " 0 R /F2 " + (4 + totalPages * 2) + " 0 R >> >>\n"
                        + "   /Contents " + streamObjId + " 0 R\n"
                        + ">>\nendobj\n");

                offsets.add((long) baos.size());
                writeString(baos, streamObjId + " 0 obj\n"
                        + "<< /Length " + streamBytes.length + " >>\n"
                        + "stream\n");
                baos.write(streamBytes);
                writeString(baos, "\nendstream\nendobj\n");
            }

            int fontF1Id = 3 + totalPages * 2;
            int fontF2Id = fontF1Id + 1;

            offsets.add((long) baos.size());
            writeString(baos, fontF1Id + " 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>\nendobj\n");

            offsets.add((long) baos.size());
            writeString(baos, fontF2Id + " 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>\nendobj\n");

            long startXref = baos.size();
            int totalObjects = fontF2Id;
            writeString(baos, "xref\n0 " + (totalObjects + 1) + "\n");
            writeString(baos, "0000000000 65535 f \n");
            for (int i = 1; i <= totalObjects; i++) {
                String offsetStr = String.format("%010d 00000 n \n", offsets.get(i));
                writeString(baos, offsetStr);
            }

            writeString(baos, "trailer\n<< /Size " + (totalObjects + 1) + " /Root 1 0 R >>\n");
            writeString(baos, "startxref\n" + startXref + "\n%%EOF\n");

        } catch (IOException e) {
            throw new RuntimeException("Failed to generate PDF document", e);
        }

        return baos.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream baos, String s) throws IOException {
        baos.write(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String escapePdf(String s) {
        if (s == null) return "";
        // Clean special characters to safe WinAnsi / ASCII
        String sanitized = s.replace("→", "->")
                .replace("←", "<-")
                .replace("•", "-")
                .replace("—", "-")
                .replace("–", "-")
                .replace("✓", "[OK]")
                .replace("⚠️", "[WARN]")
                .replace("🔴", "[ERR]")
                .replace("🟢", "[OK]")
                .replace("’", "'")
                .replace("“", "\"")
                .replace("”", "\"");

        // Replace non-ASCII printable characters with space
        StringBuilder sb = new StringBuilder();
        for (char c : sanitized.toCharArray()) {
            if (c >= 32 && c <= 126) {
                sb.append(c);
            } else if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(' ');
            }
        }

        return sb.toString().replace("\\", "\\\\")
                .replace("(", "\\(")
                .replace(")", "\\)");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        if (max <= 3) return s.substring(0, Math.max(1, max));
        return s.substring(0, max - 2) + "..";
    }
}
