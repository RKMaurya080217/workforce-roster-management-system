package com.weeklyroster.service;

import com.weeklyroster.dto.request.EmployeeRequest;
import com.weeklyroster.dto.response.EmployeeResponse;
import com.weeklyroster.dto.sync.*;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.Role;
import com.weeklyroster.entity.User;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wrms.dev.credential-mirror.enabled=false",
        "wrms.sync.employee-csv.path=target/test-data/employee.csv",
        "spring.datasource.url=jdbc:sqlite:target/test-wrms-b126.db?busy_timeout=30000&journal_mode=WAL"
})
@TestMethodOrder(MethodOrderer.MethodName.class)
public class Batch126EmployeeMasterDataSyncTest {

    @Autowired
    private EmployeeMasterSyncService syncService;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MockMvc mockMvc;

    private static final String TEST_CSV_DIR = "target/test-data";

    @BeforeEach
    void setUp() {
        new File(TEST_CSV_DIR).mkdirs();
        employeeRepository.findByEmployeeCode("EMP088").ifPresent(e -> {
            userRepository.findByUsernameIgnoreCase("emp088").ifPresent(userRepository::delete);
            employeeRepository.delete(e);
        });
        // Seed baseline test employees if not already present
        if (!employeeRepository.existsByEmployeeCode("EMP001")) {
            createTestEmployee("EMP001", "Rajat", "Maurya", "rajat.test@cris.com", Gender.MALE, "OPERATIONS", false, null);
        }
        if (!employeeRepository.existsByEmployeeCode("EMP002")) {
            createTestEmployee("EMP002", "Prachi", "Mishra", "prachi.test@cris.com", Gender.FEMALE, "OPERATIONS", false, null);
        }
    }

    private Employee createTestEmployee(String code, String first, String last, String email, Gender gender, String dept, boolean dev, Long pairId) {
        Employee e = new Employee();
        e.setEmployeeCode(code);
        e.setFirstName(first);
        e.setLastName(last);
        e.setEmail(email);
        e.setGender(gender);
        e.setActive(true);
        e.setDepartment(dept);
        e.setDevelopmentTeam(dev);
        e.setPairedEmployeeId(pairId);
        e.setJoiningDate(LocalDate.of(2026, 4, 15));
        return employeeRepository.save(e);
    }

    // -------------------------------------------------------------
    // Scenario 1: Canonical CSV Header Schema Verification
    // -------------------------------------------------------------
    @Test
    void test01_canonicalCsvHeader_containsAll13ColumnsInOrder() {
        String header = syncService.getCanonicalCsvHeader();
        assertNotNull(header);
        String[] cols = header.split(",");
        assertEquals(13, cols.length, "Canonical CSV must have exactly 13 schema columns");
        assertEquals("employeeCode", cols[0]);
        assertEquals("firstName", cols[1]);
        assertEquals("lastName", cols[2]);
        assertEquals("email", cols[3]);
        assertEquals("gender", cols[4]);
        assertEquals("active", cols[5]);
        assertEquals("contactNumber", cols[6]);
        assertEquals("joiningDate", cols[7]);
        assertEquals("resignationDate", cols[8]);
        assertEquals("department", cols[9]);
        assertEquals("developmentTeam", cols[10]);
        assertEquals("maxNightsPerWeek", cols[11]);
        assertEquals("pairedEmployeeCode", cols[12]);
    }

