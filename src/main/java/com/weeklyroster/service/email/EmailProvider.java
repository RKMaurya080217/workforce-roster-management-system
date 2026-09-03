package com.weeklyroster.service.email;

/**
 * Uniform email provider interface for transactional email delivery.
 */
public interface EmailProvider {
    String getProviderName();
    boolean isConfigured();
    EmailDeliveryResult sendEmail(EmailMessage message);
}
