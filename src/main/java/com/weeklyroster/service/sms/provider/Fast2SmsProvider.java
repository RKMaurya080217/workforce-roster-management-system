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

public class Fast2SmsProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(Fast2SmsProvider.class);

    private final String apiKey;
    private final String senderId;
    private final String endpointUrl;
    private final HttpClient httpClient;

    public Fast2SmsProvider(String apiKey, String senderId, String endpointUrl, HttpClient httpClient) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.senderId = senderId != null && !senderId.isBlank() ? senderId.trim() : "WRMS";
        this.endpointUrl = endpointUrl != null && !endpointUrl.isBlank()
                ? endpointUrl.trim()
                : "https://www.fast2sms.com/dev/bulkV2";
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .build();
    }

    @Override
    public String getProviderName() {
        return "FAST2SMS";
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    @Override
    public boolean isRealTelecom() {
        return true;
    }

    @Override
    public SmsDeliveryResult send(String clean10DigitPhone, String message, SmsMessageType messageType, String dltTemplateId) {
        if (!isConfigured()) {
            return SmsDeliveryResult.failure(getProviderName(), "Fast2SMS API key not configured", SmsDeliveryStatus.REQUEST_FAILED);
        }

        String masked = PhoneUtils.maskPhone(clean10DigitPhone);
        String jsonPayload;

        if (dltTemplateId != null && !dltTemplateId.isBlank()) {
            // TRAI DLT route
            jsonPayload = String.format(
                    "{\"route\":\"dlt\",\"sender_id\":\"%s\",\"message\":\"%s\",\"numbers\":\"%s\"}",
                    escapeJson(senderId),
                    escapeJson(dltTemplateId),
                    clean10DigitPhone
            );
        } else {
            // Quick / Transactional fallback route
            jsonPayload = String.format(
                    "{\"route\":\"q\",\"message\":\"%s\",\"language\":\"english\",\"numbers\":\"%s\"}",
                    escapeJson(message),
                    clean10DigitPhone
            );
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpointUrl))
                    .header("authorization", apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(8))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            String responseBody = response.body() != null ? response.body() : "";

            if (statusCode == 401 || statusCode == 403) {
                log.warn("[WRMS SMS - FAST2SMS] Authentication failure for {}: HTTP {}", masked, statusCode);
                return SmsDeliveryResult.failure(getProviderName(), "Authentication failure (HTTP " + statusCode + ")", SmsDeliveryStatus.REQUEST_FAILED);
            }

            if (statusCode >= 200 && statusCode < 300) {
                // Fast2SMS returns {"return":true,...} or {"return":false,...}
                if (responseBody.contains("\"return\":true") || responseBody.contains("\"return\": true")) {
                    String requestId = extractRequestId(responseBody);
                    if (requestId == null || requestId.isBlank()) {
                        requestId = "F2S-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
                    }
                    log.info("[WRMS SMS - FAST2SMS] Request accepted by provider for {} (ID: {})", masked, requestId);
                    return SmsDeliveryResult.success(requestId, getProviderName());
                } else {
                    log.warn("[WRMS SMS - FAST2SMS] Provider rejected dispatch for {}: {}", masked, responseBody);
                    return SmsDeliveryResult.failure(getProviderName(), "Provider rejected: " + responseBody, SmsDeliveryStatus.REQUEST_FAILED);
                }
            } else {
                log.warn("[WRMS SMS - FAST2SMS] Dispatch failed for {}: HTTP {} - {}", masked, statusCode, responseBody);
                return SmsDeliveryResult.failure(getProviderName(), "HTTP " + statusCode + ": " + responseBody, SmsDeliveryStatus.REQUEST_FAILED);
            }
        } catch (Exception ex) {
            log.warn("[WRMS SMS - FAST2SMS] Exception connecting to gateway for {}: {}", masked, ex.getMessage());
            return SmsDeliveryResult.failure(getProviderName(), "Connection failed: " + ex.getMessage(), SmsDeliveryStatus.REQUEST_FAILED);
        }
    }

    private String extractRequestId(String json) {
        try {
            int idx = json.indexOf("\"request_id\"");
            if (idx != -1) {
                int colon = json.indexOf(":", idx);
                if (colon != -1) {
                    int quoteStart = json.indexOf("\"", colon);
                    if (quoteStart != -1) {
                        int quoteEnd = json.indexOf("\"", quoteStart + 1);
                        if (quoteEnd != -1) {
                            return json.substring(quoteStart + 1, quoteEnd);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
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
