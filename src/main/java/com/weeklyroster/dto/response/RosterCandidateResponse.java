package com.weeklyroster.dto.response;

import java.util.Map;

/**
 * BATCH 94: Multi-Candidate Roster Generation & Admin Comparison DTO
 *
 * Represents an individual candidate roster option with its overall quality score,
 * dimensional breakdown (preference match %, continuity rating, night balance, weekly off),
 * hard constraint safety guarantee (0 critical conflicts), and full grid response.
 */
public record RosterCandidateResponse(
        String candidateId,
        int candidateIndex,
        String label,
        int qualityScore,
        int preferenceMatchPct,
        String continuityLabel,
        String nightBalanceLabel,
        String weeklyOffLabel,
        int conflictsCount,
        String diversityDescription,
        RosterCycleResponse roster,
        Map<String, String> employeeScheduleSummary
) {}
