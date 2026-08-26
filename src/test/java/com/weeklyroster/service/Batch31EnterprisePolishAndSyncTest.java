package com.weeklyroster.service;

import com.weeklyroster.dto.request.ExportReportRequest;
import com.weeklyroster.dto.request.ProfileChangeDecisionRequest;
import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class Batch31EnterprisePolishAndSyncTest {

    @Autowired
    private ExportCenterService exportCenterService;

    @Autowired
    private UnifiedApprovalService unifiedApprovalService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeePreferenceRepository preferenceRepository;

    @Autowired
    private ProfileChangeRequestRepository profileChangeRequestRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    private void authenticateAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "N/A", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
    }

    @BeforeEach
    void setUp() {
        authenticateAdmin();
    }

    @Test
    @DisplayName("Batch 31 Test 1: PDF Export Integrity - Generates valid standard PDF byte-stream (Not 0 KB, contains valid xref & trailer)")
    void test1_PdfExportIntegrity() {
        List<String> reportTypes = List.of(
                "WEEKLY_ROSTER",
                "EMPLOYEE_SCHEDULE",
                "LEAVE_REPORT",
                "WORKLOAD_REPORT",
                "AUDIT_REPORT",
                "HOLIDAY_CALENDAR",
                "VALIDATION_REPORT"
        );

        for (String type : reportTypes) {
            ExportReportRequest req = new ExportReportRequest(
                    type,
                    "pdf",
                    LocalDate.now().minusDays(7),
                    LocalDate.now().plusDays(7),
                    null,
                    null,
                    null
            );
            byte[] pdfBytes = exportCenterService.generateExport(req);

            assertNotNull(pdfBytes, "PDF bytes must not be null for " + type);
            assertTrue(pdfBytes.length > 300, "PDF file must not be 0 KB. Actual length: " + pdfBytes.length + " for " + type);

            String pdfText = new String(pdfBytes);
            assertTrue(pdfText.startsWith("%PDF-1.4"), "PDF must start with standard %PDF-1.4 header for " + type);
            assertTrue(pdfText.contains("xref"), "PDF must contain cross-reference table xref for " + type);
            assertTrue(pdfText.contains("trailer"), "PDF must contain trailer dictionary for " + type);
            assertTrue(pdfText.contains("%%EOF"), "PDF must end with standard %%EOF marker for " + type);
        }
    }

    @Test
    @DisplayName("Batch 31 Test 2: Image Export Integrity - Generates valid, readable PNG, JPG, and JPEG images")
    void test2_ImageExportIntegrity() throws Exception {
        List<String> formats = List.of("png", "jpg", "jpeg");

        for (String format : formats) {
            ExportReportRequest req = new ExportReportRequest(
                    "WEEKLY_ROSTER",
                    format,
                    LocalDate.now().minusDays(7),
                    LocalDate.now().plusDays(7),
                    null,
                    null,
                    null
            );
            byte[] imgBytes = exportCenterService.generateExport(req);

            assertNotNull(imgBytes, "Image bytes must not be null for format " + format);
            assertTrue(imgBytes.length > 500, "Image bytes must be > 500 bytes for format " + format);

            // Decode image using standard ImageIO
            var img = ImageIO.read(new ByteArrayInputStream(imgBytes));
            assertNotNull(img, "ImageIO must successfully decode exported " + format + " image");
            assertTrue(img.getWidth() >= 800, "Exported image width should be at least 800px");
            assertTrue(img.getHeight() >= 200, "Exported image height should be at least 200px");
        }
    }

    @Test
    @DisplayName("Batch 31 Test 3: Unified Approvals Summary & All Requests DTO integrity")
    void test3_UnifiedApprovalsSummaryAndAll() {
        UnifiedApprovalsSummaryResponse summary = unifiedApprovalService.getSummary();
        assertNotNull(summary);
        assertTrue(summary.totalPending() >= 0);
        assertEquals(
                summary.totalPending(),
                summary.profileRequestsCount() + summary.leaveRequestsCount() + summary.preferenceRequestsCount(),
                "Total pending must equal the exact sum of category counts"
        );

        UnifiedApprovalsResponse all = unifiedApprovalService.getAllPending();
        assertNotNull(all);
        assertNotNull(all.profileRequests());
        assertNotNull(all.leaveRequests());
        assertNotNull(all.preferenceRequests());
        assertEquals(summary.totalPending(), all.totalPending());
    }

    @Test
    @DisplayName("Batch 31 Test 4: Unified Approvals Decision Workflow reduces pending counts properly")
    void test4_UnifiedApprovalsDecisionWorkflow() {
        List<Employee> employees = employeeRepository.findAll();
        assertFalse(employees.isEmpty(), "Must have employees in database");
        Employee emp = employees.get(0);

        // 1. Create a dummy Profile Change Request
        ProfileChangeRequest pcr = new ProfileChangeRequest();
        pcr.setEmployee(emp);
        pcr.setFieldName("contactNumber");
        pcr.setCurrentValue("1234567890");
        pcr.setRequestedValue("9876543210");
        pcr.setRequestedAt(LocalDateTime.now());
        pcr.setStatus(ProfileChangeStatus.PENDING);
        pcr = profileChangeRequestRepository.save(pcr);

        long countBefore = unifiedApprovalService.getSummary().profileRequestsCount();
        assertTrue(countBefore >= 1);

        // Approve profile change
        ProfileChangeRequestResponse decided = unifiedApprovalService.decideProfile(
                pcr.getId(),
                true,
                new ProfileChangeDecisionRequest("Approved by Admin in Test")
        );
        assertEquals(ProfileChangeStatus.APPROVED, decided.status());

        long countAfter = unifiedApprovalService.getSummary().profileRequestsCount();
        assertEquals(countBefore - 1, countAfter, "Pending count must decrement by 1 after approval");
    }

    @Test
    @DisplayName("Batch 31 Test 5: All supported export formats generate fresh database content")
    void test5_AllExportFormatsGenerateFreshData() {
        List<String> formats = List.of("xlsx", "pdf", "csv", "png", "jpg", "jpeg");
        for (String fmt : formats) {
            ExportReportRequest req = new ExportReportRequest(
                    "LEAVE_REPORT",
                    fmt,
                    LocalDate.now().minusMonths(1),
                    LocalDate.now().plusMonths(1),
                    null,
                    null,
                    null
            );
            byte[] data = exportCenterService.generateExport(req);
            assertNotNull(data);
            assertTrue(data.length > 0, "Export output must not be empty for format: " + fmt);
        }
    }
}
