package com.weeklyroster.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Safe startup diagnostic logger for WRMS Transactional Email Provider Configuration.
 * Displays active provider (BREVO HTTPS vs SMTP Fallback) and configuration readiness without ever leaking secrets.
 */
@Component
@Order(2)
public class EmailStartupDiagnosticLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmailStartupDiagnosticLogger.class);

    @Value("${email.provider:${EMAIL_PROVIDER:BREVO}}")
    private String emailProvider;

    @Value("${brevo.api.key:${BREVO_API_KEY:}}")
    private String brevoApiKey;

    @Value("${brevo.sender.email:${BREVO_SENDER_EMAIL:${spring.mail.username:${MAIL_USERNAME:rajatkumarmaury@gmail.com}}}}")
    private String brevoSenderEmail;

    @Value("${brevo.sender.name:${BREVO_SENDER_NAME:WRMS}}")
    private String brevoSenderName;

    @Value("${brevo.api.url:${brevo.api.base-url:${BREVO_API_BASE_URL:https://api.brevo.com}}}")
    private String brevoApiUrl;

    @Value("${spring.mail.host:smtp.gmail.com}")
    private String mailHost;

    @Value("${spring.mail.port:587}")
    private String mailPort;

    @Value("${spring.mail.username:${MAIL_USERNAME:${SPRING_MAIL_USERNAME:rajatkumarmaury@gmail.com}}}")
    private String mailUsername;

    @Value("${spring.mail.password:${MAIL_APP_PASSWORD:${SPRING_MAIL_PASSWORD:${MAIL_PASSWORD:}}}}")
    private String mailPassword;

    @Value("${roster.auto-email.enabled:true}")
    private boolean autoEmailEnabled;

    private String resolveBrevoEndpoint() {
        if (brevoApiUrl == null || brevoApiUrl.isBlank()) {
            return "https://api.brevo.com/v3/smtp/email";
        }
        String trimmed = brevoApiUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/v3/smtp/email")) {
            return trimmed;
        }
        return trimmed + "/v3/smtp/email";
    }

    @Override
    public void run(ApplicationArguments args) {
        String activeProvider = "SMTP".equalsIgnoreCase(emailProvider != null ? emailProvider.trim() : "") ? "SMTP" : "BREVO";
        boolean hasBrevoKey = brevoApiKey != null && !brevoApiKey.isBlank();
        boolean hasSmtpPass = mailPassword != null && !mailPassword.isBlank();

        log.info("================================================================================");
        log.info("  [WRMS TRANSACTIONAL EMAIL SYSTEM DIAGNOSTICS]");
        log.info("  Active Email Provider    : {}", activeProvider);
        log.info("  Auto-Email Distribution  : {}", autoEmailEnabled);
        log.info("--------------------------------------------------------------------------------");
        log.info("  [PRIMARY] Brevo HTTPS REST API:");
        log.info("    API Endpoint           : {}", resolveBrevoEndpoint());
        log.info("    API Key Configured     : {}", hasBrevoKey ? "true (BREVO_API_KEY loaded)" : "false (MISSING - add BREVO_API_KEY in Railway variables)");
        log.info("    Sender Email           : {}", brevoSenderEmail);
        log.info("    Sender Name            : {}", brevoSenderName);
        log.info("--------------------------------------------------------------------------------");
        log.info("  [FALLBACK] Gmail SMTP (Retained for future SMTP-capable environments):");
        log.info("    SMTP Host / Port       : {}:{}", mailHost, mailPort);
        log.info("    SMTP User Configured   : {}", mailUsername);
        log.info("    SMTP Password Config   : {}", hasSmtpPass ? "true" : "false");
        log.info("    SMTP Fallback Enabled  : {}", activeProvider.equals("SMTP"));
        log.info("--------------------------------------------------------------------------------");

        if (activeProvider.equals("BREVO") && !hasBrevoKey) {
            log.warn("  [ACTION REQUIRED] BREVO_API_KEY is not set in Railway environment variables.");
            log.warn("  Please add variable 'BREVO_API_KEY' with your Brevo API key in Railway Dashboard -> Service Variables.");
        } else if (activeProvider.equals("SMTP") && !hasSmtpPass) {
            log.warn("  [ACTION REQUIRED] MAIL_APP_PASSWORD is not set in Railway environment variables.");
        }

        log.info("================================================================================");
    }
}
