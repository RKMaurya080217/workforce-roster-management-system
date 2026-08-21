package com.weeklyroster.export;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weeklyroster.dto.response.CoverageReportResponse;
import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.GenerationMode;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RosterExcelExporterTest {

    @Test
    @DisplayName("Should generate valid OpenXML .xlsx byte array with proper structure")
    void testExportToExcelValidZip() throws Exception {
        LocalDate startDate = LocalDate.of(2026, 8, 24);
        LocalDate endDate = startDate.plusDays(6);

        List<Shift> shifts = createTestShifts();
        List<RosterAssignmentResponse> assignments = createSampleAssignments(startDate);

        RosterCycleResponse cycle = new RosterCycleResponse(
                1L,
                startDate,
                endDate,
                LocalDateTime.now(),
                GenerationMode.AUTOMATIC,
                "SENT",
                assignments,
                new CoverageReportResponse(56, 42, 42, 42, 0, 14, List.of(), List.of())
        );

        byte[] excelBytes = RosterExcelExporter.exportToExcel(cycle, shifts);

        assertNotNull(excelBytes);
        assertTrue(excelBytes.length > 500, "Generated Excel bytes should be substantial");

        // Verify valid ZIP structure containing OpenXML parts
        boolean hasWorkbook = false;
        boolean hasSheet = false;
        boolean hasStyles = false;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(excelBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("xl/workbook.xml".equals(entry.getName())) hasWorkbook = true;
                if ("xl/worksheets/sheet1.xml".equals(entry.getName())) hasSheet = true;
                if ("xl/styles.xml".equals(entry.getName())) hasStyles = true;
            }
        }

        assertTrue(hasWorkbook, "Excel package must contain xl/workbook.xml");
        assertTrue(hasSheet, "Excel package must contain xl/worksheets/sheet1.xml");
        assertTrue(hasStyles, "Excel package must contain xl/styles.xml");
    }

    private List<Shift> createTestShifts() {
        List<Shift> list = new ArrayList<>();
        list.add(createShift(1L, ShiftType.MORNING, LocalTime.of(7, 0), LocalTime.of(15, 0), 2, false));
        list.add(createShift(2L, ShiftType.GENERAL, LocalTime.of(9, 30), LocalTime.of(18, 0), 2, false));
        list.add(createShift(3L, ShiftType.EVENING, LocalTime.of(14, 0), LocalTime.of(22, 0), 2, false));
        list.add(createShift(4L, ShiftType.NIGHT, LocalTime.of(22, 0), LocalTime.of(7, 0), 1, true));
        list.add(createShift(5L, ShiftType.OFF, null, null, 1, false));
        return list;
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

    private List<RosterAssignmentResponse> createSampleAssignments(LocalDate start) {
        List<RosterAssignmentResponse> list = new ArrayList<>();
        String[] codes = {"EMP001", "EMP002", "EMP003", "EMP004", "EMP005", "EMP006", "EMP007"};
        String[] names = {"Aarav Sharma", "Priya Patel", "Rohan Verma", "Sneha Iyer", "Vikram Singh", "Ananya Rao", "Rahul Gupta"};
        Gender[] genders = {Gender.MALE, Gender.FEMALE, Gender.MALE, Gender.FEMALE, Gender.MALE, Gender.FEMALE, Gender.MALE};

        for (int day = 0; day < 7; day++) {
            LocalDate date = start.plusDays(day);
            list.add(new RosterAssignmentResponse(1L + day * 7, 1L, date, 1L, codes[0], names[0], genders[0], ShiftType.MORNING, false, false, false));
            list.add(new RosterAssignmentResponse(2L + day * 7, 1L, date, 2L, codes[1], names[1], genders[1], ShiftType.MORNING, false, false, false));
            list.add(new RosterAssignmentResponse(3L + day * 7, 1L, date, 3L, codes[2], names[2], genders[2], ShiftType.GENERAL, false, false, false));
            list.add(new RosterAssignmentResponse(4L + day * 7, 1L, date, 4L, codes[3], names[3], genders[3], ShiftType.GENERAL, false, false, false));
            list.add(new RosterAssignmentResponse(5L + day * 7, 1L, date, 5L, codes[4], names[4], genders[4], ShiftType.EVENING, false, false, false));
            list.add(new RosterAssignmentResponse(6L + day * 7, 1L, date, 6L, codes[5], names[5], genders[5], ShiftType.OFF, true, false, false));
            list.add(new RosterAssignmentResponse(7L + day * 7, 1L, date, 7L, codes[6], names[6], genders[6], ShiftType.NIGHT, false, false, false));
        }
        return list;
    }
}
