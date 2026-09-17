package com.weeklyroster.service.sms.provider;

import com.weeklyroster.entity.SmsDeliveryStatus;
import com.weeklyroster.entity.SmsMessageType;
import com.weeklyroster.service.sms.SmsDeliveryResult;
import com.weeklyroster.util.PhoneUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

public class GenericRestSmsProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(GenericRestSmsProvider.class);

    private final String apiKey;
    private final String senderId;
    private final String endpointUrl;
    private final HttpClient httpClient;

    public GenericRestSmsProvider(String apiKey, String senderId, String endpointUrl, HttpClient httpClient) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.senderId = senderId != null && !senderId.isBlank() ? senderId.trim() : "WRMS";
        this.endpointUrl = endpointUrl != null ? endpointUrl.trim() : "";
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .build();
    }

    @Override
    public String getProviderName() {
        return "GENERIC_REST";
    }

    @Override
    public boolean isConfigured() {
        return !endpointUrl.isBlank();
    }

    @Override
    public boolean isRealTelecom() {
        return isConfigured();
    }

    @Override
    public SmsDeliveryResult send(String clean10DigitPhone, String message, SmsMessageType messageType, String dltTemplateId) {
        if (!isConfigured()) {
            return SmsDeliveryResult.failure(getProviderName(), "Endpoint URL not configured", SmsDeliveryStatus.REQUEST_FAILED);
        }

        String masked = PhoneUtils.maskPhone(clean10DigitPhone);
        String jsonPayload = String.format(
                "{\"to\":\"%s\",\"message\":\"%s\",\"sender\":\"%s\",\"messageType\":\"%s\",\"dltTemplateId\":\"%s\"}",
                clean10DigitPhone,
                escapeJson(message),
                escapeJson(senderId),
                messageType != null ? messageType.name() : "SMS",
                escapeJson(dltTemplateId != null ? dltTemplateId : "")
        );

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpointUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(8))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload));

            if (!apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();

            if (statusCode == 401 || statusCode == 403) {
                log.warn("[WRMS SMS - GENERIC_REST] Authentication failure for {}: HTTP {}", masked, statusCode);
                return SmsDeliveryResult.failure(getProviderName(), "Authentication failure (HTTP " + statusCode + ")", SmsDeliveryStatus.REQUEST_FAILED);
            }

            if (statusCode >= 200 && statusCode < 300) {
                String id = "WEBHOOK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
                log.info("[WRMS SMS - GENERIC_REST] Request accepted for {} (HTTP {})", masked, statusCode);
                return SmsDeliveryResult.success(id, getProviderName());
            } else {
                log.warn("[WRMS SMS - GENERIC_REST] Request failed for {}: HTTP {} - {}", masked, statusCode, response.body());
                return SmsDeliveryResult.failure(getProviderName(), "HTTP " + statusCode + ": " + response.body(), SmsDeliveryStatus.REQUEST_FAILED);
            }
        } catch (Exception ex) {
            log.warn("[WRMS SMS - GENERIC_REST] Connection exception for {}: {}", masked, ex.getMessage());
            return SmsDeliveryResult.failure(getProviderName(), "Connection failed: " + ex.getMessage(), SmsDeliveryStatus.REQUEST_FAILED);
        }
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
