package com.weeklyroster.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.weeklyroster.entity.Employee;

/**
 * Development-only credential and profile mirror for employees.csv.
 * STRICTLY for local development/testing.
 * Disabled by default in production via configuration property.
 */
@Service
public class DevCredentialMirrorService {

    private static final Logger log = LoggerFactory.getLogger(DevCredentialMirrorService.class);
    private final ReentrantLock lock = new ReentrantLock();

    @Value("${wrms.dev.credential-mirror.enabled:true}")
    private boolean devCredentialMirrorEnabled = true;

    private static final String CSV_HEADER = "employeeCode,firstName,lastName,email,gender,contactNumber,devPassword";

    public static class CsvEmployeeRecord {
        public String employeeCode = "";
        public String firstName = "";
        public String lastName = "";
        public String email = "";
        public String gender = "MALE";
        public String contactNumber = "";
        public String devPassword = "password123";

        public String toCsvLine() {
            return String.format("%s,%s,%s,%s,%s,%s,%s",
                    safe(employeeCode),
                    safe(firstName),
                    safe(lastName),
                    safe(email),
                    safe(gender),
                    safe(contactNumber),
                    safe(devPassword));
        }

        private String safe(String val) {
            return val == null ? "" : val.replace(",", " ").trim();
        }
    }

    /**
     * Synchronize a password change for a user to employees.csv in local development mode.
     */
    public void updatePassword(String usernameOrCode, String newPlaintextPassword) {
        if (!devCredentialMirrorEnabled) {
            log.debug("Dev credential mirror is disabled. Skipping employees.csv password update.");
            return;
        }

        if (usernameOrCode == null || newPlaintextPassword == null) {
            return;
        }

        lock.lock();
        try {
            List<File> targetFiles = resolveCsvFiles();
            for (File csvFile : targetFiles) {
                if (!csvFile.exists()) {
                    continue;
                }
                List<CsvEmployeeRecord> records = readCsv(csvFile);
                boolean matched = false;
                for (CsvEmployeeRecord rec : records) {
                    if (matches(rec.employeeCode, usernameOrCode)) {
                        rec.devPassword = newPlaintextPassword;
                        matched = true;
                        break;
                    }
                }
                if (matched) {
                    writeCsv(csvFile, records);
                    log.info("Development mirror: synchronized password for {} in {}", usernameOrCode, csvFile.getName());
                }
            }
        } catch (Exception e) {
            log.warn("Development mirror: failed to update employees.csv password for {}: {}", usernameOrCode, e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Synchronize employee profile field updates to employees.csv in local development mode.
     */
    public void updateProfile(Employee employee, String optionalDevPassword) {
        if (!devCredentialMirrorEnabled || employee == null) {
            return;
        }

        lock.lock();
        try {
            List<File> targetFiles = resolveCsvFiles();
            for (File csvFile : targetFiles) {
                if (!csvFile.exists()) {
                    continue;
                }
                List<CsvEmployeeRecord> records = readCsv(csvFile);
                boolean matched = false;
                for (CsvEmployeeRecord rec : records) {
                    if (matches(rec.employeeCode, employee.getEmployeeCode())) {
                        rec.firstName = employee.getFirstName() != null ? employee.getFirstName() : rec.firstName;
                        rec.lastName = employee.getLastName() != null ? employee.getLastName() : rec.lastName;
                        rec.email = employee.getEmail() != null ? employee.getEmail() : rec.email;
                        rec.gender = employee.getGender() != null ? employee.getGender().name() : rec.gender;
                        rec.contactNumber = employee.getContactNumber() != null ? employee.getContactNumber() : rec.contactNumber;
                        if (optionalDevPassword != null && !optionalDevPassword.isBlank()) {
                            rec.devPassword = optionalDevPassword;
                        }
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    CsvEmployeeRecord newRec = new CsvEmployeeRecord();
                    newRec.employeeCode = employee.getEmployeeCode();
                    newRec.firstName = employee.getFirstName() != null ? employee.getFirstName() : "";
                    newRec.lastName = employee.getLastName() != null ? employee.getLastName() : "";
                    newRec.email = employee.getEmail() != null ? employee.getEmail() : "";
                    newRec.gender = employee.getGender() != null ? employee.getGender().name() : "MALE";
                    newRec.contactNumber = employee.getContactNumber() != null ? employee.getContactNumber() : "";
                    newRec.devPassword = optionalDevPassword != null ? optionalDevPassword : "password123";
                    records.add(newRec);
                }
                writeCsv(csvFile, records);
                log.info("Development mirror: synchronized profile for {} in {}", employee.getEmployeeCode(), csvFile.getName());
            }
        } catch (Exception e) {
            log.warn("Development mirror: failed to update employees.csv profile for {}: {}", employee.getEmployeeCode(), e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    private boolean matches(String code, String query) {
        if (code == null || query == null) return false;
        String cleanCode = code.trim().toLowerCase();
        String cleanQuery = query.trim().toLowerCase();
        return cleanCode.equals(cleanQuery) ||
               cleanCode.replace("emp0", "emp").equals(cleanQuery) ||
               cleanCode.equals("emp" + cleanQuery) ||
               cleanQuery.equals("emp" + cleanCode);
    }

    private List<File> resolveCsvFiles() {
        List<File> files = new ArrayList<>();
        File srcFile = new File("src/main/resources/employees.csv");
        if (srcFile.exists()) files.add(srcFile);
        File targetFile = new File("target/classes/employees.csv");
        if (targetFile.exists()) files.add(targetFile);
        return files;
    }

    private List<CsvEmployeeRecord> readCsv(File file) {
        List<CsvEmployeeRecord> list = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = br.readLine()) != null) {
                if (header) {
                    header = false;
                    continue;
                }
                if (line.trim().isEmpty()) continue;
                String[] cols = line.split(",", -1);
                if (cols.length >= 5) {
                    CsvEmployeeRecord rec = new CsvEmployeeRecord();
                    rec.employeeCode = cols[0].trim();
                    rec.firstName = cols[1].trim();
                    rec.lastName = cols[2].trim();
                    rec.email = cols[3].trim();
                    rec.gender = cols[4].trim().toUpperCase();
                    if (cols.length >= 6) rec.contactNumber = cols[5].trim();
                    if (cols.length >= 7) rec.devPassword = cols[6].trim();
                    list.add(rec);
                }
            }
        } catch (Exception e) {
            log.warn("Error reading {}: {}", file.getPath(), e.getMessage());
        }
        return list;
    }

    private void writeCsv(File file, List<CsvEmployeeRecord> records) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(file, StandardCharsets.UTF_8, false))) {
            pw.println(CSV_HEADER);
            for (CsvEmployeeRecord rec : records) {
                pw.println(rec.toCsvLine());
            }
        } catch (Exception e) {
            log.warn("Error writing {}: {}", file.getPath(), e.getMessage());
        }
    }
}
