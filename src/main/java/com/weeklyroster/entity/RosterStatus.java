package com.weeklyroster.entity;

public enum RosterStatus {
    DRAFT,
    TENTATIVE,
    GENERATED,
    PUBLISHED,
    FINAL,
    LOCKED,
    ACTIVE,
    COMPLETED,
    ARCHIVED;

    /**
     * Determines whether a transition from this status to target status is valid.
     */
    public boolean canTransitionTo(RosterStatus target) {
        if (target == null || this == target) return true;
        return switch (this) {
            case DRAFT -> target == TENTATIVE || target == GENERATED || target == ARCHIVED;
            case TENTATIVE -> target == FINAL || target == LOCKED || target == GENERATED || target == DRAFT || target == ARCHIVED;
            case GENERATED -> target == TENTATIVE || target == PUBLISHED || target == DRAFT || target == ARCHIVED;
            case PUBLISHED -> target == FINAL || target == ACTIVE || target == LOCKED || target == COMPLETED || target == ARCHIVED;
            case FINAL -> target == LOCKED || target == TENTATIVE || target == ACTIVE || target == COMPLETED || target == ARCHIVED;
            case ACTIVE -> target == COMPLETED || target == LOCKED || target == ARCHIVED;
            case LOCKED -> target == TENTATIVE || target == FINAL || target == PUBLISHED || target == ACTIVE || target == COMPLETED || target == ARCHIVED;
            case COMPLETED -> target == ARCHIVED;
            case ARCHIVED -> false;
        };
    }
}
