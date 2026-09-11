package com.weeklyroster.service;

import com.weeklyroster.dto.external.v1.ExternalClientCreateRequest;
import com.weeklyroster.dto.external.v1.ExternalClientResponse;
import com.weeklyroster.entity.ApiClient;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.ApiClientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ApiClientRepository apiClientRepository;
    private final String defaultApiKey;

    public ApiKeyService(ApiClientRepository apiClientRepository,
                         @Value("${wrms.external-api.default-key:wrms_live_dev_test_secret_key_change_in_prod}") String defaultApiKey) {
        this.apiClientRepository = apiClientRepository;
        this.defaultApiKey = defaultApiKey;
    }

    public Optional<ApiClient> validateApiKey(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            return Optional.empty();
        }

        String trimmed = rawApiKey.trim();
        String hash = hashKey(trimmed);

        // Check fallback / environment variable default API key
        if (defaultApiKey != null && !defaultApiKey.isBlank()) {
            if (trimmed.equals(defaultApiKey.trim()) || hash.equalsIgnoreCase(hashKey(defaultApiKey.trim()))) {
                ApiClient defaultClient = new ApiClient(
                        "Default Integration Client",
                        hash,
                        "ROSTER_READ,EMPLOYEE_READ,SHIFT_READ,LEAVE_READ",
                        120
                );
                defaultClient.setId(0L);
                return Optional.of(defaultClient);
            }
        }

        Optional<ApiClient> clientOpt = apiClientRepository.findByApiKeyHash(hash);
        if (clientOpt.isPresent()) {
            ApiClient client = clientOpt.get();
            if (client.isActive()) {
                try {
                    client.setLastUsedAt(LocalDateTime.now());
                    apiClientRepository.save(client);
                } catch (Exception e) {
                    log.debug("Could not update lastUsedAt for client {}: {}", client.getClientName(), e.getMessage());
                }
                return Optional.of(client);
            }
        }

        return Optional.empty();
    }

    @Transactional
    public ExternalClientResponse createClient(ExternalClientCreateRequest request) {
        String plainApiKey = generateSecureKey();
        String keyHash = hashKey(plainApiKey);

        String scopes = request.scopes();
        if (scopes == null || scopes.isBlank()) {
            scopes = "ROSTER_READ,EMPLOYEE_READ,SHIFT_READ,LEAVE_READ";
        } else {
            scopes = scopes.toUpperCase().replace(" ", "");
        }

        int rateLimit = request.rateLimitPerMinute() != null && request.rateLimitPerMinute() > 0
                ? request.rateLimitPerMinute()
                : 60;

        ApiClient client = new ApiClient(request.clientName().trim(), keyHash, scopes, rateLimit);
        ApiClient saved = apiClientRepository.save(client);

        log.info("Created new external API client: '{}' (ID: {}) with scopes [{}]", saved.getClientName(), saved.getId(), scopes);

        return new ExternalClientResponse(
                saved.getId(),
                saved.getClientName(),
                plainApiKey, // Exposed ONLY once on creation
                maskKey(plainApiKey),
                saved.getScopes(),
                saved.getRateLimitPerMinute(),
                saved.isActive(),
                saved.getCreatedAt(),
                saved.getLastUsedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<ExternalClientResponse> listClients() {
        return apiClientRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(c -> new ExternalClientResponse(
                        c.getId(),
                        c.getClientName(),
                        null,
                        "wrms_live_****************",
                        c.getScopes(),
                        c.getRateLimitPerMinute() != null ? c.getRateLimitPerMinute() : 60,
                        c.isActive(),
                        c.getCreatedAt(),
                        c.getLastUsedAt()
                ))
                .toList();
    }

    @Transactional
    public ExternalClientResponse toggleStatus(Long id) {
        ApiClient client = apiClientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("API Client not found with id: " + id));

        client.setActive(!client.isActive());
        ApiClient updated = apiClientRepository.save(client);

        log.info("Toggled status for external client '{}' (ID: {}) to active={}", updated.getClientName(), updated.getId(), updated.isActive());

        return new ExternalClientResponse(
                updated.getId(),
                updated.getClientName(),
                null,
                "wrms_live_****************",
                updated.getScopes(),
                updated.getRateLimitPerMinute() != null ? updated.getRateLimitPerMinute() : 60,
                updated.isActive(),
                updated.getCreatedAt(),
                updated.getLastUsedAt()
        );
    }

    @Transactional
    public void deleteClient(Long id) {
        ApiClient client = apiClientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("API Client not found with id: " + id));
        apiClientRepository.delete(client);
        log.warn("Revoked and deleted external client '{}' (ID: {})", client.getClientName(), id);
    }

    public static String hashKey(String plainKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(plainKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : encodedhash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public static String generateSecureKey() {
        byte[] randomBytes = new byte[16];
        SECURE_RANDOM.nextBytes(randomBytes);
        StringBuilder sb = new StringBuilder("wrms_live_");
        for (byte b : randomBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String maskKey(String key) {
        if (key == null || key.length() < 14) return "wrms_live_****";
        return key.substring(0, 10) + "..." + key.substring(key.length() - 4);
    }
}
