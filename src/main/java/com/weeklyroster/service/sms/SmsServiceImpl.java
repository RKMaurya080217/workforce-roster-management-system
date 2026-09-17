package com.weeklyroster.service.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

@Service
public class SmsServiceImpl implements SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsServiceImpl.class);

    @Value("${sms.enabled:true}")
    private boolean enabled = true;

    @Value("${sms.provider:${SMS_PROVIDER:LOG}}")
    private String provider = "LOG";

    @Value("${sms.api.key:${SMS_API_KEY:}}")
    private String apiKey = "";

    @Value("${sms.api.secret:${SMS_API_SECRET:}}")
    private String apiSecret = "";

    @Value("${sms.sender.id:${SMS_SENDER_ID:WRMS}}")
    private String senderId = "WRMS";

    @Value("${sms.endpoint.url:${SMS_ENDPOINT_URL:}}")
    private String endpointUrl = "";

    private final HttpClient httpClient;

    public SmsServiceImpl() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public SmsServiceImpl(boolean enabled, String provider, String apiKey, String senderId) {
        this.enabled = enabled;
        this.provider = provider != null ? provider : "LOG";
        this.apiKey = apiKey != null ? apiKey : "";
        this.senderId = senderId != null ? senderId : "WRMS";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public boolean isConfigured() {
        if (!enabled) return false;
        if ("LOG".equalsIgnoreCase(provider) || "MOCK".equalsIgnoreCase(provider)) {
            return true;
        }
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String getProviderName() {
        return provider;
    }

    @Override
    public SmsDeliveryResult sendSms(String toPhoneNumber, String message) {
        if (!enabled) {
            log.info("[WRMS SMS] SMS service is disabled by configuration (sms.enabled=false). Dispatch skipped.");
            return SmsDeliveryResult.success("DISABLED", "DISABLED");
        }

        if (toPhoneNumber == null || toPhoneNumber.isBlank()) {
            log.warn("[WRMS SMS] Recipient phone number is empty. SMS dispatch skipped.");
            return SmsDeliveryResult.failure(provider, "Missing recipient phone number");
        }

        String cleanPhone = toPhoneNumber.replaceAll("[^0-9+]", "");
        if (cleanPhone.replaceAll("[^0-9]", "").length() < 10) {
            log.warn("[WRMS SMS] Phone number '{}' has fewer than 10 digits. Invalid number format.", maskPhone(cleanPhone));
            return SmsDeliveryResult.failure(provider, "Invalid phone number length (< 10 digits)");
        }

        String masked = maskPhone(cleanPhone);

        // Fallback / Simulated Logging Mode
        if (!isConfigured() || "LOG".equalsIgnoreCase(provider) || "MOCK".equalsIgnoreCase(provider)) {
            log.info("[WRMS SMS - SIMULATED/{}] Dispatching to {}: \"{}\"",
                    provider.toUpperCase(), masked, message);
            String mockId = "SIM-SMS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            return SmsDeliveryResult.success(mockId, provider.toUpperCase());
        }

        try {
            if ("FAST2SMS".equalsIgnoreCase(provider)) {
                return sendViaFast2Sms(cleanPhone, message, masked);
            } else if ("GENERIC_REST".equalsIgnoreCase(provider) && endpointUrl != null && !endpointUrl.isBlank()) {
                return sendViaGenericWebhook(cleanPhone, message, masked);
            } else {
                log.info("[WRMS SMS - FALLBACK] Provider '{}' without active transport. Simulating to {}: \"{}\"",
                        provider, masked, message);
                return SmsDeliveryResult.success("FALLBACK-" + UUID.randomUUID().toString().substring(0, 8), provider);
            }
        } catch (Exception ex) {
            log.warn("[WRMS SMS] Non-fatal delivery failure to {}: {}", masked, ex.getMessage());
            return SmsDeliveryResult.failure(provider, ex.getMessage());
        }
    }

    private SmsDeliveryResult sendViaFast2Sms(String phone, String message, String masked) throws Exception {
        String targetUrl = (endpointUrl != null && !endpointUrl.isBlank())
                ? endpointUrl
                : "https://www.fast2sms.com/dev/bulkV2";

        String jsonPayload = String.format(
                "{\"route\":\"q\",\"message\":\"%s\",\"language\":\"english\",\"numbers\":\"%s\"}",
                escapeJson(message),
                phone.replaceAll("[^0-9]", "")
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .header("authorization", apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(6))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            log.info("[WRMS SMS - FAST2SMS] Successfully sent SMS to {} (HTTP {})", masked, response.statusCode());
            return SmsDeliveryResult.success("FAST2SMS-" + UUID.randomUUID().toString().substring(0, 8), "FAST2SMS");
        } else {
            log.warn("[WRMS SMS - FAST2SMS] Failed to send SMS to {}: HTTP {} - {}", masked, response.statusCode(), response.body());
            return SmsDeliveryResult.failure("FAST2SMS", "HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    private SmsDeliveryResult sendViaGenericWebhook(String phone, String message, String masked) throws Exception {
        String jsonPayload = String.format(
                "{\"to\":\"%s\",\"message\":\"%s\",\"sender\":\"%s\"}",
                escapeJson(phone),
                escapeJson(message),
                escapeJson(senderId)
        );

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(6))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload));

        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            log.info("[WRMS SMS - GENERIC_REST] Successfully dispatched to {} (HTTP {})", masked, response.statusCode());
            return SmsDeliveryResult.success("WEBHOOK-" + UUID.randomUUID().toString().substring(0, 8), "GENERIC_REST");
        } else {
            log.warn("[WRMS SMS - GENERIC_REST] Webhook dispatch to {} returned HTTP {}: {}", masked, response.statusCode(), response.body());
            return SmsDeliveryResult.failure("GENERIC_REST", "HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) {
            return "****";
        }
        int len = phone.length();
        if (len <= 7) {
            return phone.substring(0, 2) + "***" + phone.substring(len - 2);
        }
        return phone.substring(0, len - 5) + "***" + phone.substring(len - 2);
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