    // -------------------------------------------------------------
    // Scenario 2: Legacy / Outdated CSV Schema Detection
    // -------------------------------------------------------------
    @Test
    void test02_outdatedCsvSchemaDetection_handlesMissingColumnsGracefully() {
        String legacyCsv = "employeeCode,firstName,lastName,email,gender,contactNumber,devPassword\n" +
                "EMP001,Rajat,Maurya,rajat.test@cris.com,MALE,9876543201,password123\n";

        List<EmployeeSyncRecord> records = syncService.parseCsvContent(legacyCsv);
        assertNotNull(records);
        assertEquals(1, records.size());
        EmployeeSyncRecord rec = records.get(0);
        assertEquals("EMP001", rec.employeeCode());
        assertEquals("Rajat", rec.firstName());
        assertEquals("Maurya", rec.lastName());
        assertEquals("rajat.test@cris.com", rec.email());
        assertEquals(Gender.MALE, rec.gender());
        assertEquals("9876543201", rec.contactNumber());
        assertTrue(rec.active());
        assertEquals("OPERATIONS", rec.department());
        assertNull(rec.joiningDate());
    }

    // -------------------------------------------------------------
    // Scenario 3: Stable Identity Enforcement (employeeCode is authoritative)
    // -------------------------------------------------------------
    @Test
    void test03_stableIdentityEnforcement_employeeCodeIsAuthoritative() {
        Employee before = employeeRepository.findByEmployeeCode("EMP001").orElseThrow();
        Long originalId = before.getId();

        String csv = "employeeCode,firstName,lastName,email,gender,active,contactNumber,joiningDate,resignationDate,department,developmentTeam,maxNightsPerWeek,pairedEmployeeCode\n" +
                "EMP001,RajatUpdated,MauryaUpdated,rajat.new@cris.com,MALE,true,9876500000,2026-04-14,,OPERATIONS,false,1,\n";

        SyncExecutionResult result = syncService.applySyncFromContent(csv);
        assertTrue(result.success());

        Employee after = employeeRepository.findByEmployeeCode("EMP001").orElseThrow();
        assertEquals(originalId, after.getId(), "Database Primary Key ID must be preserved across updates");
        assertEquals("RajatUpdated", after.getFirstName());
        assertEquals("MauryaUpdated", after.getLastName());
        assertEquals("rajat.new@cris.com", after.getEmail());
        assertEquals("9876500000", after.getContactNumber());
    }

    // -------------------------------------------------------------
    // Scenario 4: Duplicate Employee ID Detection in CSV Aborts Sync
    // -------------------------------------------------------------
    @Test
    void test04_duplicateEmployeeIdInCsv_abortsSyncWithConflict() {
        String duplicateCsv = "employeeCode,firstName,lastName,email,gender,active,contactNumber,joiningDate,resignationDate,department,developmentTeam,maxNightsPerWeek,pairedEmployeeCode\n" +
                "EMP001,Rajat,Maurya,rajat1@cris.com,MALE,true,,2026-04-14,,OPERATIONS,false,,\n" +
                "EMP001,RajatDuplicate,Maurya,rajat2@cris.com,MALE,true,,2026-04-14,,OPERATIONS,false,,\n";

        SyncPreviewResponse preview = syncService.previewSyncFromContent(duplicateCsv);
        assertFalse(preview.canApply(), "Preview must reject duplicate employee IDs");
        assertTrue(preview.conflictCount() > 0);
        assertTrue(preview.errors().stream().anyMatch(e -> e.contains("Duplicate Employee ID")));

        assertThrows(BusinessException.class, () -> syncService.applySyncFromContent(duplicateCsv));
    }

