package com.weeklyroster.service.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.config.FirebaseConfig;
import com.weeklyroster.entity.DeviceToken;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.User;
import com.weeklyroster.repository.DeviceTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NotificationPushServiceImpl implements NotificationPushService {

    private static final Logger log = LoggerFactory.getLogger(NotificationPushServiceImpl.class);
    private static final DateTimeFormatter D_MMM = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter D_MMM_YYYY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final FirebaseConfig firebaseConfig;
    private final DeviceTokenRepository deviceTokenRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    // In-memory cache for duplicate suppression (15 minutes window)
    private final Map<String, Instant> dispatchCache = new ConcurrentHashMap<>();

    // Cached Google OAuth2 Access Token
    private volatile String cachedAccessToken = null;
    private volatile Instant accessTokenExpiry = Instant.MIN;

    @Autowired
    public NotificationPushServiceImpl(FirebaseConfig firebaseConfig,
                                       DeviceTokenRepository deviceTokenRepository) {
        this.firebaseConfig = firebaseConfig;
        this.deviceTokenRepository = deviceTokenRepository;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .build();
    }

    @Override
    public boolean isConfigured() {
        return firebaseConfig.isServerConfigured();
    }

    @Override
    @Transactional(readOnly = true)
    public long getActiveTokenCountForUser(User user) {
        if (user == null) return 0;
        return deviceTokenRepository.countByUserAndActiveTrue(user);
    }

    @Override
    @Transactional
    public boolean registerToken(User user, Employee employee, String token, String deviceType) {
        if (user == null || token == null || token.trim().isBlank()) {
            return false;
        }
        String cleanToken = token.trim();
        Optional<DeviceToken> existingOpt = deviceTokenRepository.findByToken(cleanToken);

        if (existingOpt.isPresent()) {
            DeviceToken dt = existingOpt.get();
            dt.setUser(user);
            if (employee != null) dt.setEmployee(employee);
            if (deviceType != null && !deviceType.isBlank()) dt.setDeviceType(deviceType.trim());
            dt.setActive(true);
            dt.setUpdatedAt(LocalDateTime.now());
            deviceTokenRepository.save(dt);
            log.info("[WRMS PUSH] Updated active token {} for user: {}", maskToken(cleanToken), user.getUsername());
        } else {
            DeviceToken dt = new DeviceToken(user, employee, cleanToken, deviceType != null ? deviceType.trim() : "Browser");
            deviceTokenRepository.save(dt);
            log.info("[WRMS PUSH] Registered new token {} for user: {}", maskToken(cleanToken), user.getUsername());
        }
        return true;
    }

    @Override
    @Transactional
    public boolean deactivateToken(String token, User user) {
        if (token == null || token.isBlank()) return false;
        String cleanToken = token.trim();
        if (user != null) {
            int updated = deviceTokenRepository.deactivateTokenForUser(cleanToken, user, LocalDateTime.now());
            log.info("[WRMS PUSH] Deactivated token {} for user: {}", maskToken(cleanToken), user.getUsername());
            return updated > 0;
        } else {
            int updated = deviceTokenRepository.deactivateToken(cleanToken, LocalDateTime.now());
            log.info("[WRMS PUSH] Deactivated token {}", maskToken(cleanToken));
            return updated > 0;
        }
    }

    @Override
    @Transactional
    public int sendRosterNotification(RosterCycle cycle, Employee employee, boolean isFinal, String dateRangeParam) {
        if (cycle == null || employee == null) {
            return 0;
        }

        // Exact dynamic date range from the actual cycle object in Asia/Kolkata
        String formattedRange = formatCycleDateRange(cycle.getStartDate(), cycle.getEndDate());

        // Exact required notification format:
        // Final: "Your final roster for {START_DATE} – {END_DATE} has been sent to your registered email."
        // Tentative: "Your tentative roster for {START_DATE} – {END_DATE} has been sent to your registered email."
        String title = isFinal ? "WRMS Final Roster Published" : "WRMS Tentative Roster Available";
        String body = isFinal
                ? String.format("Your final roster for %s has been sent to your registered email.", formattedRange)
                : String.format("Your tentative roster for %s has been sent to your registered email.", formattedRange);

        // Section 22: Duplicate Notification Protection (15-minute suppression window per cycle, employee, type)
        String dedupeKey = cycle.getId() + ":" + employee.getId() + ":" + (isFinal ? "FINAL" : "TENTATIVE");
        Instant lastSent = dispatchCache.get(dedupeKey);
        if (lastSent != null && Duration.between(lastSent, Instant.now()).toMinutes() < 15) {
            log.info("[WRMS PUSH] Duplicate push notification suppressed for employee {} on cycle #{} (sent within last 15m)",
                    employee.getEmployeeCode(), cycle.getId());
            return 0;
        }

        List<DeviceToken> tokens = deviceTokenRepository.findByEmployeeAndActiveTrue(employee);
        if (tokens.isEmpty() && employee.getUser() != null) {
            tokens = deviceTokenRepository.findByUserAndActiveTrue(employee.getUser());
        }

        Map<String, String> data = Map.of(
                "cycleId", String.valueOf(cycle.getId()),
                "isFinal", String.valueOf(isFinal),
                "linkPage", "roster"
        );

        int delivered = 0;
        if (!firebaseConfig.isServerConfigured()) {
            // Simulated / Log mode fallback
            if (isFinal) {
                log.info("[WRMS PUSH - SIMULATED/LOG] Final roster notification sent to employee {} for {}",
                        employee.getEmployeeCode(), formattedRange);
            } else {
                log.info("[WRMS PUSH - SIMULATED/LOG] Tentative roster notification sent to employee {} for {}",
                        employee.getEmployeeCode(), formattedRange);
            }
            dispatchCache.put(dedupeKey, Instant.now());
            return Math.max(1, tokens.size());
        }

        for (DeviceToken dt : tokens) {
            boolean success = dispatchFcmMessage(dt.getToken(), title, body, data);
            if (success) {
                delivered++;
                dt.setLastUsedAt(LocalDateTime.now());
                deviceTokenRepository.save(dt);
            }
        }

        if (delivered > 0) {
            dispatchCache.put(dedupeKey, Instant.now());
            if (isFinal) {
                log.info("[WRMS PUSH] Final roster notification sent to employee {} for {}",
                        employee.getEmployeeCode(), formattedRange);
            } else {
                log.info("[WRMS PUSH] Tentative roster notification sent to employee {} for {}",
                        employee.getEmployeeCode(), formattedRange);
            }
        } else if (!tokens.isEmpty()) {
            log.warn("[WRMS PUSH] Failed for employee {}: all active tokens failed delivery", employee.getEmployeeCode());
        }

        return delivered;
    }

    @Override
    @Transactional
    public boolean sendNotificationToUser(User user, String title, String body, Map<String, String> data) {
        if (user == null) return false;
        List<DeviceToken> tokens = deviceTokenRepository.findByUserAndActiveTrue(user);
        if (tokens.isEmpty()) {
            log.info("[WRMS PUSH] No active device tokens found for user: {}", user.getUsername());
            return false;
        }

        if (!firebaseConfig.isServerConfigured()) {
            log.info("[WRMS PUSH - SIMULATED/LOG] Push notification sent to user {}: '{}'", user.getUsername(), title);
            return true;
        }

        boolean anySuccess = false;
        for (DeviceToken dt : tokens) {
            boolean ok = dispatchFcmMessage(dt.getToken(), title, body, data);
            if (ok) {
                anySuccess = true;
                dt.setLastUsedAt(LocalDateTime.now());
                deviceTokenRepository.save(dt);
            }
        }
        return anySuccess;
    }

    @Override
    @Transactional
    public boolean sendAdminTestNotification(User adminUser) {
        if (adminUser == null) return false;
        String title = "WRMS Test Notification";
        String body = "WRMS test notification: Push notifications are working successfully.";
        Map<String, String> data = Map.of("type", "ADMIN_TEST", "timestamp", String.valueOf(System.currentTimeMillis()));

        List<DeviceToken> tokens = deviceTokenRepository.findByUserAndActiveTrue(adminUser);
        if (!firebaseConfig.isServerConfigured()) {
            log.info("[WRMS PUSH - SIMULATED/LOG] Admin test notification sent to {}: '{}'", adminUser.getUsername(), body);
            return true;
        }

        if (tokens.isEmpty()) {
            log.warn("[WRMS PUSH] Admin test failed: no registered device tokens found for admin {}", adminUser.getUsername());
            return false;
        }

        boolean anySuccess = false;
        for (DeviceToken dt : tokens) {
            boolean ok = dispatchFcmMessage(dt.getToken(), title, body, data);
            if (ok) {
                anySuccess = true;
                dt.setLastUsedAt(LocalDateTime.now());
                deviceTokenRepository.save(dt);
            }
        }
        return anySuccess;
    }

    public String formatCycleDateRange(LocalDate start, LocalDate end) {
        if (start == null || end == null) return "";
        if (start.getYear() == end.getYear()) {
            return start.format(D_MMM) + " \u2013 " + end.format(D_MMM_YYYY);
        } else {
            return start.format(D_MMM_YYYY) + " \u2013 " + end.format(D_MMM_YYYY);
        }
    }

    private boolean dispatchFcmMessage(String token, String title, String body, Map<String, String> data) {
        try {
            String projectId = firebaseConfig.getProjectId();
            String accessToken = getGoogleAccessToken();
            if (accessToken == null || projectId == null || projectId.isBlank()) {
                log.warn("[WRMS PUSH] Cannot send FCM message: Google Access Token or Project ID unavailable");
                return false;
            }

            Map<String, Object> notificationMap = new LinkedHashMap<>();
            notificationMap.put("title", title);
            notificationMap.put("body", body);

            Map<String, Object> webNotification = new LinkedHashMap<>(notificationMap);
            webNotification.put("icon", "/favicon.ico");
            webNotification.put("badge", "/favicon.ico");

            Map<String, Object> webpush = new LinkedHashMap<>();
            webpush.put("notification", webNotification);
            webpush.put("fcm_options", Map.of("link", "/"));

            Map<String, Object> message = new LinkedHashMap<>();
            message.put("token", token);
            message.put("notification", notificationMap);
            message.put("webpush", webpush);
            if (data != null && !data.isEmpty()) {
                message.put("data", data);
            }

            Map<String, Object> payload = Map.of("message", message);
            String jsonBody = objectMapper.writeValueAsString(payload);

            String url = "https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json; UTF-8")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("[WRMS PUSH] FCM delivery SUCCESS to token: {}", maskToken(token));
                return true;
            } else {
                String respBody = response.body();
                log.warn("[WRMS PUSH] FCM delivery FAILED (HTTP {}): {}", response.statusCode(), respBody);

                // If token is invalid or unregistered, automatically deactivate it
                if (response.statusCode() == 404 || response.statusCode() == 400 || (respBody != null && respBody.contains("UNREGISTERED"))) {
                    deviceTokenRepository.deactivateToken(token, LocalDateTime.now());
                    log.info("[WRMS PUSH] Deactivated invalid/unregistered FCM token: {}", maskToken(token));
                }
                return false;
            }
        } catch (Exception e) {
            log.error("[WRMS PUSH] Failed to dispatch FCM message to {}: {}", maskToken(token), e.getMessage());
            return false;
        }
    }

    private synchronized String getGoogleAccessToken() {
        if (cachedAccessToken != null && Instant.now().isBefore(accessTokenExpiry)) {
            return cachedAccessToken;
        }

        try {
            String jsonContent = firebaseConfig.getServiceAccountJsonContent();
            if (jsonContent == null || jsonContent.isBlank()) {
                return null;
            }

            JsonNode root = objectMapper.readTree(jsonContent);
            String clientEmail = root.path("client_email").asText();
            String privateKeyPem = root.path("private_key").asText();
            String tokenUri = root.path("token_uri").asText("https://oauth2.googleapis.com/token");

            if (clientEmail.isBlank() || privateKeyPem.isBlank()) {
                log.warn("[WRMS FCM] Service account JSON missing client_email or private_key");
                return null;
            }

            // Generate signed JWT for Google OAuth2
            long nowSec = Instant.now().getEpochSecond();
            long expSec = nowSec + 3600;

            String headerJson = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
            String claimsJson = String.format(
                    "{\"iss\":\"%s\",\"sub\":\"%s\",\"aud\":\"%s\",\"iat\":%d,\"exp\":%d,\"scope\":\"https://www.googleapis.com/auth/firebase.messaging\"}",
                    clientEmail, clientEmail, tokenUri, nowSec, expSec
            );

            Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
            String headerB64 = enc.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
            String claimsB64 = enc.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8));
            String signPayload = headerB64 + "." + claimsB64;

            PrivateKey privateKey = parsePrivateKeyFromPem(privateKeyPem);
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(privateKey);
            rsa.update(signPayload.getBytes(StandardCharsets.UTF_8));
            String sigB64 = enc.encodeToString(rsa.sign());

            String jwtAssertion = signPayload + "." + sigB64;

            // Exchange with token_uri
            String formBody = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                    + "&assertion=" + URLEncoder.encode(jwtAssertion, StandardCharsets.UTF_8);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUri))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode tokenNode = objectMapper.readTree(resp.body());
                String token = tokenNode.path("access_token").asText();
                long expiresIn = tokenNode.path("expires_in").asLong(3600);
                this.cachedAccessToken = token;
                this.accessTokenExpiry = Instant.now().plusSeconds(Math.max(300, expiresIn - 300));
                log.info("[WRMS FCM] Successfully obtained Google OAuth2 Access Token for FCM HTTP v1");
                return token;
            } else {
                log.error("[WRMS FCM] Google OAuth token exchange failed (HTTP {}): {}", resp.statusCode(), resp.body());
                return null;
            }
        } catch (Exception e) {
            log.error("[WRMS FCM] Exception while obtaining Google OAuth token: {}", e.getMessage());
            return null;
        }
    }

    private PrivateKey parsePrivateKeyFromPem(String pem) throws Exception {
        String cleaned = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(cleaned);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }

    private String maskToken(String token) {
        if (token == null) return "null";
        if (token.length() <= 10) return "****";
        return token.substring(0, 6) + "..." + token.substring(token.length() - 4);
    }
}
