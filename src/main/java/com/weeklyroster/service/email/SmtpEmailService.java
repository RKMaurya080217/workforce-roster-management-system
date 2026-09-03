package com.weeklyroster.service.email;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * SMTP FALLBACK — retained intentionally for future Railway Pro / SMTP-capable deployment.
 * Disabled by default on Railway Trial/Hobby where outbound SMTP ports are blocked.
 * Can be activated anytime by setting environment variable: EMAIL_PROVIDER=SMTP.
 */
@Service
public class SmtpEmailService implements EmailProvider {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailService.class);
    private static final String PROVIDER_NAME = "SMTP";

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:${MAIL_USERNAME:${SPRING_MAIL_USERNAME:rajatkumarmaury@gmail.com}}}")
    private String mailUsername;

    @Value("${spring.mail.password:${MAIL_APP_PASSWORD:${SPRING_MAIL_PASSWORD:${MAIL_PASSWORD:}}}}")
    private String mailPassword;

    @Autowired
    public SmtpEmailService(@Autowired(required = false) JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isConfigured() {
        return mailSender != null && mailPassword != null && !mailPassword.isBlank();
    }

    public String getMailUsername() {
        return mailUsername;
    }

    @Override
    public EmailDeliveryResult sendEmail(EmailMessage message) {
        if (!isConfigured()) {
            String err = (mailPassword == null || mailPassword.isBlank())
                    ? "EMAIL_NOT_CONFIGURED: MAIL_APP_PASSWORD is not configured in environment variables."
                    : "EMAIL_NOT_CONFIGURED: JavaMailSender bean is not initialized.";
            log.warn("[WRMS EMAIL] Provider=SMTP recipient={} status=FAILED reason={}", maskEmail(message.getToEmail()), err);
            return EmailDeliveryResult.failure(PROVIDER_NAME, err, 401);
        }

        try {
            log.info("[WRMS EMAIL] [FALLBACK] Sending email via Gmail SMTP to {}", maskEmail(message.getToEmail()));
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            String from = (message.getFromEmail() != null && !message.getFromEmail().isBlank())
                    ? message.getFromEmail()
                    : ((mailUsername != null && !mailUsername.isBlank()) ? mailUsername : "rajatkumarmaury@gmail.com");

            helper.setFrom(from);
            helper.setTo(message.getToEmail());
            helper.setSubject(message.getSubject() != null ? message.getSubject() : "WRMS Notification");

            if (message.getHtmlBody() != null && !message.getHtmlBody().isBlank()) {
                helper.setText(message.getTextBody() != null ? message.getTextBody() : "", message.getHtmlBody());
            } else {
                helper.setText(message.getTextBody() != null ? message.getTextBody() : "", false);
            }

            if (message.getAttachments() != null) {
                for (EmailAttachment att : message.getAttachments()) {
                    if (att != null && att.getData() != null && att.getData().length > 0) {
                        helper.addAttachment(att.getFilename(), new ByteArrayResource(att.getData()), att.getContentType());
                    }
                }
            }

            mailSender.send(mimeMessage);
            log.info("[WRMS EMAIL] Provider=SMTP recipient={} status=SENT", maskEmail(message.getToEmail()));
            return EmailDeliveryResult.success(PROVIDER_NAME, null);

        } catch (Exception ex) {
            log.error("[WRMS EMAIL] Provider=SMTP recipient={} status=FAILED reason={}", maskEmail(message.getToEmail()), ex.getMessage());
            return EmailDeliveryResult.failure(PROVIDER_NAME, ex.getMessage() != null ? ex.getMessage() : "SMTP delivery failed", 500);
        }
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) return "unknown";
        int atIdx = email.indexOf('@');
        if (atIdx <= 2) return "***" + email.substring(Math.max(0, atIdx));
        return email.substring(0, 2) + "***" + email.substring(atIdx);
    }
}