    // -------------------------------------------------------------
    // Scenario 5: Add New Employee From CSV with Full Schema
    // -------------------------------------------------------------
    @Test
    void test05_addNewEmployeeFromCsv_insertsWithFullSchemaAttributes() {
        String newEmpCsv = "employeeCode,firstName,lastName,email,gender,active,contactNumber,joiningDate,resignationDate,department,developmentTeam,maxNightsPerWeek,pairedEmployeeCode\n" +
                "EMP088,Anita,Sharma,anita.sharma@cris.com,FEMALE,true,9123456789,2026-05-10,,DEVELOPMENT,true,2,\n";

        SyncExecutionResult result = syncService.applySyncFromContent(newEmpCsv);
        assertTrue(result.success());
        assertEquals(1, result.recordsInserted());

        Optional<Employee> createdOpt = employeeRepository.findByEmployeeCode("EMP088");
        assertTrue(createdOpt.isPresent());
        Employee emp = createdOpt.get();
        assertEquals("Anita", emp.getFirstName());
        assertEquals("Sharma", emp.getLastName());
        assertEquals(Gender.FEMALE, emp.getGender());
        assertTrue(emp.isDevelopmentTeam());
        assertEquals("DEVELOPMENT", emp.getDepartment());
        assertEquals(Integer.valueOf(2), emp.getMaxNightsPerWeek());
        assertEquals(LocalDate.of(2026, 5, 10), emp.getJoiningDate());

        // Verify linked user was created
        assertTrue(userRepository.findByUsernameIgnoreCase("emp088").isPresent());
    }

    // -------------------------------------------------------------
    // Scenario 6: Update Existing Employee Attributes From CSV
    // -------------------------------------------------------------
    @Test
    void test06_updateExistingEmployeeAttributesFromCsv() {
        String updateCsv = "employeeCode,firstName,lastName,email,gender,active,contactNumber,joiningDate,resignationDate,department,developmentTeam,maxNightsPerWeek,pairedEmployeeCode\n" +
                "EMP002,Prachi,Mishra,prachi.mishra@cris.com,FEMALE,true,9876543299,2026-05-02,,INFRASTRUCTURE,true,3,\n";

        SyncExecutionResult result = syncService.applySyncFromContent(updateCsv);
        assertTrue(result.success());

        Employee emp = employeeRepository.findByEmployeeCode("EMP002").orElseThrow();
        assertEquals("INFRASTRUCTURE", emp.getDepartment());
        assertTrue(emp.isDevelopmentTeam());
        assertEquals(Integer.valueOf(3), emp.getMaxNightsPerWeek());
    }

    // -------------------------------------------------------------
    // Scenario 7: Joining Date Update via CSV Influences Roster Eligibility
    // -------------------------------------------------------------
    @Test
    void test07_joiningDateUpdateViaCsv_reflectsInRosterEligibility() {
        String csv = "employeeCode,firstName,lastName,email,gender,active,contactNumber,joiningDate,resignationDate,department,developmentTeam,maxNightsPerWeek,pairedEmployeeCode\n" +
                "EMP001,Rajat,Maurya,rajat.test@cris.com,MALE,true,,2026-06-01,,OPERATIONS,false,,\n";

        syncService.applySyncFromContent(csv);

        Employee emp = employeeRepository.findByEmployeeCode("EMP001").orElseThrow();
        assertEquals(LocalDate.of(2026, 6, 1), emp.getJoiningDate());

        // Prior to joining date -> NOT eligible
        assertFalse(emp.isEligibleForDate(LocalDate.of(2026, 5, 31)));
        // On or after joining date -> eligible
        assertTrue(emp.isEligibleForDate(LocalDate.of(2026, 6, 1)));
        assertTrue(emp.isEligibleForDate(LocalDate.of(2026, 6, 15)));
    }

    // -------------------------------------------------------------
    // Scenario 8: Resignation Date Update via CSV Terminates Eligibility
    // -------------------------------------------------------------
    @Test
    void test08_resignationDateUpdateViaCsv_terminatesEligibilityAfterDate() {
        String csv = "employeeCode,firstName,lastName,email,gender,active,contactNumber,joiningDate,resignationDate,department,developmentTeam,maxNightsPerWeek,pairedEmployeeCode\n" +
                "EMP001,Rajat,Maurya,rajat.test@cris.com,MALE,true,,2026-04-01,2026-08-31,OPERATIONS,false,,\n";

        syncService.applySyncFromContent(csv);

        Employee emp = employeeRepository.findByEmployeeCode("EMP001").orElseThrow();
        assertEquals(LocalDate.of(2026, 8, 31), emp.getResignationDate());

        // On or before resignation -> eligible
        assertTrue(emp.isEligibleForDate(LocalDate.of(2026, 8, 31)));
        // After resignation -> NOT eligible
        assertFalse(emp.isEligibleForDate(LocalDate.of(2026, 9, 1)));
    }

