package com.weeklyroster.entity;

public enum RosterStatus {
    DRAFT,
    GENERATED,
    PUBLISHED,
    ACTIVE,
    COMPLETED,
    ARCHIVED,
    LOCKED;

    /**
     * Determines whether a transition from this status to target status is valid.
     */
    public boolean canTransitionTo(RosterStatus target) {
        if (target == null || this == target) return true;
        return switch (this) {
            case DRAFT -> target == GENERATED || target == ARCHIVED;
            case GENERATED -> target == PUBLISHED || target == DRAFT || target == ARCHIVED;
            case PUBLISHED -> target == ACTIVE || target == LOCKED || target == COMPLETED || target == ARCHIVED;
            case ACTIVE -> target == COMPLETED || target == LOCKED || target == ARCHIVED;
            case LOCKED -> target == PUBLISHED || target == ACTIVE || target == COMPLETED || target == ARCHIVED;
            case COMPLETED -> target == ARCHIVED;
            case ARCHIVED -> false;
        };
    }
}
