package com.weeklyroster.service.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central Transactional Email Service Facade for WRMS.
 * Selects between Primary Brevo HTTPS API and Fallback SMTP based on 'email.provider' configuration.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final BrevoEmailService brevoEmailService;
    private final SmtpEmailService smtpEmailService;

    @Value("${email.provider:${EMAIL_PROVIDER:BREVO}}")
    private String configuredProvider;

    @Autowired
    public EmailService(BrevoEmailService brevoEmailService, SmtpEmailService smtpEmailService) {
        this.brevoEmailService = brevoEmailService;
        this.smtpEmailService = smtpEmailService;
    }

    public EmailProvider getActiveProvider() {
        if ("SMTP".equalsIgnoreCase(configuredProvider != null ? configuredProvider.trim() : "")) {
            return smtpEmailService;
        }
        return brevoEmailService;
    }

    public String getActiveProviderName() {
        return getActiveProvider().getProviderName();
    }

    public boolean isConfigured() {
        return getActiveProvider().isConfigured();
    }

    public EmailDeliveryResult sendEmail(EmailMessage message) {
        EmailProvider provider = getActiveProvider();
        log.info("[WRMS EMAIL] Dispatching email via active provider: {}", provider.getProviderName());
        return provider.sendEmail(message);
    }

    public Map<String, Object> getProviderStatus() {
        EmailProvider active = getActiveProvider();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("activeProvider", active.getProviderName());
        status.put("configured", active.isConfigured());
        status.put("brevoAvailable", true);
        status.put("brevoConfigured", brevoEmailService.isConfigured());
        status.put("brevoSenderEmail", brevoEmailService.getDefaultSenderEmail());
        status.put("brevoSenderName", brevoEmailService.getDefaultSenderName());
        status.put("smtpFallbackAvailable", true);
        status.put("smtpFallbackConfigured", smtpEmailService.isConfigured());
        status.put("smtpFallbackEnabled", "SMTP".equalsIgnoreCase(configuredProvider != null ? configuredProvider.trim() : ""));
        return status;
    }
}