    // -------------------------------------------------------------
    // Scenario 9: Date Bounds Validation (Resignation before Joining) Fails
    // -------------------------------------------------------------
    @Test
    void test09_dateBoundsValidation_resignationBeforeJoining_failsWithConflict() {
        String invalidDateCsv = "employeeCode,firstName,lastName,email,gender,active,contactNumber,joiningDate,resignationDate,department,developmentTeam,maxNightsPerWeek,pairedEmployeeCode\n" +
                "EMP001,Rajat,Maurya,rajat.test@cris.com,MALE,true,,2026-09-01,2026-08-01,OPERATIONS,false,,\n";

        SyncPreviewResponse preview = syncService.previewSyncFromContent(invalidDateCsv);
        assertFalse(preview.canApply());
        assertTrue(preview.errors().stream().anyMatch(e -> e.contains("cannot be before joining date")));

        assertThrows(BusinessException.class, () -> syncService.applySyncFromContent(invalidDateCsv));
    }

    // -------------------------------------------------------------
    // Scenario 10: Paired Employee Code Resolution to Paired Employee ID
    // -------------------------------------------------------------
    @Test
    void test10_pairedEmployeeCodeResolution_mapsToPairedEmployeeId() {
        Employee emp2 = employeeRepository.findByEmployeeCode("EMP002").orElseThrow();

        String pairCsv = "employeeCode,firstName,lastName,email,gender,active,contactNumber,joiningDate,resignationDate,department,developmentTeam,maxNightsPerWeek,pairedEmployeeCode\n" +
                "EMP001,Rajat,Maurya,rajat.test@cris.com,MALE,true,,2026-04-01,,OPERATIONS,false,,EMP002\n" +
                "EMP002,Prachi,Mishra,prachi.mishra@cris.com,FEMALE,true,,2026-05-01,,OPERATIONS,false,,EMP001\n";

        SyncExecutionResult result = syncService.applySyncFromContent(pairCsv);
        assertTrue(result.success());

        Employee emp1After = employeeRepository.findByEmployeeCode("EMP001").orElseThrow();
        assertEquals(emp2.getId(), emp1After.getPairedEmployeeId(), "pairedEmployeeCode EMP002 must resolve to EMP002's numeric DB ID");
    }

    // -------------------------------------------------------------
    // Scenario 11: Paired Employee Self-Reference Rejection
    // -------------------------------------------------------------
    @Test
    void test11_pairedEmployeeSelfReference_rejectedWithConflict() {
        String selfPairCsv = "employeeCode,firstName,lastName,email,gender,active,contactNumber,joiningDate,resignationDate,department,developmentTeam,maxNightsPerWeek,pairedEmployeeCode\n" +
                "EMP001,Rajat,Maurya,rajat.test@cris.com,MALE,true,,2026-04-01,,OPERATIONS,false,,EMP001\n";

        SyncPreviewResponse preview = syncService.previewSyncFromContent(selfPairCsv);
        assertFalse(preview.canApply());
        assertTrue(preview.errors().stream().anyMatch(e -> e.contains("cannot be paired with themselves")));
    }

