package com.weeklyroster.dto.response;

public record AuthResponse(
        String token,
        String tokenType,
        UserProfileResponse user
) {
}
