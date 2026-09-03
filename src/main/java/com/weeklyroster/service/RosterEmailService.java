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
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.export.RosterExcelExporter;
import com.weeklyroster.export.RosterImageExporter;
import com.weeklyroster.repository.EmailDeliveryLogRepository;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.ShiftRepository;
import com.weeklyroster.service.email.EmailAttachment;
import com.weeklyroster.service.email.EmailDeliveryResult;
import com.weeklyroster.service.email.EmailMessage;
import com.weeklyroster.service.email.EmailService;
import com.weeklyroster.service.email.BrevoEmailService;
import com.weeklyroster.service.email.SmtpEmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RosterEmailService {

    private static final Logger log = LoggerFactory.getLogger(RosterEmailService.class);
    private static final DateTimeFormatter DISPLAY_DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private final EmailDeliveryLogRepository emailLogRepository;
    private final EmployeeRepository employeeRepository;
    private final RosterCycleRepository cycleRepository;
    private final RosterAssignmentRepository assignmentRepository;
    private final ShiftRepository shiftRepository;
    private final EmailService emailService;

    @Value("${roster.auto-email.enabled:true}")
    private boolean autoEmailEnabled;

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
                              EmailService emailService) {
        this.emailLogRepository = emailLogRepository;
        this.employeeRepository = employeeRepository;
        this.cycleRepository = cycleRepository;
        this.assignmentRepository = assignmentRepository;
        this.shiftRepository = shiftRepository;
        this.emailService = emailService != null ? emailService : new EmailService(new BrevoEmailService(), new SmtpEmailService(null));
    }

    public RosterEmailService(EmailDeliveryLogRepository emailLogRepository,
                              EmployeeRepository employeeRepository,
                              RosterCycleRepository cycleRepository,
                              RosterAssignmentRepository assignmentRepository,
                              ShiftRepository shiftRepository) {
        this(emailLogRepository, employeeRepository, cycleRepository, assignmentRepository, shiftRepository,
                new EmailService(new BrevoEmailService(), new SmtpEmailService(null)));
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
            log.warn("[WRMS EMAIL] Email distribution rejected: system shutdown in progress.");
            return List.of();
        }

        if (mode == GenerationMode.AUTOMATIC && !isImmediateUpcomingWeek(cycle.getStartDate(), cycle.getEndDate())) {
            log.warn("[WRMS EMAIL] Automatic email distribution skipped for cycle #{} ({} to {}). "
                    + "Automatic distribution is STRICTLY RESTRICTED to the immediate upcoming week.",
                    cycle.getId(), cycle.getStartDate(), cycle.getEndDate());
            return List.of();
        }

        log.info("[WRMS EMAIL] Distributing weekly roster emails for cycle #{} ({} to {}) in mode {}...",
                cycle.getId(), cycle.getStartDate(), cycle.getEndDate(), mode);

        List<Employee> activeEmployees = employeeRepository.findByActiveTrueOrderByIdAsc();
        List<Shift> shifts = shiftRepository.findByActiveTrueOrderByIdAsc();

        byte[] excelBytes = null;
        try {
            excelBytes = RosterExcelExporter.exportToExcel(cycleResponse, shifts);
        } catch (Exception ex) {
            log.error("Failed to generate roster Excel attachment: {}", ex.getMessage());
        }

        byte[] imageBytes = null;
        try {
            imageBytes = RosterImageExporter.exportToImage(cycleResponse, shifts);
        } catch (Exception ex) {
            log.error("Failed to generate roster PNG attachment: {}", ex.getMessage());
        }

        Map<Long, List<RosterAssignmentResponse>> assignmentsByEmp = cycleResponse.assignments() != null
                ? cycleResponse.assignments().stream().collect(Collectors.groupingBy(RosterAssignmentResponse::employeeId))
                : Map.of();

        List<EmailDeliveryLogResponse> results = new ArrayList<>();

        for (Employee emp : activeEmployees) {
            List<RosterAssignmentResponse> myShifts = assignmentsByEmp.getOrDefault(emp.getId(), List.of());
            EmailDeliveryLog deliveryLog = sendToEmployee(cycle, emp, myShifts, shifts, excelBytes, imageBytes, mode);
            results.add(toResponse(deliveryLog));
        }

        return results;
    }

    @Transactional
    public List<EmailDeliveryLogResponse> retryFailedEmails(Long cycleId) {
        RosterCycle cycle = cycleRepository.findById(cycleId).orElse(null);
        if (cycle == null) {
            log.warn("Cannot retry emails: cycle #{} not found", cycleId);
            return List.of();
        }

        List<EmailDeliveryLog> failedLogs = emailLogRepository.findByCycleAndStatus(cycle, EmailDeliveryStatus.FAILED);
        if (failedLogs.isEmpty()) {
            log.info("No failed email delivery logs found for cycle #{}", cycleId);
            return List.of();
        }

        log.info("Retrying {} failed email deliveries for cycle #{}...", failedLogs.size(), cycleId);
        List<Shift> shifts = shiftRepository.findByActiveTrueOrderByIdAsc();

        RosterCycleResponse cycleResponse = buildCycleResponse(cycle);
        byte[] excelBytes = null;
        try {
            excelBytes = RosterExcelExporter.exportToExcel(cycleResponse, shifts);
        } catch (Exception ex) {
            log.error("Failed to generate roster Excel attachment for retry: {}", ex.getMessage());
        }

        byte[] imageBytes = null;
        try {
            imageBytes = RosterImageExporter.exportToImage(cycleResponse, shifts);
        } catch (Exception ex) {
            log.error("Failed to generate roster PNG attachment for retry: {}", ex.getMessage());
        }

        Map<Long, List<RosterAssignmentResponse>> assignmentsByEmp = cycleResponse.assignments() != null
                ? cycleResponse.assignments().stream().collect(Collectors.groupingBy(RosterAssignmentResponse::employeeId))
                : Map.of();

        List<EmailDeliveryLogResponse> retryResults = new ArrayList<>();

        for (EmailDeliveryLog failedLog : failedLogs) {
            Employee emp = failedLog.getEmployee();
            if (emp == null || !emp.isActive()) {
                continue;
            }

            List<RosterAssignmentResponse> myShifts = assignmentsByEmp.getOrDefault(emp.getId(), List.of());
            EmailDeliveryLog retried = sendToEmployee(cycle, emp, myShifts, shifts, excelBytes, imageBytes, failedLog.getMode());
            retryResults.add(toResponse(retried));
        }

        return retryResults;
    }

    @Transactional(readOnly = true)
    public List<EmailDeliveryLogResponse> getEmailLogs(Long cycleId) {
        RosterCycle cycle = cycleRepository.findById(cycleId).orElse(null);
        if (cycle == null) {
            return List.of();
        }
        return emailLogRepository.findByCycleOrderBySentAtDesc(cycle)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private EmailDeliveryLog sendToEmployee(RosterCycle cycle, Employee emp,
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

        // Build HTML template
        String htmlBody = buildHtmlEmailTemplate(emailType, emp, dateRange, personalSchedule);

        // Build EmailMessage
        EmailMessage.Builder msgBuilder = EmailMessage.builder()
                .to(deliveryLog.getRecipientEmail(), emp.getFirstName() + " " + emp.getLastName())
                .from(mailUsername != null && !mailUsername.isBlank() ? mailUsername : "rajatkumarmaury@gmail.com", "WRMS")
                .subject(subject)
                .textBody(emailBody)
                .htmlBody(htmlBody);

        String dateRangeStr = cycle.getStartDate().toString() + "_to_" + cycle.getEndDate().toString();
        if (excelBytes != null && excelBytes.length > 0) {
            msgBuilder.addAttachment(new EmailAttachment(
                    "WRMS_Roster_" + dateRangeStr + ".xlsx",
                    excelBytes,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            ));
        }
        if (imageBytes != null && imageBytes.length > 0) {
            msgBuilder.addAttachment(new EmailAttachment(
                    "WRMS_Roster_" + dateRangeStr + ".png",
                    imageBytes,
                    "image/png"
            ));
        }

        EmailDeliveryResult result = emailService.sendEmail(msgBuilder.build());

        if (result.isSuccess()) {
            deliveryLog.setStatus(EmailDeliveryStatus.SENT);
            deliveryLog.setErrorMessage(null);
        } else {
            deliveryLog.setStatus(EmailDeliveryStatus.FAILED);
            deliveryLog.setErrorMessage(result.getErrorMessage() != null ? result.getErrorMessage() : "Email delivery failed");
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

        return emailLogRepository.save(deliveryLog);
    }

    private String buildHtmlEmailTemplate(EmailType emailType, Employee emp, String dateRange, String personalSchedule) {
        String badgeColor = "#2563eb";
        String badgeTitle = "WEEKLY DUTY ROSTER";
        String statusNote = "Please review your scheduled shifts below.";

        if (emailType == EmailType.TENTATIVE_ROSTER) {
            badgeColor = "#d97706";
            badgeTitle = "🟠 TENTATIVE ROSTER — SUBJECT TO CHANGE";
            statusNote = "This is a tentative schedule for employee review. Review deadline: <strong>Sunday 4:00 PM IST</strong>.";
        } else if (emailType == EmailType.FINAL_ROSTER) {
            badgeColor = "#16a34a";
            badgeTitle = "🟢 FINAL ROSTER — LOCKED";
            statusNote = "This is the final locked schedule. Approved changes have been applied.";
        }

        String scheduleHtml = personalSchedule.replace("\n", "<br/>");

        return "<!DOCTYPE html><html><head><meta charset='UTF-8'><style>"
                + "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;margin:0;padding:20px;background-color:#f8fafc;color:#1e293b;}"
                + ".container{max-width:600px;margin:0 auto;background:#ffffff;border-radius:10px;overflow:hidden;border:1px solid #e2e8f0;box-shadow:0 4px 6px -1px rgba(0,0,0,0.05);}"
                + ".header{background:#0f172a;padding:24px 28px;text-align:center;color:#ffffff;}"
                + ".header h1{margin:0;font-size:20px;font-weight:700;letter-spacing:-0.5px;}"
                + ".header p{margin:6px 0 0 0;font-size:13px;color:#94a3b8;}"
                + ".badge-banner{background:" + badgeColor + ";color:#ffffff;padding:10px 20px;text-align:center;font-weight:700;font-size:13px;letter-spacing:0.5px;}"
                + ".content{padding:28px;}"
                + ".greeting{font-size:16px;font-weight:600;margin-bottom:14px;color:#0f172a;}"
                + ".notice-box{background:#f1f5f9;border-left:4px solid " + badgeColor + ";padding:12px 16px;border-radius:4px;margin-bottom:20px;font-size:13px;line-height:1.5;}"
                + ".schedule-card{background:#fafafa;border:1px solid #e5e7eb;border-radius:8px;padding:18px;margin:20px 0;font-family:monospace;font-size:13px;line-height:1.6;color:#334155;}"
                + ".attachments-box{background:#f8fafc;border:1px dashed #cbd5e1;border-radius:8px;padding:14px 18px;margin-top:20px;font-size:13px;color:#475569;}"
                + ".footer{background:#f8fafc;padding:20px 28px;border-top:1px solid #e2e8f0;text-align:center;font-size:12px;color:#64748b;}"
                + "</style></head><body>"
                + "<div class='container'>"
                + "<div class='header'><h1>Weekly Roster Management System</h1><p>Cycle: " + dateRange + "</p></div>"
                + "<div class='badge-banner'>" + badgeTitle + "</div>"
                + "<div class='content'>"
                + "<div class='greeting'>Dear " + escapeHtml(emp.getFirstName()) + " " + escapeHtml(emp.getLastName()) + ",</div>"
                + "<div class='notice-box'>" + statusNote + "</div>"
                + "<div class='schedule-card'><strong>YOUR SCHEDULE:</strong><br/><br/>" + scheduleHtml + "</div>"
                + "<div class='attachments-box'><strong>Attached Documents:</strong><br/>• Complete Weekly Roster Spreadsheet (.xlsx)<br/>• Weekly Roster Schedule Card (.png)</div>"
                + "</div>"
                + "<div class='footer'><p>This is an automated notification from Weekly Roster Management System (WRMS).</p></div>"
                + "</div></body></html>";
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * Admin-controlled transactional email test.
     */
    public Map<String, Object> sendTestEmail(String toEmail) {
        String from = (mailUsername != null && !mailUsername.isBlank()) ? mailUsername : "rajatkumarmaury@gmail.com";
        String recipient = (toEmail != null && !toEmail.isBlank()) ? toEmail : from;
        String subject = "WRMS Transactional Email Test";
        String body = "WRMS Transactional Email configuration is working successfully via " + emailService.getActiveProviderName() + ".";
        String html = "<p><strong>WRMS Transactional Email</strong> configuration is working successfully via <code>" + emailService.getActiveProviderName() + "</code>.</p>";

        EmailMessage message = EmailMessage.builder()
                .to(recipient)
                .from(from, "WRMS")
                .subject(subject)
                .textBody(body)
                .htmlBody(html)
                .build();

        EmailDeliveryResult result = emailService.sendEmail(message);

        return Map.of(
                "status", result.isSuccess() ? "SUCCESS" : "FAILED",
                "provider", result.getProvider(),
                "sender", from,
                "recipient", recipient,
                "subject", subject,
                "messageId", result.getMessageId() != null ? result.getMessageId() : "N/A",
                "message", result.isSuccess() ? "Test email sent successfully via " + result.getProvider() : result.getErrorMessage(),
                "timestamp", LocalDateTime.now().toString()
        );
    }

    public String buildPersonalSchedule(LocalDate startDate, LocalDate endDate,
                                         List<RosterAssignmentResponse> myShifts,
                                         List<Shift> allShifts) {
        Map<ShiftType, Shift> shiftMap = allShifts != null ? allShifts.stream()
                .filter(s -> s.getShiftType() != null)
                .collect(Collectors.toMap(Shift::getShiftType, s -> s, (a, b) -> a)) : Map.of();

        Map<LocalDate, RosterAssignmentResponse> map = myShifts != null ? myShifts.stream()
                .filter(a -> a.rosterDate() != null)
                .collect(Collectors.toMap(RosterAssignmentResponse::rosterDate, a -> a, (a, b) -> a)) : Map.of();

        StringBuilder sb = new StringBuilder();
        DateTimeFormatter displayFmt = DateTimeFormatter.ofPattern("dd-MMM-yyyy");
        LocalDate curr = startDate;
        while (!curr.isAfter(endDate)) {
            String dayName = curr.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            String dateStr = curr.format(displayFmt);
            RosterAssignmentResponse assign = map.get(curr);

            if (assign != null) {
                if (assign.onLeave()) {
                    sb.append(String.format("  • %s (%s): ON LEAVE%n", dayName, dateStr));
                } else if (assign.weeklyOff() || assign.shiftType() == ShiftType.OFF) {
                    sb.append(String.format("  • %s (%s): WEEKLY OFF%n", dayName, dateStr));
                } else if (assign.shiftType() != null) {
                    Shift s = shiftMap.get(assign.shiftType());
                    if (s != null && s.getStartTime() != null && s.getEndTime() != null) {
                        String timing = (assign.shiftType() == ShiftType.NIGHT)
                                ? String.format("%s–%s next day", s.getStartTime().toString(), s.getEndTime().toString())
                                : String.format("%s–%s", s.getStartTime().toString(), s.getEndTime().toString());
                        sb.append(String.format("  • %s (%s): %s (%s)%n", dayName, dateStr, assign.shiftType().name(), timing));
                    } else {
                        sb.append(String.format("  • %s (%s): %s%n", dayName, dateStr, assign.shiftType().name()));
                    }
                } else {
                    sb.append(String.format("  • %s (%s): WEEKLY OFF%n", dayName, dateStr));
                }
            } else {
                sb.append(String.format("  • %s (%s): WEEKLY OFF%n", dayName, dateStr));
            }
            curr = curr.plusDays(1);
        }
        return sb.toString();
    }

    private RosterCycleResponse buildCycleResponse(RosterCycle cycle) {
        List<com.weeklyroster.entity.RosterAssignment> assignments = assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(cycle);
        List<RosterAssignmentResponse> assignmentResponses = assignments != null ? assignments.stream().map(a ->
                new RosterAssignmentResponse(
                        a.getId(),
                        a.getCycle() != null ? a.getCycle().getId() : null,
                        a.getRosterDate(),
                        a.getEmployee() != null ? a.getEmployee().getId() : null,
                        a.getEmployee() != null ? a.getEmployee().getEmployeeCode() : "EMP",
                        a.getEmployee() != null ? (a.getEmployee().getFirstName() + " " + a.getEmployee().getLastName()) : "Unknown",
                        a.getEmployee() != null ? a.getEmployee().getGender() : null,
                        a.getShift() != null ? a.getShift().getShiftType() : null,
                        a.isWeeklyOff(),
                        a.isOnLeave(),
                        a.isOverridden(),
                        a.getAssignmentReason()
                )
        ).collect(Collectors.toList()) : List.of();

        return new RosterCycleResponse(
                cycle.getId(),
                cycle.getStartDate(),
                cycle.getEndDate(),
                cycle.getGeneratedAt(),
                cycle.getGenerationMode(),
                cycle.getStatus() != null ? cycle.getStatus().name() : "GENERATED",
                assignmentResponses,
                null
        );
    }

    private EmailDeliveryLogResponse toResponse(EmailDeliveryLog log) {
        return new EmailDeliveryLogResponse(
                log.getId(),
                log.getCycle() != null ? log.getCycle().getId() : null,
                log.getEmployee() != null ? log.getEmployee().getId() : null,
                log.getEmployee() != null ? log.getEmployee().getEmployeeCode() : "EMP",
                log.getEmployee() != null ? (log.getEmployee().getFirstName() + " " + log.getEmployee().getLastName()) : "Unknown",
                log.getRecipientEmail(),
                log.getSentAt() != null ? log.getSentAt().toString() : null,
                log.getStatus(),
                log.getErrorMessage(),
                log.getMode(),
                log.getEmailType()
        );
    }
}
