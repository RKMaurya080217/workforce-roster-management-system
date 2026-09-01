package com.weeklyroster.export;

import static org.junit.jupiter.api.Assertions.*;

import com.weeklyroster.dto.response.CoverageReportResponse;
import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.GenerationMode;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public class Batch45ImageExportAwtHeadlessTest {

    @Test
    @DisplayName("Test 1: Java AWT operates in Headless Mode on Server")
    void testAwtHeadlessModeEnabled() {
        String headless = System.getProperty("java.awt.headless");
        assertTrue("true".equalsIgnoreCase(headless), "java.awt.headless must be set to true");
        assertTrue(GraphicsEnvironment.isHeadless(), "GraphicsEnvironment.isHeadless() must return true");
    }

    @Test
    @DisplayName("Test 2: Safe Font Handling Never Fails Even with Unknown Fonts")
    void testSafeFontResolution() {
        Font safeFont = RosterImageExporter.getSafeFont("NonExistentFontFamilyName_12345", Font.BOLD, 14);
        assertNotNull(safeFont, "Safe font must never be null");
        assertEquals(14, safeFont.getSize());
        assertEquals(Font.BOLD, safeFont.getStyle());

        Font segoeOrFallback = RosterImageExporter.getSafeFont("Segoe UI", Font.PLAIN, 12);
        assertNotNull(segoeOrFallback);
        assertEquals(12, segoeOrFallback.getSize());
    }

    @Test
    @DisplayName("Test 3: EnterpriseImageExporter generates valid PNG and JPEG in Headless Mode")
    void testEnterpriseImageExporter() throws Exception {
        List<String[]> rows = List.of(
                new String[]{"ID", "Employee Code", "Full Name", "Department", "Designation", "Status"},
                new String[]{"1", "EMP001", "Rajat Maurya", "Operations", "Roster Lead", "ACTIVE"},
                new String[]{"2", "EMP002", "Priya Sharma", "IT Support", "Engineer", "WORKING"},
                new String[]{"3", "EMP003", "Aarav Patel", "Logistics", "Dispatcher", "ON_LEAVE"}
        );

        byte[] pngBytes = EnterpriseImageExporter.generateImage("Employee Master Directory Report", rows, "png");
        assertNotNull(pngBytes);
        assertTrue(pngBytes.length > 500, "PNG image bytes must be substantial");

        BufferedImage pngImg = ImageIO.read(new ByteArrayInputStream(pngBytes));
        assertNotNull(pngImg, "ImageIO must read the generated PNG image");
        assertTrue(pngImg.getWidth() >= 1000);
        assertTrue(pngImg.getHeight() >= 200);

        byte[] jpegBytes = EnterpriseImageExporter.generateImage("Employee Master Directory Report", rows, "jpg");
        assertNotNull(jpegBytes);
        assertTrue(jpegBytes.length > 500, "JPEG image bytes must be substantial");
    }

    @Test
    @DisplayName("Test 4: RosterImageExporter generates valid PNG roster chart in Headless Mode")
    void testRosterImageExporter() throws Exception {
        LocalDate startDate = LocalDate.of(2026, 9, 7);
        LocalDate endDate = startDate.plusDays(6);

        List<Shift> shifts = new ArrayList<>();
        shifts.add(createShift(1L, ShiftType.MORNING, LocalTime.of(7, 0), LocalTime.of(15, 0), 2, false));
        shifts.add(createShift(2L, ShiftType.GENERAL, LocalTime.of(9, 30), LocalTime.of(18, 0), 2, false));
        shifts.add(createShift(3L, ShiftType.EVENING, LocalTime.of(14, 0), LocalTime.of(22, 0), 2, false));
        shifts.add(createShift(4L, ShiftType.NIGHT, LocalTime.of(22, 0), LocalTime.of(7, 0), 1, true));

        List<RosterAssignmentResponse> assignments = new ArrayList<>();
        assignments.add(new RosterAssignmentResponse(1L, 1L, startDate, 1L, "EMP001", "Rajat Maurya", Gender.MALE, ShiftType.MORNING, false, false, false));
        assignments.add(new RosterAssignmentResponse(2L, 1L, startDate, 2L, "EMP002", "Priya Sharma", Gender.FEMALE, ShiftType.GENERAL, false, false, false));
        assignments.add(new RosterAssignmentResponse(3L, 1L, startDate, 3L, "EMP003", "Aarav Patel", Gender.MALE, ShiftType.EVENING, false, false, false));
        assignments.add(new RosterAssignmentResponse(4L, 1L, startDate, 4L, "EMP004", "Sneha Rao", Gender.FEMALE, ShiftType.OFF, true, false, false));
        assignments.add(new RosterAssignmentResponse(5L, 1L, startDate, 5L, "EMP005", "Vikram Singh", Gender.MALE, ShiftType.NIGHT, false, false, false));

        RosterCycleResponse cycle = new RosterCycleResponse(
                101L,
                startDate,
                endDate,
                LocalDateTime.now(),
                GenerationMode.AUTOMATIC,
                "TENTATIVE",
                assignments,
                new CoverageReportResponse(35, 30, 30, 30, 0, 5, List.of(), List.of())
        );

        byte[] imgBytes = RosterImageExporter.exportToImage(cycle, shifts);

        assertNotNull(imgBytes);
        assertTrue(imgBytes.length > 1000, "PNG image bytes must be substantial");

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(imgBytes));
        assertNotNull(img, "Rendered image must be readable by ImageIO");
        assertEquals(1600, img.getWidth());
        assertTrue(img.getHeight() >= 500);
    }

    private Shift createShift(Long id, ShiftType type, LocalTime start, LocalTime end, int cap, boolean overnight) {
        Shift s = new Shift();
        s.setId(id);
        s.setShiftType(type);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setCapacity(cap);
        s.setOvernight(overnight);
        s.setActive(true);
        return s;
    }
}
