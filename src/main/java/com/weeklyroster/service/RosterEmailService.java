package com.weeklyroster.service;

import com.weeklyroster.dto.response.EmailDeliveryLogResponse;
import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.EmailDeliveryLog;
import com.weeklyroster.entity.EmailDeliveryStatus;
import com.weeklyroster.entity.EmailType;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.GenerationMode;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.export.RosterExcelExporter;
import com.weeklyroster.export.RosterImageExporter;
import com.weeklyroster.repository.EmailDeliveryLogRepository;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.ShiftRepository;
import jakarta.mail.internet.MimeMessage;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RosterEmailService {

    private static final Logger log = LoggerFactory.getLogger(RosterEmailService.class);
    private static final DateTimeFormatter DISPLAY_DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private final EmailDeliveryLogRepository emailLogRepository;
    private final EmployeeRepository employeeRepository;
    private final RosterCycleRepository cycleRepository;
    private final RosterAssignmentRepository assignmentRepository;
    private final ShiftRepository shiftRepository;
    private final JavaMailSender mailSender;

    @Value("${roster.auto-email.enabled:true}")
    private boolean autoEmailEnabled;

    @Value("${spring.mail.host:smtp.gmail.com}")
    private String mailHost;

    @Value("${spring.mail.username:${MAIL_USERNAME:${SPRING_MAIL_USERNAME:${SMTP_USERNAME:rajatkumarmaury@gmail.com}}}}")
    private String mailUsername;

    @Value("${spring.mail.password:${MAIL_APP_PASSWORD:${SPRING_MAIL_PASSWORD:${MAIL_PASSWORD:}}}}")
    private String mailPassword;

    private volatile boolean isShuttingDown = false;

    @jakarta.annotation.PreDestroy
    public void onShutdown() {
        this.isShuttingDown = true;
        log.info("[WRMS EMAIL] System shutting down. Stopping all background and scheduled email operations.");
    }

    @Autowired
    public RosterEmailService(EmailDeliveryLogRepository emailLogRepository,
                              EmployeeRepository employeeRepository,
                              RosterCycleRepository cycleRepository,
                              RosterAssignmentRepository assignmentRepository,
                              ShiftRepository shiftRepository,
                              @Autowired(required = false) JavaMailSender mailSender) {
        this.emailLogRepository = emailLogRepository;
        this.employeeRepository = employeeRepository;
        this.cycleRepository = cycleRepository;
        this.assignmentRepository = assignmentRepository;
        this.shiftRepository = shiftRepository;
        this.mailSender = mailSender;
    }

    public RosterEmailService(EmailDeliveryLogRepository emailLogRepository,
                              EmployeeRepository employeeRepository,
                              RosterCycleRepository cycleRepository,
                              RosterAssignmentRepository assignmentRepository,
                              ShiftRepository shiftRepository) {
        this(emailLogRepository, employeeRepository, cycleRepository, assignmentRepository, shiftRepository, null);
    }

    /**
     * Global validation: Checks if given cycle dates match the immediate upcoming Monday to Sunday cycle.
     */
        @Transactional
    public List<EmailDeliveryLogResponse> distributeTentativeRosterEmails(RosterCycle cycle, RosterCycleResponse cycleResponse, GenerationMode mode) {
        if (cycle != null) {
            cycle.setStatus(com.weeklyroster.entity.RosterStatus.TENTATIVE);
        }
        return distributeRosterEmails(cycle, cycleResponse, mode);
    }

    @Transactional
    public List<EmailDeliveryLogResponse> distributeFinalRosterEmails(RosterCycle cycle, RosterCycleResponse cycleResponse, GenerationMode mode) {
        if (cycle != null) {
            cycle.setStatus(com.weeklyroster.entity.RosterStatus.FINAL);
        }
        return distributeRosterEmails(cycle, cycleResponse, mode);
    }

    public boolean isImmediateUpcomingWeek(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) return false;
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        LocalDate currentStart = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        LocalDate upcomingStart = currentStart.plusDays(7);
        LocalDate upcomingEnd = upcomingStart.plusDays(6);
        return startDate.equals(upcomingStart) && endDate.equals(upcomingEnd);
    }

    @Transactional
    public List<EmailDeliveryLogResponse> distributeRosterEmails(RosterCycle cycle, RosterCycleResponse cycleResponse, GenerationMode mode) {
        if (isShuttingDown) {
            log.warn("[WRMS EMAIL] Email distribution skipped due to application shutdown in progress.");
            return List.of();
        }

        if (cycle == null || cycle.getStartDate() == null || cycle.getEndDate() == null) {
            log.warn("[WRMS EMAIL] Cannot distribute roster emails for null or incomplete cycle.");
            return List.of();
        }

        // When mode is AUTOMATIC, enforce strict Upcoming Week Guard & Idempotency:
        if (mode == GenerationMode.AUTOMATIC) {
            java.time.ZoneId istZone = java.time.ZoneId.of("Asia/Kolkata");
            java.time.ZonedDateTime nowIst = java.time.ZonedDateTime.now(istZone);
            java.time.ZonedDateTime nowUtc = nowIst.withZoneSameInstant(java.time.ZoneId.of("UTC"));
            LocalDate today = nowIst.toLocalDate();
            LocalDate currentStart = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
            LocalDate upcomingStart = currentStart.plusDays(7);
            LocalDate upcomingEnd = upcomingStart.plusDays(6);

            if (!isImmediateUpcomingWeek(cycle.getStartDate(), cycle.getEndDate())) {
                log.warn("[WRMS EMAIL]\n" +
                         "  Time: {} IST (UTC: {})\n" +
                         "  Cycle: {} -> {}\n" +
                         "  Status: SKIPPED\n" +
                         "  Reason: Not immediate upcoming week (expected {} -> {})",
                        nowIst.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                        nowUtc.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                        cycle.getStartDate(), cycle.getEndDate(), upcomingStart, upcomingEnd);
                return List.of();
            }

            // Check if automated emails were already sent for this cycle
            List<EmailDeliveryLog> sentLogs = emailLogRepository.findByCycleAndEmailTypeAndStatus(cycle, (cycle.getStatus() == com.weeklyroster.entity.RosterStatus.FINAL || cycle.getStatus() == com.weeklyroster.entity.RosterStatus.LOCKED) ? EmailType.FINAL_ROSTER : EmailType.TENTATIVE_ROSTER, EmailDeliveryStatus.SENT);
            if (sentLogs == null || sentLogs.isEmpty()) {
                sentLogs = emailLogRepository.findByCycleAndStatus(cycle, EmailDeliveryStatus.SENT);
            }
            if (!sentLogs.isEmpty()) {
                log.info("[WRMS EMAIL] Automated roster emails for upcoming cycle #{} ({} to {}) have already been sent ({} logs). Skipping duplicate dispatch.",
                        cycle.getId(), cycle.getStartDate(), cycle.getEndDate(), sentLogs.size());
                return sentLogs.stream().map(this::toResponse).toList();
            }

            log.info("[WRMS EMAIL] Starting automated roster email distribution ONLY for upcoming cycle: {} -> {}",
                    cycle.getStartDate(), cycle.getEndDate());
        } else {
            log.info("[WRMS EMAIL] Distributing weekly roster emails for cycle #{} ({} to {}) in mode {}...",
                    cycle.getId(), cycle.getStartDate(), cycle.getEndDate(), mode);
        }

        List<Employee> activeEmployees = employeeRepository.findByActiveTrueOrderByIdAsc();
        List<Shift> shifts = shiftRepository.findByActiveTrueOrderByIdAsc();

        // Pre-generate attachments once
        byte[] excelBytes = null;
        byte[] imageBytes = null;
        try {
            excelBytes = RosterExcelExporter.exportToExcel(cycleResponse, shifts);
            imageBytes = RosterImageExporter.exportToImage(cycleResponse, shifts);
        } catch (Exception e) {
            log.error("Failed to pre-generate email attachments for cycle {}", cycle.getId(), e);
        }

        // Map assignments by employeeId -> list of assignments
        Map<Long, List<RosterAssignmentResponse>> empAssignments = cycleResponse.assignments() != null
                ? cycleResponse.assignments().stream().collect(Collectors.groupingBy(RosterAssignmentResponse::employeeId))
                : Map.of();

        List<EmailDeliveryLog> logs = new ArrayList<>();

        for (Employee emp : activeEmployees) {
            if (isShuttingDown) {
                log.warn("[WRMS EMAIL] Distribution interrupted by application shutdown.");
                break;
            }
            List<RosterAssignmentResponse> myShifts = empAssignments.getOrDefault(emp.getId(), List.of());
            EmailDeliveryLog entry = sendToEmployee(cycle, emp, myShifts, shifts, excelBytes, imageBytes, mode);
            if (entry != null) {
                logs.add(emailLogRepository.save(entry));
            }
        }

        return logs.stream().map(this::toResponse).toList();
    }

    @Transactional
    public List<EmailDeliveryLogResponse> retryFailedEmails(Long cycleId) {
        RosterCycle cycle = cycleRepository.findById(cycleId)
                .orElseThrow(() -> new IllegalArgumentException("Roster cycle not found with id: " + cycleId));

        List<EmailDeliveryLog> failedLogs = emailLogRepository.findByCycleAndStatus(cycle, EmailDeliveryStatus.FAILED);
        if (failedLogs.isEmpty()) {
            log.info("No failed email logs to retry for cycle {}", cycleId);
            return emailLogRepository.findByCycleOrderBySentAtDesc(cycle).stream().map(this::toResponse).toList();
        }

        List<Shift> shifts = shiftRepository.findByActiveTrueOrderByIdAsc();
        RosterCycleResponse cycleResponse = toCycleResponse(cycle);

        byte[] excelBytes = null;
        byte[] imageBytes = null;
        try {
            excelBytes = RosterExcelExporter.exportToExcel(cycleResponse, shifts);
            imageBytes = RosterImageExporter.exportToImage(cycleResponse, shifts);
        } catch (Exception e) {
            log.error("Failed to generate attachments during email retry for cycle {}", cycleId, e);
        }

        Map<Long, List<RosterAssignmentResponse>> empAssignments = cycleResponse.assignments() != null
                ? cycleResponse.assignments().stream().collect(Collectors.groupingBy(RosterAssignmentResponse::employeeId))
                : Map.of();

        List<EmailDeliveryLogResponse> results = new ArrayList<>();

        for (EmailDeliveryLog failed : failedLogs) {
            Employee emp = failed.getEmployee();
            List<RosterAssignmentResponse> myShifts = empAssignments.getOrDefault(emp.getId(), List.of());
            EmailDeliveryLog newLog = sendToEmployee(cycle, emp, myShifts, shifts, excelBytes, imageBytes, failed.getMode());

            failed.setStatus(newLog.getStatus());
            failed.setSentAt(newLog.getSentAt());
            failed.setErrorMessage(newLog.getErrorMessage());
            emailLogRepository.save(failed);
            results.add(toResponse(failed));
        }

        return results;
    }

    @Transactional(readOnly = true)
    public List<EmailDeliveryLogResponse> getEmailLogs(Long cycleId) {
        RosterCycle cycle = cycleRepository.findById(cycleId)
                .orElseThrow(() -> new IllegalArgumentException("Roster cycle not found with id: " + cycleId));
        return emailLogRepository.findByCycleOrderBySentAtDesc(cycle).stream().map(this::toResponse).toList();
    }

    private EmailDeliveryLog sendToEmployee(RosterCycle cycle,
                                           Employee emp,
                                           List<RosterAssignmentResponse> myShifts,
                                           List<Shift> shifts,
                                           byte[] excelBytes,
                                           byte[] imageBytes,
                                           GenerationMode mode) {
        EmailType targetEmailType = (mode == GenerationMode.AUTOMATIC && (cycle.getStatus() == com.weeklyroster.entity.RosterStatus.TENTATIVE || cycle.getStatus() == com.weeklyroster.entity.RosterStatus.GENERATED))
                ? EmailType.TENTATIVE_ROSTER
                : ((cycle.getStatus() == com.weeklyroster.entity.RosterStatus.FINAL || cycle.getStatus() == com.weeklyroster.entity.RosterStatus.LOCKED)
                ? EmailType.FINAL_ROSTER
                : EmailType.WEEKLY_ROSTER_DISTRIBUTION);

        if (mode == GenerationMode.AUTOMATIC) {
            List<EmailDeliveryLog> sentForEmp = emailLogRepository.findByCycleAndEmployeeAndEmailTypeAndStatus(cycle, emp, targetEmailType, EmailDeliveryStatus.SENT);
            if (sentForEmp == null || sentForEmp.isEmpty()) {
                List<EmailDeliveryLog> allSent = emailLogRepository.findByCycleAndStatus(cycle, EmailDeliveryStatus.SENT);
                if (allSent != null) {
                    sentForEmp = allSent.stream()
                            .filter(l -> l.getEmployee() != null && l.getEmployee().getId().equals(emp.getId()))
                            .toList();
                }
            }
            if (!sentForEmp.isEmpty()) {
                log.info("[WRMS EMAIL] Employee {} <{}> already received {} email for cycle #{} ({} -> {}). Skipping duplicate dispatch.",
                        emp.getEmployeeCode(), emp.getEmail(), targetEmailType, cycle.getId(), cycle.getStartDate(), cycle.getEndDate());
                return sentForEmp.get(0);
            }
        }

        EmailDeliveryLog deliveryLog = new EmailDeliveryLog();
        deliveryLog.setCycle(cycle);
        deliveryLog.setEmployee(emp);
        deliveryLog.setRecipientEmail(emp.getEmail() != null && !emp.getEmail().isBlank()
                ? emp.getEmail()
                : emp.getEmployeeCode().toLowerCase() + "@company.com");
        deliveryLog.setSentAt(LocalDateTime.now());
        deliveryLog.setMode(mode != null ? mode : GenerationMode.MANUAL);

        EmailType emailType = (mode == GenerationMode.AUTOMATIC && (cycle.getStatus() == com.weeklyroster.entity.RosterStatus.TENTATIVE || cycle.getStatus() == com.weeklyroster.entity.RosterStatus.GENERATED))
                ? EmailType.TENTATIVE_ROSTER
                : ((cycle.getStatus() == com.weeklyroster.entity.RosterStatus.FINAL || cycle.getStatus() == com.weeklyroster.entity.RosterStatus.LOCKED)
                ? EmailType.FINAL_ROSTER
                : EmailType.WEEKLY_ROSTER_DISTRIBUTION);

        deliveryLog.setEmailType(emailType);

        String subject;
        String emailBody;
        String personalSchedule = buildPersonalSchedule(cycle.getStartDate(), cycle.getEndDate(), myShifts, shifts);
        String dateRange = cycle.getStartDate().format(DISPLAY_DATE_FMT) + " to " + cycle.getEndDate().format(DISPLAY_DATE_FMT);

        if (emailType == EmailType.TENTATIVE_ROSTER) {
            subject = "TENTATIVE WRMS Weekly Roster — " + dateRange + " — SUBJECT TO CHANGE";
            emailBody = "=======================================================\n"
                    + "  🟠 TENTATIVE ROSTER — SUBJECT TO CHANGE\n"
                    + "=======================================================\n\n"
                    + "Dear " + emp.getFirstName() + " " + emp.getLastName() + ",\n\n"
                    + "This is a tentative roster for the upcoming cycle (" + dateRange + ").\n"
                    + "Employees may review their assigned shifts and submit eligible change/leave requests before the review deadline.\n"
                    + "Review Deadline: Sunday 4:00 PM IST.\n"
                    + "The final roster will be issued after the review period.\n\n"
                    + "YOUR TENTATIVE SCHEDULE:\n"
                    + personalSchedule + "\n"
                    + "STATUS: TENTATIVE — SUBJECT TO CHANGE\n\n"
                    + "To submit shift preferences or leave requests, please visit the WRMS Employee Portal.\n\n"
                    + "Attached:\n"
                    + "  1. Tentative Weekly Roster (.xlsx)\n"
                    + "  2. Tentative Schedule Card (.png)\n\n"
                    + "Best regards,\n"
                    + "Weekly Roster Management System (WRMS)";
        } else if (emailType == EmailType.FINAL_ROSTER) {
            subject = "FINAL WRMS Weekly Roster — " + dateRange + " — LOCKED";
            emailBody = "=======================================================\n"
                    + "  🟢 FINAL ROSTER — LOCKED\n"
                    + "=======================================================\n\n"
                    + "Dear " + emp.getFirstName() + " " + emp.getLastName() + ",\n\n"
                    + "This is the final roster for the upcoming week (" + dateRange + ").\n"
                    + "The roster has been finalized after the employee review period and approved changes.\n"
                    + "No further changes will be accepted through the normal employee request workflow.\n\n"
                    + "YOUR FINAL LOCKED SCHEDULE:\n"
                    + personalSchedule + "\n"
                    + "STATUS: FINAL — LOCKED\n"
                    + "Finalized At: Sunday 4:00 PM IST\n\n"
                    + "Attached:\n"
                    + "  1. Final Weekly Roster (.xlsx)\n"
                    + "  2. Final Schedule Card (.png)\n\n"
                    + "Best regards,\n"
                    + "Weekly Roster Management System (WRMS)";
        } else {
            subject = "WRMS Weekly Roster — " + dateRange;
            emailBody = "Dear " + emp.getFirstName() + " " + emp.getLastName() + ",\n\n"
                    + "Here is your duty roster for the weekly cycle (" + dateRange + "):\n\n"
                    + personalSchedule + "\n"
                    + "Attached please find:\n"
                    + "  1. Complete Weekly Roster Spreadsheet (.xlsx)\n"
                    + "  2. Complete Weekly Roster Schedule Card (.png)\n\n"
                    + "Best regards,\n"
                    + "Weekly Roster Management System (WRMS)";
        }

        log.info("Preparing weekly roster email for {} <{}> (Subject: '{}')",
                emp.getFirstName() + " " + emp.getLastName(), deliveryLog.getRecipientEmail(), subject);

        if (mailSender != null && mailPassword != null && !mailPassword.isBlank()) {
            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
                String from = (mailUsername != null && !mailUsername.isBlank()) ? mailUsername : "rajatkumarmaury@gmail.com";
                helper.setFrom(from);
                helper.setTo(deliveryLog.getRecipientEmail());
                helper.setSubject(subject);
                helper.setText(emailBody, false);

                String dateRangeStr = cycle.getStartDate().toString() + "_to_" + cycle.getEndDate().toString();
                if (excelBytes != null && excelBytes.length > 0) {
                    helper.addAttachment("WRMS_Roster_" + dateRangeStr + ".xlsx",
                            new ByteArrayResource(excelBytes),
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                }
                if (imageBytes != null && imageBytes.length > 0) {
                    helper.addAttachment("WRMS_Roster_" + dateRangeStr + ".png",
                            new ByteArrayResource(imageBytes),
                            "image/png");
                }

                mailSender.send(message);
                log.info("Weekly roster email successfully delivered via Gmail SMTP to {}", deliveryLog.getRecipientEmail());
                deliveryLog.setStatus(EmailDeliveryStatus.SENT);
                deliveryLog.setErrorMessage(null);
            } catch (Exception ex) {
                log.error("Failed to send roster email via SMTP to {}: {}", deliveryLog.getRecipientEmail(), ex.getMessage());
                deliveryLog.setStatus(EmailDeliveryStatus.FAILED);
                deliveryLog.setErrorMessage(ex.getMessage() != null ? ex.getMessage() : "SMTP delivery failed");
            }
        } else {
            String errorMsg = (mailPassword == null || mailPassword.isBlank())
                    ? "EMAIL_NOT_CONFIGURED: MAIL_APP_PASSWORD is not configured in Railway environment variables."
                    : "EMAIL_NOT_CONFIGURED: JavaMailSender bean is not initialized.";
            log.warn("[WRMS EMAIL] {}", errorMsg);
            deliveryLog.setStatus(EmailDeliveryStatus.FAILED);
            deliveryLog.setErrorMessage(errorMsg);
        }

        java.time.ZoneId istZone = java.time.ZoneId.of("Asia/Kolkata");
        java.time.ZonedDateTime nowIst = java.time.ZonedDateTime.now(istZone);
        java.time.ZonedDateTime nowUtc = nowIst.withZoneSameInstant(java.time.ZoneId.of("UTC"));
        String instanceInfo = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();

        log.info("[WRMS EMAIL]\n" +
                 "  Time: {} IST (UTC: {})\n" +
                 "  Trigger: {}\n" +
                 "  Instance: {}\n" +
                 "  Cycle: {} -> {}\n" +
                 "  Recipient: {}\n" +
                 "  Email Type: WEEKLY_ROSTER_DISTRIBUTION\n" +
                 "  Status: {}",
                nowIst.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                nowUtc.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                mode == GenerationMode.AUTOMATIC ? "WeeklyRosterScheduler" : "AdminManual",
                instanceInfo,
                cycle.getStartDate(), cycle.getEndDate(),
                deliveryLog.getRecipientEmail(),
                deliveryLog.getStatus());

        return deliveryLog;
    }

    /**
     * Admin-controlled Gmail SMTP configuration test.
     */
    public Map<String, Object> sendTestEmail(String toEmail) {
        String from = (mailUsername != null && !mailUsername.isBlank()) ? mailUsername : "rajatkumarmaury@gmail.com";
        String recipient = (toEmail != null && !toEmail.isBlank()) ? toEmail : from;
        String subject = "WRMS SMTP Test";
        String body = "WRMS Gmail SMTP configuration is working successfully.";

        if (mailPassword == null || mailPassword.isBlank()) {
            String notice = "SMTP TEST BLOCKED — MAIL_USERNAME or MAIL_APP_PASSWORD is not configured.";
            log.warn("Gmail SMTP test email BLOCKED: {}", notice);
            return Map.of(
                    "status", "BLOCKED",
                    "sender", from,
                    "recipient", recipient,
                    "subject", subject,
                    "message", notice,
                    "timestamp", LocalDateTime.now().toString()
            );
        }

        if (mailSender == null) {
            return Map.of(
                    "status", "FAILED",
                    "sender", from,
                    "recipient", recipient,
                    "subject", subject,
                    "message", "JavaMailSender bean is not available",
                    "timestamp", LocalDateTime.now().toString()
            );
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(body, false);
            mailSender.send(message);
            log.info("Gmail SMTP test email successfully sent to {}", recipient);
            return Map.of(
                    "status", "SENT",
                    "sender", from,
                    "recipient", recipient,
                    "subject", subject,
                    "message", "Test email successfully delivered via Gmail SMTP",
                    "timestamp", LocalDateTime.now().toString()
            );
        } catch (Exception ex) {
            log.error("Failed to send test email via Gmail SMTP to {}: {}", recipient, ex.getMessage(), ex);
            return Map.of(
                    "status", "FAILED",
                    "sender", from,
                    "recipient", recipient,
                    "subject", subject,
                    "error", ex.getMessage() != null ? ex.getMessage() : "SMTP dispatch failed",
                    "timestamp", LocalDateTime.now().toString()
            );
        }
    }

    public String buildPersonalSchedule(LocalDate start, LocalDate end, List<RosterAssignmentResponse> myShifts, List<Shift> shifts) {
        StringBuilder sb = new StringBuilder();
        Map<LocalDate, RosterAssignmentResponse> map = myShifts.stream()
                .collect(Collectors.toMap(RosterAssignmentResponse::rosterDate, a -> a, (a, b) -> a));

        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            String dayName = d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            RosterAssignmentResponse a = map.get(d);
            String shiftInfo;
            if (a == null) {
                shiftInfo = "OFF";
            } else if (a.onLeave()) {
                shiftInfo = "ON LEAVE";
            } else if (a.weeklyOff()) {
                shiftInfo = "WEEKLY OFF";
            } else if (a.shiftType() != null) {
                shiftInfo = a.shiftType().name() + " (" + getTimingString(shifts, a.shiftType()) + ")";
            } else {
                shiftInfo = "OFF";
            }
            sb.append("  * ").append(dayName).append(" (").append(d.format(DISPLAY_DATE_FMT)).append("): ").append(shiftInfo).append("\n");
        }
        return sb.toString();
    }

    private String getTimingString(List<Shift> shifts, com.weeklyroster.entity.ShiftType type) {
        if (shifts != null) {
            for (Shift s : shifts) {
                if (s.getShiftType() == type && s.isActive()) {
                    if (s.getStartTime() != null && s.getEndTime() != null) {
                        String suffix = s.isOvernight() ? " next day" : "";
                        return s.getStartTime() + "–" + s.getEndTime() + suffix;
                    }
                }
            }
        }
        return switch (type) {
            case MORNING -> "07:00–15:00";
            case GENERAL -> "09:30–18:00";
            case EVENING -> "14:00–22:00";
            case NIGHT -> "22:00–07:00 next day";
            default -> "No working hours";
        };
    }

    private RosterCycleResponse toCycleResponse(RosterCycle cycle) {
        List<RosterAssignmentResponse> assignments = assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(cycle)
                .stream()
                .map(a -> new RosterAssignmentResponse(
                        a.getId(),
                        cycle.getId(),
                        a.getRosterDate(),
                        a.getEmployee().getId(),
                        a.getEmployee().getEmployeeCode(),
                        a.getEmployee().getFirstName() + " " + a.getEmployee().getLastName(),
                        a.getEmployee().getGender(),
                        a.getShift().getShiftType(),
                        a.isWeeklyOff(),
                        a.isOnLeave(),
                        a.isOverridden()
                )).toList();

        return new RosterCycleResponse(
                cycle.getId(),
                cycle.getStartDate(),
                cycle.getEndDate(),
                cycle.getGeneratedAt(),
                cycle.getGenerationMode(),
                "SENT",
                assignments,
                null
        );
    }

    private EmailDeliveryLogResponse toResponse(EmailDeliveryLog log) {
        String empName = log.getEmployee() != null ? log.getEmployee().getFirstName() + " " + log.getEmployee().getLastName() : "Unknown";
        String empCode = log.getEmployee() != null ? log.getEmployee().getEmployeeCode() : "N/A";
        return new EmailDeliveryLogResponse(
                log.getId(),
                log.getCycle().getId(),
                log.getEmployee() != null ? log.getEmployee().getId() : null,
                empCode,
                empName,
                log.getRecipientEmail(),
                log.getSentAt() != null ? log.getSentAt().toString() : null,
                log.getStatus(),
                log.getErrorMessage(),
                log.getMode()
        );
    }
}
