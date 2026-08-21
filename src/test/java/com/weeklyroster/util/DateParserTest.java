package com.weeklyroster.util;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DateParserTest {

    @Test
    @DisplayName("Parse ISO format yyyy-MM-dd")
    void testParse_IsoFormat() {
        LocalDate parsed = DateParser.parse("2026-08-17");
        assertEquals(LocalDate.of(2026, 8, 17), parsed);
    }

    @Test
    @DisplayName("Parse European/Indian format dd-MM-yyyy")
    void testParse_DdMmYyyy() {
        LocalDate parsed = DateParser.parse("17-08-2026");
        assertEquals(LocalDate.of(2026, 8, 17), parsed);
    }

    @Test
    @DisplayName("Parse slash format dd/MM/yyyy")
    void testParse_SlashFormat() {
        LocalDate parsed = DateParser.parse("17/08/2026");
        assertEquals(LocalDate.of(2026, 8, 17), parsed);
    }

    @Test
    @DisplayName("Parse single-digit format d-M-yyyy")
    void testParse_SingleDigit() {
        LocalDate parsed = DateParser.parse("7-8-2026");
        assertEquals(LocalDate.of(2026, 8, 7), parsed);
    }

    @Test
    @DisplayName("Parse ISO timestamp with time component")
    void testParse_Timestamp() {
        LocalDate parsed = DateParser.parse("2026-08-17T10:30:00.000Z");
        assertEquals(LocalDate.of(2026, 8, 17), parsed);
    }

    @Test
    @DisplayName("Null or empty date returns fallback")
    void testParse_NullOrBlank_ReturnsFallback() {
        LocalDate fallback = LocalDate.of(2026, 8, 18);
        assertEquals(fallback, DateParser.parse(null, fallback));
        assertEquals(fallback, DateParser.parse("", fallback));
        assertEquals(fallback, DateParser.parse("  ", fallback));
        assertEquals(fallback, DateParser.parse("undefined", fallback));
        assertEquals(fallback, DateParser.parse("null", fallback));
    }

    @Test
    @DisplayName("Invalid date string throws IllegalArgumentException")
    void testParse_InvalidString_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> DateParser.parse("invalid-date-string"));
    }
}
