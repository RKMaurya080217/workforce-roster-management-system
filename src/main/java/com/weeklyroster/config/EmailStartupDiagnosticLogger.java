package com.weeklyroster.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Safe startup diagnostic logger for WRMS Email / Gmail SMTP Configuration.
 * Explicitly displays whether SMTP host, port, username, and password are configured WITHOUT ever logging secret values.
 */
@Component
@Order(2)
public class EmailStartupDiagnosticLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmailStartupDiagnosticLogger.class);

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

    @Override
    public void run(ApplicationArguments args) {
        boolean hasPassword = mailPassword != null && !mailPassword.isBlank();
        boolean hasUsername = mailUsername != null && !mailUsername.isBlank();

        log.info("================================================================================");
        log.info("  [WRMS Email / Gmail SMTP Startup Diagnostics]");
        log.info("  SMTP Host                : {}", mailHost);
        log.info("  SMTP Port                : {}", mailPort);
        log.info("  SMTP Username Configured : {} ({})", hasUsername, hasUsername ? mailUsername : "NOT CONFIGURED");
        log.info("  SMTP Password Configured : {}", hasPassword ? "true (Google App Password loaded)" : "false (MISSING - add MAIL_APP_PASSWORD in Railway variables)");
        log.info("  STARTTLS Enabled         : true");
        log.info("  Auto-Email Distribution  : {}", autoEmailEnabled);
        if (!hasPassword) {
            log.warn("  [ACTION REQUIRED] MAIL_APP_PASSWORD is not set in Railway environment.");
            log.warn("  Please add variable 'MAIL_APP_PASSWORD' with your 16-character Gmail App Password in Railway Dashboard -> Service Variables and redeploy.");
        }
        log.info("================================================================================");
    }
}
