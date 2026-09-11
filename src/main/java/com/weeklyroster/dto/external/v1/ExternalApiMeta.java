package com.weeklyroster.dto.external.v1;

import java.time.LocalDateTime;

public record ExternalApiMeta(
        LocalDateTime timestamp,
        String requestId,
        String version
) {}