    // -------------------------------------------------------------
    // Scenario 12: Missing Row in CSV Preserves Existing DB Records
    // -------------------------------------------------------------
    @Test
    void test12_missingRowInCsv_preservesExistingDatabaseRecord() {
        assertTrue(employeeRepository.findByEmployeeCode("EMP002").isPresent());

        // CSV only contains EMP001 (EMP002 is omitted)
        String partialCsv = "employeeCode,firstName,lastName,email,gender,active,contactNumber,joiningDate,resignationDate,department,developmentTeam,maxNightsPerWeek,pairedEmployeeCode\n" +
                "EMP001,Rajat,Maurya,rajat.test@cris.com,MALE,true,,2026-04-01,,OPERATIONS,false,,\n";

        SyncPreviewResponse preview = syncService.previewSyncFromContent(partialCsv);
        assertTrue(preview.missingFromCsvCount() >= 1);
        assertTrue(preview.diffs().stream().anyMatch(d -> "PRESERVE_DATABASE_RECORD".equals(d.action()) && "EMP002".equals(d.employeeCode())));

        SyncExecutionResult result = syncService.applySyncFromContent(partialCsv);
        assertTrue(result.success());
        assertTrue(result.recordsPreserved() >= 1);

        // EMP002 must NOT be deleted from DB
        assertTrue(employeeRepository.findByEmployeeCode("EMP002").isPresent(), "Missing CSV records must remain preserved in DB");
    }

    // -------------------------------------------------------------
    // Scenario 13: Database Modification Triggers Canonical CSV Export
    // -------------------------------------------------------------
    @Test
    void test13_databaseModificationViaService_triggersCanonicalCsvExport() {
        Employee emp = employeeRepository.findByEmployeeCode("EMP001").orElseThrow();

        EmployeeRequest req = new EmployeeRequest(
                emp.getEmployeeCode(),
                "RajatExportTest",
                emp.getLastName(),
                emp.getEmail(),
                emp.getGender(),
                null,
                null,
                "9991112223",
                null,
                emp.getJoiningDate(),
                null,
                "OPERATIONS",
                false,
                1,
                null
        );

        EmployeeResponse res = employeeService.update(emp.getId(), req);
        assertEquals("RajatExportTest", res.firstName());

        File exportedFile = syncService.resolveCanonicalFile();
        assertTrue(exportedFile.exists(), "Canonical CSV file must be exported on DB update");
    }

    // -------------------------------------------------------------
    // Scenario 14: Controlled Sync Cycle (No Infinite Loop)
    // -------------------------------------------------------------
    @Test
    void test14_noInfiniteLoop_syncCycleIsControlled() {
        String csv = syncService.exportCanonicalCsvString(null);
        // Apply sync from this content
        SyncExecutionResult res = syncService.applySyncFromContent(csv);
        assertTrue(res.success());
        // Verify no infinite loop occurred and timestamp was recorded
        assertNotNull(res.timestamp());
    }

    // -------------------------------------------------------------
    // Scenario 15: Password Security in CSV Export
    // -------------------------------------------------------------
    @Test
    void test15_passwordSecurity_canonicalCsvNeverContainsPasswordsOrHashes() {
        String exported = syncService.exportCanonicalCsvString(null);
        assertNotNull(exported);

        assertFalse(exported.toLowerCase().contains("password123"), "Passwords must never appear in CSV export");
        assertFalse(exported.toLowerCase().contains("$2a$"), "BCrypt hashes must never appear in CSV export");
        assertFalse(exported.contains("devPassword"), "Legacy devPassword header column must never appear in canonical export");
    }

    // -------------------------------------------------------------
    // Scenario 16: Admin Master Data Safe Export
    // -------------------------------------------------------------
    @Test
    void test16_adminMasterDataExport_safeFieldsOnly() {
        // Ensure admin user exists
        if (userRepository.findByRole(Role.ROLE_ADMIN).isEmpty()) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword("hashedsecret");
            admin.setRole(Role.ROLE_ADMIN);
            admin.setEnabled(true);
            userRepository.save(admin);
        }

