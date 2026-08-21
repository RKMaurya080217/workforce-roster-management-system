package com.weeklyroster.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

public final class DateParser {

    private static final List<DateTimeFormatter> FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,                    // yyyy-MM-dd
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),           // 17-08-2026
            DateTimeFormatter.ofPattern("d-M-yyyy"),             // 17-8-2026
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),           // 17/08/2026
            DateTimeFormatter.ofPattern("d/M/yyyy"),             // 17/8/2026
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),           // 2026/08/17
            DateTimeFormatter.ofPattern("yyyy/M/d"),             // 2026/8/17
            DateTimeFormatter.ofPattern("yyyy-M-d")              // 2026-8-17
    );

    private DateParser() {
    }

    /**
     * Parses a date string supporting multiple standard formats (yyyy-MM-dd, dd-MM-yyyy, dd/MM/yyyy, etc.).
     * Returns fallback if raw input is null, blank, or "undefined".
     */
    public static LocalDate parse(String rawDate, LocalDate fallback) {
        if (rawDate == null || rawDate.trim().isEmpty() || "undefined".equalsIgnoreCase(rawDate.trim()) || "null".equalsIgnoreCase(rawDate.trim())) {
            return fallback;
        }

        String cleaned = rawDate.trim();
        // If ISO timestamp with time component (e.g. 2026-08-17T00:00:00), strip time
        if (cleaned.contains("T")) {
            cleaned = cleaned.substring(0, cleaned.indexOf("T"));
        }

        for (DateTimeFormatter formatter : FORMATTERS) {
            try {
                return LocalDate.parse(cleaned, formatter);
            } catch (DateTimeParseException ignored) {
                // Try next pattern
            }
        }

        throw new IllegalArgumentException("Invalid date format: '" + rawDate + "'. Supported formats: yyyy-MM-dd, dd-MM-yyyy, dd/MM/yyyy");
    }

    public static LocalDate parse(String rawDate) {
        return parse(rawDate, null);
    }
}
