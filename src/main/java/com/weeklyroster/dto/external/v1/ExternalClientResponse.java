package com.weeklyroster.dto.external.v1;

import java.time.LocalDateTime;

public record ExternalClientResponse(
        Long id,
        String clientName,
        String plainApiKey, // non-null ONLY at creation time
        String apiKeyMasked,
        String scopes,
        int rateLimitPerMinute,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime lastUsedAt
) {}
