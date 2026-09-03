package com.weeklyroster.service.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Primary Production Email Provider for WRMS deployed on Railway.
 * Dispatches transactional emails via Brevo HTTPS REST API (https://api.brevo.com/v3/smtp/email).
 * Avoids outbound SMTP port blocking on Railway Trial/Hobby environments.
 */
@Service
public class BrevoEmailService implements EmailProvider {

    private static final Logger log = LoggerFactory.getLogger(BrevoEmailService.class);
    private static final String PROVIDER_NAME = "BREVO";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${brevo.api.key:${BREVO_API_KEY:}}")
    private String apiKey;

    @Value("${brevo.api.url:${brevo.api.base-url:${BREVO_API_BASE_URL:https://api.brevo.com}}}")
    private String rawApiUrl;

    @Value("${brevo.sender.email:${BREVO_SENDER_EMAIL:${spring.mail.username:${MAIL_USERNAME:rajatkumarmaury@gmail.com}}}}")
    private String defaultSenderEmail;

    @Value("${brevo.sender.name:${BREVO_SENDER_NAME:WRMS}}")
    private String defaultSenderName;

    @Value("${brevo.reply-to:${BREVO_REPLY_TO:}}")
    private String replyToEmail;

    public BrevoEmailService() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), new ObjectMapper());
    }

    public BrevoEmailService(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getDefaultSenderEmail() {
        return defaultSenderEmail;
    }

    public String getDefaultSenderName() {
        return defaultSenderName;
    }

    /**
     * Cleanly resolves the full Brevo transactional email REST endpoint.
     * Handles both base URLs (e.g. "https://api.brevo.com" or "https://api.brevo.com/")
     * and full endpoints ("https://api.brevo.com/v3/smtp/email"), avoiding duplicate paths.
     */
    public String getResolvedEndpointUrl() {
        if (rawApiUrl == null || rawApiUrl.isBlank()) {
            return "https://api.brevo.com/v3/smtp/email";
        }
        String trimmed = rawApiUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/v3/smtp/email")) {
            return trimmed;
        }
        return trimmed + "/v3/smtp/email";
    }

    @Override
    public EmailDeliveryResult sendEmail(EmailMessage message) {
        if (!isConfigured()) {
            String err = "EMAIL_NOT_CONFIGURED: BREVO_API_KEY is not configured in Railway environment variables. Please add BREVO_API_KEY in Railway Dashboard -> Service Variables.";
            log.warn("[WRMS EMAIL] Provider=BREVO recipient={} status=FAILED reason={}", maskEmail(message.getToEmail()), err);
            return EmailDeliveryResult.failure(PROVIDER_NAME, err, 401);
        }

        try {
            Map<String, Object> payload = buildBrevoPayload(message);
            String jsonPayload = objectMapper.writeValueAsString(payload);

            String endpoint = getResolvedEndpointUrl();
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(15))
                    .header("api-key", apiKey.trim())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload));

            HttpRequest request = reqBuilder.build();

            // Execute request with transient error retry (up to 2 retries for 5xx/network errors)
            int attempts = 0;
            int maxAttempts = 3;
            HttpResponse<String> response = null;
            Exception lastException = null;

            while (attempts < maxAttempts) {
                attempts++;
                try {
                    log.info("[WRMS EMAIL] Sending transactional email via BREVO HTTPS API to {} (attempt {}/{})",
                            maskEmail(message.getToEmail()), attempts, maxAttempts);
                    response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                    int statusCode = response.statusCode();
                    if (statusCode == 200 || statusCode == 201) {
                        String messageId = extractMessageId(response.body());
                        log.info("[WRMS EMAIL] Provider=BREVO recipient={} status=SENT messageId={}",
                                maskEmail(message.getToEmail()), messageId);
                        return EmailDeliveryResult.success(PROVIDER_NAME, messageId);
                    } else if (statusCode == 400 || statusCode == 401 || statusCode == 403 || statusCode == 404 || statusCode == 429) {
                        // Client / Auth error -> Do not retry
                        String reason = parseErrorMessage(response.body(), statusCode);
                        log.error("[WRMS EMAIL] Provider=BREVO recipient={} status=FAILED httpStatus={} reason={}",
                                maskEmail(message.getToEmail()), statusCode, reason);
                        return EmailDeliveryResult.failure(PROVIDER_NAME, reason, statusCode);
                    } else {
                        // 5xx Server Error -> retry if attempts remain
                        log.warn("[WRMS EMAIL] Brevo returned 5xx status: {}. Response: {}", statusCode, response.body());
                        if (attempts < maxAttempts) {
                            Thread.sleep(1000L * attempts);
                        }
                    }
                } catch (IOException | InterruptedException ex) {
                    lastException = ex;
                    log.warn("[WRMS EMAIL] Transient network error connecting to Brevo API on attempt {}: {}", attempts, ex.getMessage());
                    if (attempts < maxAttempts) {
                        try {
                            Thread.sleep(1000L * attempts);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            String finalError = response != null
                    ? parseErrorMessage(response.body(), response.statusCode())
                    : (lastException != null ? lastException.getMessage() : "Brevo API connection timeout");

            log.error("[WRMS EMAIL] Provider=BREVO recipient={} status=FAILED reason={}", maskEmail(message.getToEmail()), finalError);
            return EmailDeliveryResult.failure(PROVIDER_NAME, finalError, response != null ? response.statusCode() : 500);

        } catch (Exception e) {
            log.error("[WRMS EMAIL] Unexpected error during Brevo email dispatch: {}", e.getMessage(), e);
            return EmailDeliveryResult.failure(PROVIDER_NAME, e.getMessage(), 500);
        }
    }

    private Map<String, Object> buildBrevoPayload(EmailMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();

        // Sender
        String senderEmail = message.getFromEmail() != null && !message.getFromEmail().isBlank()
                ? message.getFromEmail()
                : defaultSenderEmail;
        String senderName = message.getFromName() != null && !message.getFromName().isBlank()
                ? message.getFromName()
                : defaultSenderName;
        payload.put("sender", Map.of("name", senderName, "email", senderEmail));

        // Recipient
        Map<String, String> recipientMap = new LinkedHashMap<>();
        recipientMap.put("email", message.getToEmail());
        if (message.getToName() != null && !message.getToName().isBlank()) {
            recipientMap.put("name", message.getToName());
        }
        payload.put("to", List.of(recipientMap));

        // Reply To
        String replyTo = message.getReplyTo() != null && !message.getReplyTo().isBlank()
                ? message.getReplyTo()
                : replyToEmail;
        if (replyTo != null && !replyTo.isBlank()) {
            payload.put("replyTo", Map.of("email", replyTo));
        }

        // Subject & Body
        payload.put("subject", message.getSubject() != null ? message.getSubject() : "WRMS Notification");

        if (message.getHtmlBody() != null && !message.getHtmlBody().isBlank()) {
            payload.put("htmlContent", message.getHtmlBody());
        } else if (message.getTextBody() != null && !message.getTextBody().isBlank()) {
            payload.put("htmlContent", "<pre style=\"font-family:sans-serif;white-space:pre-wrap;\">" + escapeHtml(message.getTextBody()) + "</pre>");
        }

        if (message.getTextBody() != null && !message.getTextBody().isBlank()) {
            payload.put("textContent", message.getTextBody());
        }

        // Attachments
        if (message.getAttachments() != null && !message.getAttachments().isEmpty()) {
            List<Map<String, String>> attachmentList = new ArrayList<>();
            for (EmailAttachment att : message.getAttachments()) {
                if (att != null && att.getData() != null && att.getData().length > 0) {
                    attachmentList.add(Map.of(
                            "name", att.getFilename(),
                            "content", att.getBase64Content()
                    ));
                }
            }
            if (!attachmentList.isEmpty()) {
                payload.put("attachment", attachmentList);
            }
        }

        return payload;
    }

    private String extractMessageId(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return "brevo-" + UUID.randomUUID();
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            if (node.has("messageId")) {
                return node.get("messageId").asText();
            }
        } catch (Exception ignored) {}
        return "brevo-" + UUID.randomUUID();
    }

    private String parseErrorMessage(String responseBody, int statusCode) {
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(responseBody);
                if (node.has("message")) {
                    return "Brevo Error (" + statusCode + "): " + node.get("message").asText();
                }
                if (node.has("code")) {
                    return "Brevo Error (" + statusCode + "): " + node.get("code").asText();
                }
            } catch (Exception ignored) {}
        }
        if (statusCode == 401) {
            return "Brevo Authentication Failed (401): Invalid or missing BREVO_API_KEY.";
        }
        if (statusCode == 429) {
            return "Brevo Rate Limit Exceeded (429): Too many requests in short period.";
        }
        return "Brevo API returned HTTP " + statusCode;
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) return "unknown";
        int atIdx = email.indexOf('@');
        if (atIdx <= 2) return "***" + email.substring(Math.max(0, atIdx));
        return email.substring(0, 2) + "***" + email.substring(atIdx);
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
