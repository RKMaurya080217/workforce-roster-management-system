package com.weeklyroster;

import com.weeklyroster.controller.ExportCenterController;
import com.weeklyroster.controller.RosterController;
import com.weeklyroster.dto.response.CoverageReportResponse;
import com.weeklyroster.dto.response.EmailDeliveryLogResponse;
import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.EmailDeliveryStatus;
import com.weeklyroster.entity.GenerationMode;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.RosterStatus;
import com.weeklyroster.export.EnterpriseImageExporter;
import com.weeklyroster.export.RosterImageExporter;
import com.weeklyroster.service.ExportCenterService;
import com.weeklyroster.service.RosterEmailService;
import com.weeklyroster.service.RosterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class Batch46ResponsiveAndExportIntegrationTest {

    @Mock
    private RosterService rosterService;

    @Mock
    private RosterEmailService rosterEmailService;

    @Mock
    private ExportCenterService exportCenterService;

    @InjectMocks
    private RosterController rosterController;

    private ExportCenterController exportCenterController;

    private RosterCycleResponse mockCycle;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "pass", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );

        exportCenterController = new ExportCenterController(exportCenterService);

        mockCycle = new RosterCycleResponse(
                101L,
                LocalDate.of(2026, 9, 7),
                LocalDate.of(2026, 9, 13),
                LocalDateTime.now(),
                GenerationMode.AUTOMATIC,
                "TENTATIVE",
                Collections.emptyList(),
                new CoverageReportResponse(35, 30, 30, 30, 0, 5, List.of(), List.of())
        );
    }

    @Test
    @DisplayName("Test 1: Roster PNG Export via RosterController returns valid non-empty PNG")
    void testRosterPngExport() throws Exception {
        byte[] samplePng = EnterpriseImageExporter.generateImage("Test Roster", List.of(new String[]{"Col1", "Col2"}, new String[]{"Val1", "Val2"}), "png");
        when(rosterService.exportImage(101L)).thenReturn(samplePng);
        when(rosterService.cycle(101L)).thenReturn(mockCycle);

        ResponseEntity<byte[]> response = rosterController.exportImage(101L);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().length > 100);

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(response.getBody()));
        assertNotNull(img, "Exported PNG must be readable image");
        assertTrue(response.getHeaders().getContentDisposition().toString().contains("WRMS_Roster_2026-09-07_to_2026-09-13.png"));
    }

    @Test
    @DisplayName("Test 2: Roster Excel Export via RosterController returns valid non-empty XLSX")
    void testRosterExcelExport() {
        byte[] sampleExcel = new byte[]{80, 75, 3, 4, 0, 0, 0, 0}; // PK zip header
        when(rosterService.exportExcel(101L)).thenReturn(sampleExcel);
        when(rosterService.cycle(101L)).thenReturn(mockCycle);

        ResponseEntity<byte[]> response = rosterController.exportExcel(101L);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().length >= 8);
        assertTrue(response.getHeaders().getContentDisposition().toString().contains("WRMS_Roster_2026-09-07_to_2026-09-13.xlsx"));
    }

    @Test
    @DisplayName("Test 3: Export Center Download returns requested format")
    void testExportCenterDownload() {
        byte[] samplePng = new byte[]{1, 2, 3, 4, 5};
        when(exportCenterService.generateExport(any())).thenReturn(samplePng);

        ResponseEntity<byte[]> response = exportCenterController.downloadReport(
                "WEEKLY_ROSTER", "png", LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 13), 101L, null, null
        );

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("image/png", response.getHeaders().getContentType().toString());
        assertTrue(response.getHeaders().getContentDisposition().toString().contains("WRMS_weekly_roster_2026-09-07.png"));
    }

    @Test
    @DisplayName("Test 4: Roster Email Delivery dispatches and returns delivery logs")
    void testRosterEmailDelivery() {
        when(rosterService.cycle(101L)).thenReturn(mockCycle);
        when(rosterEmailService.distributeRosterEmails(any(), any(), eq(GenerationMode.MANUAL)))
                .thenReturn(List.of(new EmailDeliveryLogResponse(1L, 101L, 1L, "EMP001", "Rajat Maurya", "rajat@example.com", "2026-09-02 00:00:00", EmailDeliveryStatus.SENT, null, GenerationMode.MANUAL)));

        ResponseEntity<List<EmailDeliveryLogResponse>> response = rosterController.sendEmail(101L);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals(EmailDeliveryStatus.SENT, response.getBody().get(0).status());
    }
}
