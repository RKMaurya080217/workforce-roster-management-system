package com.weeklyroster.dto.request;

public record RosterChangeDecisionRequest(
        String overrideReason,
        String adminRemarks
) {}
