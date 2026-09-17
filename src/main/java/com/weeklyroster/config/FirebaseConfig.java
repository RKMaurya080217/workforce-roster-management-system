package com.weeklyroster.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Value("${fcm.enabled:${FCM_ENABLED:true}}")
    private boolean enabled;

    @Value("${fcm.web.api-key:${FCM_WEB_API_KEY:${FIREBASE_API_KEY:}}}")
    private String webApiKey;

    @Value("${fcm.web.project-id:${FCM_WEB_PROJECT_ID:${FIREBASE_PROJECT_ID:}}}")
    private String projectId;

    @Value("${fcm.web.messaging-sender-id:${FCM_WEB_MESSAGING_SENDER_ID:${FIREBASE_MESSAGING_SENDER_ID:}}}")
    private String messagingSenderId;

    @Value("${fcm.web.app-id:${FCM_WEB_APP_ID:${FIREBASE_APP_ID:}}}")
    private String appId;

    @Value("${fcm.web.vapid-key:${FCM_VAPID_KEY:${FCM_WEB_VAPID_KEY:${FIREBASE_VAPID_KEY:${FIREBASE_WEB_VAPID_KEY:}}}}}")
    private String vapidKey;

    @Value("${fcm.server.service-account-json:${FIREBASE_SERVICE_ACCOUNT_JSON:}}")
    private String serviceAccountJson;

    @Value("${fcm.server.credentials-path:${FIREBASE_CREDENTIALS_PATH:${GOOGLE_APPLICATION_CREDENTIALS:}}}")
    private String credentialsPath;

    @Value("${fcm.server.client-email:${FIREBASE_CLIENT_EMAIL:${FCM_CLIENT_EMAIL:}}}")
    private String clientEmail;

    @Value("${fcm.server.private-key:${FIREBASE_PRIVATE_KEY:${FCM_PRIVATE_KEY:}}}")
    private String privateKey;

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isWebConfigured() {
        return enabled && webApiKey != null && !webApiKey.isBlank()
                && projectId != null && !projectId.isBlank()
                && messagingSenderId != null && !messagingSenderId.isBlank()
                && vapidKey != null && !vapidKey.isBlank();
    }

    public boolean isServerConfigured() {
        if (!enabled) return false;
        String json = getServiceAccountJsonContent();
        if (json != null && !json.isBlank()) return true;
        return clientEmail != null && !clientEmail.isBlank()
                && privateKey != null && !privateKey.isBlank()
                && projectId != null && !projectId.isBlank();
    }

    public String getClientEmail() {
        if (clientEmail != null && !clientEmail.isBlank()) {
            return clientEmail.trim();
        }
        String json = getServiceAccountJsonContent();
        if (json != null && !json.isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
                String email = root.path("client_email").asText();
                if (!email.isBlank()) return email.trim();
            } catch (Exception ignored) {}
        }
        return null;
    }

    public String getPrivateKeyPem() {
        if (privateKey != null && !privateKey.isBlank()) {
            return cleanPrivateKeyPem(privateKey);
        }
        String json = getServiceAccountJsonContent();
        if (json != null && !json.isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
                String key = root.path("private_key").asText();
                if (!key.isBlank()) return cleanPrivateKeyPem(key);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private String cleanPrivateKeyPem(String pem) {
        if (pem == null) return null;
        String trimmed = pem.trim();
        // Handle Base64-encoded PEM string if provided
        if (!trimmed.contains("BEGIN PRIVATE KEY") && !trimmed.contains("---")) {
            try {
                byte[] decoded = Base64.getDecoder().decode(trimmed);
                trimmed = new String(decoded, StandardCharsets.UTF_8).trim();
            } catch (Exception ignored) {}
        }
        return trimmed;
    }

    public String getServiceAccountJsonContent() {
        if (serviceAccountJson != null && !serviceAccountJson.isBlank()) {
            String trimmed = serviceAccountJson.trim();
            if (trimmed.startsWith("{")) {
                return trimmed;
            }
            try {
                // Support Base64 encoded JSON string
                byte[] decoded = Base64.getDecoder().decode(trimmed);
                return new String(decoded, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return trimmed;
            }
        }
        if (credentialsPath != null && !credentialsPath.isBlank()) {
            try {
                File file = new File(credentialsPath.trim());
                if (file.exists() && file.canRead()) {
                    return Files.readString(file.toPath(), StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                log.warn("[WRMS FCM] Failed reading credentials from path {}: {}", credentialsPath, e.getMessage());
            }
        }
        return null;
    }

    public String getWebApiKey() { return webApiKey != null ? webApiKey : ""; }
    public String getProjectId() { return projectId != null ? projectId : ""; }
    public String getMessagingSenderId() { return messagingSenderId != null ? messagingSenderId : ""; }
    public String getAppId() { return appId != null ? appId : ""; }
    public String getVapidKey() { return vapidKey != null ? vapidKey : ""; }
}
