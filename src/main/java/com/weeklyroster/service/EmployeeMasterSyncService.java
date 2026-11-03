package com.weeklyroster.service;

import com.weeklyroster.dto.sync.*;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.Role;
import com.weeklyroster.entity.User;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class EmployeeMasterSyncService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeMasterSyncService.class);

    public static final String CANONICAL_HEADER =
            "employeeCode,firstName,lastName,email,gender,active,contactNumber,joiningDate,resignationDate,department,developmentTeam,maxNightsPerWeek,pairedEmployeeCode";

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${wrms.sync.employee-csv.path:data/employee.csv}")
    private String primaryCsvPath = "data/employee.csv";

    private final ReentrantLock syncLock = new ReentrantLock();
    private final AtomicBoolean isSyncingOrExporting = new AtomicBoolean(false);

    private volatile LocalDateTime lastSyncTimestamp;
    private volatile String lastSyncedFileHash;
    private volatile String lastSyncedDbHash;

    public EmployeeMasterSyncService(EmployeeRepository employeeRepository,
                                     UserRepository userRepository,
                                     PasswordEncoder passwordEncoder) {
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public String getCanonicalCsvHeader() {
        return CANONICAL_HEADER;
    }

    public String getPrimaryCsvPath() {
        return primaryCsvPath;
    }

    public File resolveCanonicalFile() {
        File primary = new File(primaryCsvPath);
        if (primary.exists()) {
            return primary;
        }
        File mirror = new File("src/main/resources/employees.csv");
        if (mirror.exists()) {
            return mirror;
        }
        return primary;
    }

    // -------------------------------------------------------------
    // Status & Hash Operations
    // -------------------------------------------------------------

    public SyncStatusResponse getSyncStatus() {
        List<Employee> dbEmployees = employeeRepository.findAllByOrderByIdAsc();
        String dbHash = computeDatabaseHash(dbEmployees);

        File file = resolveCanonicalFile();
        int csvCount = 0;
        String fileHash = "";

        if (file.exists() && file.length() > 0) {
            try {
                String content = readFileContent(file);
                List<EmployeeSyncRecord> parsed = parseCsvContent(content);
                csvCount = parsed.size();
                fileHash = computeSha256(normalizeCsvContent(content));
            } catch (Exception e) {
                log.warn("Failed to calculate CSV file status: {}", e.getMessage());
            }
        }

        boolean inSync = dbHash != null && dbHash.equalsIgnoreCase(fileHash);
        String status = inSync ? "IN_SYNC" : "OUT_OF_SYNC";

        return new SyncStatusResponse(
                status,
                lastSyncTimestamp,
                dbEmployees.size(),
                csvCount,
                dbHash,
                fileHash,
                file.getAbsolutePath()
        );
    }

    public String computeDatabaseHash(List<Employee> employees) {
        if (employees == null) {
            employees = employeeRepository.findAllByOrderByIdAsc();
        }
        String csvContent = exportCanonicalCsvString(employees);
        return computeSha256(normalizeCsvContent(csvContent));
    }

    public String computeSha256(String input) {
        if (input == null) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : encodedhash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    public String normalizeCsvContent(String content) {
        if (content == null) return "";
        BufferedReader reader = new BufferedReader(new StringReader(content));
        List<String> dataRows = new ArrayList<>();
        try {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (first) {
                    first = false;
                    continue; // Skip header for data comparison
                }
                dataRows.add(line);
            }
        } catch (IOException ignored) {}
        Collections.sort(dataRows);
        return String.join("\n", dataRows);
    }

    // -------------------------------------------------------------
    // Parsing & Schema Validation
    // -------------------------------------------------------------

    public List<EmployeeSyncRecord> parseCsvContent(String csvContent) {
        if (csvContent == null || csvContent.isBlank()) {
            return Collections.emptyList();
        }

        List<EmployeeSyncRecord> records = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new StringReader(csvContent));
        try {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return Collections.emptyList();
            }

            String[] headerCols = parseCsvLine(headerLine);
            Map<String, Integer> colIndex = new HashMap<>();
            for (int i = 0; i < headerCols.length; i++) {
                colIndex.put(headerCols[i].trim().toLowerCase(), i);
            }

            boolean isLegacyHeader = !colIndex.containsKey("joiningdate") && colIndex.containsKey("devpassword");

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] cols = parseCsvLine(line);
                if (cols.length == 0) continue;

                String code = getColValue(cols, colIndex, "employeecode", 0);
                if (code == null || code.isBlank()) continue;

                String firstName = getColValue(cols, colIndex, "firstname", 1);
                String lastName = getColValue(cols, colIndex, "lastname", 2);
                String email = getColValue(cols, colIndex, "email", 3);

                Gender gender = Gender.MALE;
                String genderStr = getColValue(cols, colIndex, "gender", 4);
                if (genderStr != null && !genderStr.isBlank()) {
                    try {
                        gender = Gender.valueOf(genderStr.trim().toUpperCase());
                    } catch (IllegalArgumentException ignored) {}
                }

                Boolean active = true;
                if (colIndex.containsKey("active")) {
                    String actStr = getColValue(cols, colIndex, "active", -1);
                    if (actStr != null && !actStr.isBlank()) {
                        active = Boolean.parseBoolean(actStr.trim());
                    }
                }

                String contact = getColValue(cols, colIndex, "contactnumber", 5);

                LocalDate joiningDate = null;
                String joinStr = getColValue(cols, colIndex, "joiningdate", -1);
                if (joinStr != null && !joinStr.isBlank()) {
                    try {
                        joiningDate = LocalDate.parse(joinStr.trim());
                    } catch (DateTimeParseException ignored) {}
                }

                LocalDate resignationDate = null;
                String resStr = getColValue(cols, colIndex, "resignationdate", -1);
                if (resStr != null && !resStr.isBlank()) {
                    try {
                        resignationDate = LocalDate.parse(resStr.trim());
                    } catch (DateTimeParseException ignored) {}
                }

                String dept = getColValue(cols, colIndex, "department", -1);
                if (dept == null || dept.isBlank()) {
                    dept = "OPERATIONS";
                }

                Boolean devTeam = false;
                if (colIndex.containsKey("developmentteam")) {
                    String devStr = getColValue(cols, colIndex, "developmentteam", -1);
                    if (devStr != null && !devStr.isBlank()) {
                        devTeam = Boolean.parseBoolean(devStr.trim()) || "1".equals(devStr.trim());
                    }
                }

                Integer maxNights = null;
                String nightStr = getColValue(cols, colIndex, "maxnightsperweek", -1);
                if (nightStr != null && !nightStr.isBlank()) {
                    try {
                        maxNights = Integer.parseInt(nightStr.trim());
                    } catch (NumberFormatException ignored) {}
                }

                String pairedCode = getColValue(cols, colIndex, "pairedemployeecode", -1);

                records.add(new EmployeeSyncRecord(
                        code.trim().toUpperCase(),
                        firstName != null ? firstName.trim() : "",
                        lastName != null ? lastName.trim() : "",
                        email != null ? email.trim().toLowerCase() : "",
                        gender,
                        active,
                        contact != null ? contact.trim() : "",
                        joiningDate,
                        resignationDate,
                        dept.trim(),
                        devTeam,
                        maxNights,
                        pairedCode != null ? pairedCode.trim().toUpperCase() : ""
                ));
            }
        } catch (IOException e) {
            log.error("Failed to parse CSV: {}", e.getMessage(), e);
        }
        return records;
    }

    private String getColValue(String[] cols, Map<String, Integer> colIndex, String key, int fallbackPos) {
        if (colIndex.containsKey(key)) {
            int idx = colIndex.get(key);
            if (idx >= 0 && idx < cols.length) {
                return cols[idx].trim();
            }
        }
        if (fallbackPos >= 0 && fallbackPos < cols.length) {
            return cols[fallbackPos].trim();
        }
        return null;
    }

    public static String[] parseCsvLine(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                tokens.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        tokens.add(sb.toString().trim());
        return tokens.toArray(new String[0]);
    }

    // -------------------------------------------------------------
    // Preview Diff & Validation
    // -------------------------------------------------------------

    public SyncPreviewResponse previewSyncFromFile(File file) throws IOException {
        String content = readFileContent(file);
        return previewSyncFromContent(content);
    }

    public SyncPreviewResponse previewSyncFromContent(String csvContent) {
        List<EmployeeSyncRecord> csvRecords = parseCsvContent(csvContent);
        List<Employee> dbEmployees = employeeRepository.findAllByOrderByIdAsc();
        Map<String, Employee> dbEmployeeMap = dbEmployees.stream()
                .collect(Collectors.toMap(e -> e.getEmployeeCode().toUpperCase(), e -> e, (a, b) -> a));

        Map<Long, String> idToCodeMap = dbEmployees.stream()
                .collect(Collectors.toMap(Employee::getId, Employee::getEmployeeCode, (a, b) -> a));

        List<EmployeeSyncDiffItem> diffs = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> seenCsvCodes = new HashSet<>();
        Set<String> seenEmails = new HashSet<>();

        int added = 0;
        int updated = 0;
        int unchanged = 0;
        int conflicts = 0;

        for (EmployeeSyncRecord rec : csvRecords) {
            String code = rec.employeeCode();

            // Duplicate code validation
            if (!seenCsvCodes.add(code)) {
                String err = "Duplicate Employee ID detected in CSV: " + code + ". Sync aborted.";
                errors.add(err);
                diffs.add(EmployeeSyncDiffItem.ofConflict(code, err));
                conflicts++;
                continue;
            }

            // Email validation
            if (rec.email() == null || rec.email().isBlank() || !EMAIL_PATTERN.matcher(rec.email()).matches()) {
                String err = "Invalid email format for employee " + code + ": " + rec.email();
                errors.add(err);
                diffs.add(EmployeeSyncDiffItem.ofConflict(code, err));
                conflicts++;
                continue;
            }

            if (!seenEmails.add(rec.email())) {
                String err = "Duplicate email address detected in CSV for employee " + code + ": " + rec.email();
                errors.add(err);
                diffs.add(EmployeeSyncDiffItem.ofConflict(code, err));
                conflicts++;
                continue;
            }

            // Date bounds validation
            if (rec.joiningDate() != null && rec.resignationDate() != null) {
                if (rec.resignationDate().isBefore(rec.joiningDate())) {
                    String err = "Resignation date " + rec.resignationDate() + " cannot be before joining date " + rec.joiningDate() + " for employee " + code;
                    errors.add(err);
                    diffs.add(EmployeeSyncDiffItem.ofConflict(code, err));
                    conflicts++;
                    continue;
                }
            }

            // Paired code self-reference check
            if (rec.pairedEmployeeCode() != null && !rec.pairedEmployeeCode().isBlank()) {
                if (code.equalsIgnoreCase(rec.pairedEmployeeCode())) {
                    String err = "Employee " + code + " cannot be paired with themselves.";
                    errors.add(err);
                    diffs.add(EmployeeSyncDiffItem.ofConflict(code, err));
                    conflicts++;
                    continue;
                }
            }

            Employee existing = dbEmployeeMap.get(code);
            if (existing == null) {
                // ADD
                List<SyncFieldDiff> changes = List.of(
                        new SyncFieldDiff("employeeCode", null, code),
                        new SyncFieldDiff("firstName", null, rec.firstName()),
                        new SyncFieldDiff("lastName", null, rec.lastName()),
                        new SyncFieldDiff("email", null, rec.email()),
                        new SyncFieldDiff("gender", null, rec.gender() != null ? rec.gender().name() : null),
                        new SyncFieldDiff("department", null, rec.department())
                );
                diffs.add(EmployeeSyncDiffItem.ofAdd(code, changes));
                added++;
            } else {
                // Compare fields
                List<SyncFieldDiff> changes = new ArrayList<>();
                checkFieldDiff(changes, "firstName", existing.getFirstName(), rec.firstName());
                checkFieldDiff(changes, "lastName", existing.getLastName(), rec.lastName());
                checkFieldDiff(changes, "email", existing.getEmail(), rec.email());
                checkFieldDiff(changes, "gender", existing.getGender() != null ? existing.getGender().name() : "", rec.gender() != null ? rec.gender().name() : "");
                checkFieldDiff(changes, "active", String.valueOf(existing.isActive()), String.valueOf(rec.active()));
                checkFieldDiff(changes, "contactNumber", existing.getContactNumber(), rec.contactNumber());
                checkFieldDiff(changes, "joiningDate", existing.getJoiningDate() != null ? existing.getJoiningDate().toString() : "", rec.joiningDate() != null ? rec.joiningDate().toString() : "");
                checkFieldDiff(changes, "resignationDate", existing.getResignationDate() != null ? existing.getResignationDate().toString() : "", rec.resignationDate() != null ? rec.resignationDate().toString() : "");
                checkFieldDiff(changes, "department", existing.getDepartment(), rec.department());
                checkFieldDiff(changes, "developmentTeam", String.valueOf(existing.isDevelopmentTeam()), String.valueOf(rec.developmentTeam()));
                checkFieldDiff(changes, "maxNightsPerWeek", existing.getMaxNightsPerWeek() != null ? String.valueOf(existing.getMaxNightsPerWeek()) : "", rec.maxNightsPerWeek() != null ? String.valueOf(rec.maxNightsPerWeek()) : "");

                String existingPairedCode = existing.getPairedEmployeeId() != null ? idToCodeMap.get(existing.getPairedEmployeeId()) : "";
                checkFieldDiff(changes, "pairedEmployeeCode", existingPairedCode != null ? existingPairedCode : "", rec.pairedEmployeeCode() != null ? rec.pairedEmployeeCode() : "");

                if (changes.isEmpty()) {
                    diffs.add(EmployeeSyncDiffItem.ofUnchanged(code));
                    unchanged++;
                } else {
                    diffs.add(EmployeeSyncDiffItem.ofUpdate(code, changes));
                    updated++;
                }
            }
        }

        // Check for records missing in CSV but present in DB -> PRESERVE
        int missingCount = 0;
        for (Employee emp : dbEmployees) {
            String code = emp.getEmployeeCode().toUpperCase();
            if (!seenCsvCodes.contains(code)) {
                diffs.add(EmployeeSyncDiffItem.ofPreserve(emp.getEmployeeCode()));
                missingCount++;
            }
        }

        boolean canApply = conflicts == 0;
        return new SyncPreviewResponse(
                canApply,
                csvRecords.size(),
                added,
                updated,
                unchanged,
                missingCount,
                conflicts,
                diffs,
                errors
        );
    }

    private void checkFieldDiff(List<SyncFieldDiff> list, String name, String oldVal, String newVal) {
        String o = oldVal == null ? "" : oldVal.trim();
        String n = newVal == null ? "" : newVal.trim();
        if (!o.equalsIgnoreCase(n)) {
            list.add(new SyncFieldDiff(name, o, n));
        }
    }

    // -------------------------------------------------------------
    // Apply Synchronization
    // -------------------------------------------------------------

    public SyncExecutionResult applySyncFromFile(File file) throws IOException {
        String content = readFileContent(file);
        return applySyncFromContent(content);
    }

    @Transactional
    public SyncExecutionResult applySyncFromContent(String csvContent) {
        syncLock.lock();
        try {
            isSyncingOrExporting.set(true);

            SyncPreviewResponse preview = previewSyncFromContent(csvContent);
            if (!preview.canApply()) {
                throw new BusinessException("Cannot apply CSV sync due to validation conflicts: " + String.join("; ", preview.errors()));
            }

            List<EmployeeSyncRecord> csvRecords = parseCsvContent(csvContent);
            List<Employee> allExisting = employeeRepository.findAll();
            Map<String, Employee> existingMap = allExisting.stream()
                    .collect(Collectors.toMap(e -> e.getEmployeeCode().toUpperCase(), e -> e, (a, b) -> a));

            int inserted = 0;
            int updated = 0;
            int preserved = preview.missingFromCsvCount();

            // Pass 1: Insert or update all employee attributes (without paired foreign key)
            for (EmployeeSyncRecord rec : csvRecords) {
                String code = rec.employeeCode();
                Employee emp = existingMap.get(code);
                boolean isNew = false;
                if (emp == null) {
                    emp = new Employee();
                    emp.setEmployeeCode(code);
                    isNew = true;
                }

                emp.setFirstName(rec.firstName());
                emp.setLastName(rec.lastName());
                emp.setEmail(rec.email());
                emp.setGender(rec.gender());
                emp.setActive(rec.active() != null ? rec.active() : true);
                emp.setContactNumber(rec.contactNumber());
                emp.setJoiningDate(rec.joiningDate());
                emp.setResignationDate(rec.resignationDate());
                emp.setDepartment(rec.department() != null ? rec.department() : "OPERATIONS");
                emp.setDevelopmentTeam(rec.developmentTeam() != null ? rec.developmentTeam() : false);
                emp.setMaxNightsPerWeek(rec.maxNightsPerWeek());

                // Ensure user account exists and is synchronized
                String username = code.toLowerCase();
                final boolean activeStatus = emp.isActive();
                User user = userRepository.findByUsernameIgnoreCase(username).orElseGet(() -> {
                    User u = new User();
                    u.setUsername(username);
                    u.setPassword(passwordEncoder.encode("password123"));
                    u.setRole(Role.ROLE_EMPLOYEE);
                    u.setEnabled(activeStatus);
                    return userRepository.save(u);
                });
                if (user.isEnabled() != activeStatus) {
                    user.setEnabled(activeStatus);
                    user = userRepository.save(user);
                }
                emp.setUser(user);

                Employee saved = employeeRepository.save(emp);
                existingMap.put(code, saved);

                if (isNew) {
                    inserted++;
                } else {
                    updated++;
                }
            }

            // Pass 2: Resolve pairedEmployeeCode
            for (EmployeeSyncRecord rec : csvRecords) {
                String code = rec.employeeCode();
                Employee emp = existingMap.get(code);
                if (emp == null) continue;

                if (rec.pairedEmployeeCode() != null && !rec.pairedEmployeeCode().isBlank()) {
                    Employee pairedEmp = existingMap.get(rec.pairedEmployeeCode());
                    if (pairedEmp != null) {
                        emp.setPairedEmployeeId(pairedEmp.getId());
                    } else {
                        emp.setPairedEmployeeId(null);
                    }
                } else {
                    emp.setPairedEmployeeId(null);
                }
                employeeRepository.save(emp);
            }

            // Export canonical CSV to disk and mirror
            File exportedFile = exportCanonicalCsvInternal();

            lastSyncTimestamp = LocalDateTime.now();
            lastSyncedFileHash = computeSha256(normalizeCsvContent(csvContent));
            lastSyncedDbHash = computeDatabaseHash(employeeRepository.findAllByOrderByIdAsc());

            log.info("Master data sync completed successfully: {} inserted, {} updated, {} preserved.", inserted, updated, preserved);

            return new SyncExecutionResult(
                    true,
                    inserted,
                    updated,
                    preserved,
                    List.of(),
                    lastSyncTimestamp,
                    lastSyncedFileHash,
                    lastSyncedDbHash
            );
        } finally {
            isSyncingOrExporting.set(false);
            syncLock.unlock();
        }
    }

    // -------------------------------------------------------------
    // Export Operations (DB -> CSV)
    // -------------------------------------------------------------

    public File exportCanonicalCsv() {
        syncLock.lock();
        try {
            isSyncingOrExporting.set(true);
            return exportCanonicalCsvInternal();
        } finally {
            isSyncingOrExporting.set(false);
            syncLock.unlock();
        }
    }

    private File exportCanonicalCsvInternal() {
        List<Employee> employees = employeeRepository.findAllByOrderByIdAsc();
        String content = exportCanonicalCsvString(employees);

        // Write primary file
        File primary = new File(primaryCsvPath);
        File parent = primary.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        writeFileContent(primary, content);

        // Write mirror if src/main/resources exists
        File mirror = new File("src/main/resources/employees.csv");
        if (mirror.getParentFile() != null && mirror.getParentFile().exists()) {
            writeFileContent(mirror, content);
        }

        lastSyncedDbHash = computeSha256(normalizeCsvContent(content));
        lastSyncedFileHash = lastSyncedDbHash;
        lastSyncTimestamp = LocalDateTime.now();

        log.info("Canonical employee CSV exported successfully to: {}", primary.getAbsolutePath());
        return primary;
    }

    public String exportCanonicalCsvString(List<Employee> employees) {
        if (employees == null) {
            employees = employeeRepository.findAllByOrderByIdAsc();
        }

        Map<Long, String> idToCodeMap = employees.stream()
                .collect(Collectors.toMap(Employee::getId, Employee::getEmployeeCode, (a, b) -> a));

        StringBuilder sb = new StringBuilder();
        sb.append(CANONICAL_HEADER).append("\n");

        for (Employee emp : employees) {
            String pairedCode = emp.getPairedEmployeeId() != null ? idToCodeMap.get(emp.getPairedEmployeeId()) : "";
            EmployeeSyncRecord rec = new EmployeeSyncRecord(
                    emp.getEmployeeCode(),
                    emp.getFirstName(),
                    emp.getLastName(),
                    emp.getEmail(),
                    emp.getGender(),
                    emp.isActive(),
                    emp.getContactNumber(),
                    emp.getJoiningDate(),
                    emp.getResignationDate(),
                    emp.getDepartment(),
                    emp.isDevelopmentTeam(),
                    emp.getMaxNightsPerWeek(),
                    pairedCode
            );
            sb.append(rec.toCsvLine()).append("\n");
        }
        return sb.toString();
    }

    public String exportAdminMasterCsvString() {
        List<User> admins = userRepository.findByRole(Role.ROLE_ADMIN);
        StringBuilder sb = new StringBuilder();
        sb.append("adminId,username,role,enabled\n");
        for (User u : admins) {
            sb.append(String.format("%d,%s,%s,%b\n",
                    u.getId(),
                    safe(u.getUsername()),
                    u.getRole().name(),
                    u.isEnabled()));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------
    // Cross-source Comparison
    // -------------------------------------------------------------

    public MasterDataComparisonReport compareMasterData() {
        List<Employee> dbEmployees = employeeRepository.findAllByOrderByIdAsc();
        String dbHash = computeDatabaseHash(dbEmployees);

        File file = resolveCanonicalFile();
        String fileContent = "";
        int csvCount = 0;
        String fileHash = "";

        if (file.exists() && file.length() > 0) {
            try {
                fileContent = readFileContent(file);
                List<EmployeeSyncRecord> parsed = parseCsvContent(fileContent);
                csvCount = parsed.size();
                fileHash = computeSha256(normalizeCsvContent(fileContent));
            } catch (Exception e) {
                log.warn("Error reading canonical CSV during compare: {}", e.getMessage());
            }
        }

        SyncPreviewResponse preview = previewSyncFromContent(fileContent);

        String syncStatus = (dbHash != null && dbHash.equalsIgnoreCase(fileHash)) ? "IN_SYNC" : "OUT_OF_SYNC";

        return new MasterDataComparisonReport(
                syncStatus,
                dbEmployees.size(),
                csvCount,
                preview.unchangedCount(),
                preview.updatedCount() + preview.conflictCount(),
                preview.addedCount(),
                preview.missingFromCsvCount(),
                dbHash,
                fileHash,
                preview.diffs()
        );
    }

    // -------------------------------------------------------------
    // Startup Sync
    // -------------------------------------------------------------

    @Transactional
    public void syncOnStartup() {
        try {
            File file = resolveCanonicalFile();
            List<Employee> existing = employeeRepository.findAll();

            if (!file.exists() || file.length() == 0) {
                if (!existing.isEmpty()) {
                    log.info("Startup sync: Canonical CSV not found on disk. Exporting existing {} database employees to CSV.", existing.size());
                    exportCanonicalCsv();
                }
                return;
            }

            String content = readFileContent(file);
            List<EmployeeSyncRecord> records = parseCsvContent(content);
            if (records.isEmpty()) {
                return;
            }

            if (existing.isEmpty()) {
                log.info("Startup sync: Database is empty. Seeding {} employees from canonical CSV.", records.size());
                applySyncFromContent(content);
                return;
            }

            String dbHash = computeDatabaseHash(existing);
            String fileHash = computeSha256(normalizeCsvContent(content));

            if (!dbHash.equalsIgnoreCase(fileHash)) {
                log.info("Startup sync: Detected difference between canonical CSV and Database. Applying sync from CSV.");
                applySyncFromContent(content);
            } else {
                log.info("Startup sync: Database and CSV are already in sync (hash: {}).", dbHash);
            }
        } catch (Exception e) {
            log.error("Startup sync encountered an error: {}", e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------
    // File I/O Helpers
    // -------------------------------------------------------------

    private String readFileContent(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private void writeFileContent(File file, String content) {
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8))) {
            bw.write(content);
        } catch (IOException e) {
            log.warn("Failed to write to file {}: {}", file.getPath(), e.getMessage());
        }
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace(",", " ").trim();
    }
}