        String adminCsv = syncService.exportAdminMasterCsvString();
        assertNotNull(adminCsv);
        assertTrue(adminCsv.startsWith("adminId,username,role,enabled"));
        assertTrue(adminCsv.contains("admin"));
        assertFalse(adminCsv.contains("hashedsecret"), "Admin password hash must never be in admin master export");
    }

    // -------------------------------------------------------------
    // Scenario 17: Cross-Source SHA-256 Hash Matching
    // -------------------------------------------------------------
    @Test
    void test17_sha256HashMatching_inSyncDetection() {
        syncService.exportCanonicalCsv();
        SyncStatusResponse status = syncService.getSyncStatus();

        assertNotNull(status.databaseHash());
        assertNotNull(status.csvHash());
        assertEquals("IN_SYNC", status.syncStatus());
        assertEquals(status.databaseHash(), status.csvHash());
    }

    // -------------------------------------------------------------
    // Scenario 18: Sync Preview Endpoint Accuracy
    // -------------------------------------------------------------
    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test18_syncPreviewEndpoint_returnsAccurateDiffCounts() throws Exception {
        String previewContent = "employeeCode,firstName,lastName,email,gender,active,contactNumber,joiningDate,resignationDate,department,developmentTeam,maxNightsPerWeek,pairedEmployeeCode\n" +
                "EMP001,Rajat,Maurya,rajat.test@cris.com,MALE,true,,2026-04-01,,OPERATIONS,false,,\n";

        mockMvc.perform(post("/api/admin/employees/sync/preview")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(previewContent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canApply").isBoolean())
                .andExpect(jsonPath("$.totalCsvRecords").value(1));
    }

    // -------------------------------------------------------------
    // Scenario 19: Transactional Rollback on Fatal Sync Error
    // -------------------------------------------------------------
    @Test
    void test19_transactionalRollback_onFatalError() {
        Employee before = employeeRepository.findByEmployeeCode("EMP001").orElseThrow();
        String originalFirst = before.getFirstName();

        // CSV has one valid update for EMP001 and one fatal conflict for EMP002 (resignation before joining)
        String faultyBatchCsv = "employeeCode,firstName,lastName,email,gender,active,contactNumber,joiningDate,resignationDate,department,developmentTeam,maxNightsPerWeek,pairedEmployeeCode\n" +
                "EMP001,MutatedName,Maurya,rajat.test@cris.com,MALE,true,,2026-04-01,,OPERATIONS,false,,\n" +
                "EMP002,Prachi,Mishra,prachi.mishra@cris.com,FEMALE,true,,2026-09-01,2026-08-01,OPERATIONS,false,,\n";

        assertThrows(BusinessException.class, () -> syncService.applySyncFromContent(faultyBatchCsv));

        // Verify EMP001 was NOT mutated (rolled back)
        Employee after = employeeRepository.findByEmployeeCode("EMP001").orElseThrow();
        assertEquals(originalFirst, after.getFirstName(), "Database must remain untouched when sync contains fatal validation errors");
    }

    // -------------------------------------------------------------
    // Scenario 20: Startup Sync Loads External CSV Modifications
    // -------------------------------------------------------------
    @Test
    void test20_startupSync_ingestsModifiedCsvAutomatically() throws Exception {
        File targetFile = syncService.resolveCanonicalFile();
        targetFile.getParentFile().mkdirs();

        String modifiedCsv = "employeeCode,firstName,lastName,email,gender,active,contactNumber,joiningDate,resignationDate,department,developmentTeam,maxNightsPerWeek,pairedEmployeeCode\n" +
                "EMP001,StartupSyncName,Maurya,rajat.test@cris.com,MALE,true,,2026-04-01,,OPERATIONS,false,,\n";

        try (FileWriter fw = new FileWriter(targetFile, StandardCharsets.UTF_8)) {
            fw.write(modifiedCsv);
        }

        // Trigger startup sync
        syncService.syncOnStartup();

        Employee emp = employeeRepository.findByEmployeeCode("EMP001").orElseThrow();
        assertEquals("StartupSyncName", emp.getFirstName(), "Startup sync must automatically ingest modifications from external canonical CSV file");
    }
}
