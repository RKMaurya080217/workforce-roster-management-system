package com.weeklyroster.service;

import com.weeklyroster.dto.VisitorStatsResponse;
import com.weeklyroster.entity.ActiveVisitor;
import com.weeklyroster.entity.VisitorStatistic;
import com.weeklyroster.repository.ActiveVisitorRepository;
import com.weeklyroster.repository.VisitorStatisticRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class VisitorAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(VisitorAnalyticsService.class);

    public static final int HEARTBEAT_INTERVAL_SECONDS = 30;
    public static final int INACTIVITY_TIMEOUT_SECONDS = 90;
    public static final int CLEANUP_THRESHOLD_MINUTES = 10;

    private final VisitorStatisticRepository statisticRepository;
    private final ActiveVisitorRepository activeVisitorRepository;
    private volatile Long statRecordId = null;

    public VisitorAnalyticsService(VisitorStatisticRepository statisticRepository,
                                   ActiveVisitorRepository activeVisitorRepository) {
        this.statisticRepository = statisticRepository;
        this.activeVisitorRepository = activeVisitorRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initStatisticRecord() {
        ensureStatRecord();
    }

    private synchronized Long ensureStatRecord() {
        if (statRecordId != null) {
            return statRecordId;
        }
        var existing = statisticRepository.findFirstStat();
        if (existing.isPresent()) {
            statRecordId = existing.get().getId();
        } else {
            VisitorStatistic initial = new VisitorStatistic(0L, LocalDateTime.now());
            VisitorStatistic saved = statisticRepository.save(initial);
            statRecordId = saved.getId();
            log.info("[VisitorAnalytics] Initialized global visitor statistics record (ID: {})", statRecordId);
        }
        return statRecordId;
    }

    @Transactional(readOnly = true)
    public VisitorStatsResponse getStats() {
        Long statId = ensureStatRecord();
        long totalVisits = statisticRepository.findTotalVisitsById(statId).orElse(0L);
        long onlineNow = getOnlineCount();
        return VisitorStatsResponse.of(totalVisits, onlineNow);
    }

    @Transactional
    public VisitorStatsResponse recordVisit(String rawVisitorId) {
        String visitorId = sanitizeVisitorId(rawVisitorId);
        Long statId = ensureStatRecord();
        LocalDateTime now = LocalDateTime.now();

        // 1. Atomic database increment of global total visitors
        int updated = statisticRepository.incrementTotalVisits(statId, now);
        if (updated == 0) {
            statisticRepository.save(new VisitorStatistic(1L, now));
        }

        // 2. Register or refresh active session for this visitor
        touchActiveVisitor(visitorId, now);

        long totalVisits = statisticRepository.findTotalVisitsById(statId).orElse(1L);
        long onlineNow = getOnlineCount();
        return VisitorStatsResponse.of(totalVisits, onlineNow);
    }

    @Transactional
    public VisitorStatsResponse recordHeartbeat(String rawVisitorId) {
        String visitorId = sanitizeVisitorId(rawVisitorId);
        LocalDateTime now = LocalDateTime.now();

        touchActiveVisitor(visitorId, now);

        Long statId = ensureStatRecord();
        long totalVisits = statisticRepository.findTotalVisitsById(statId).orElse(0L);
        long onlineNow = getOnlineCount();
        return VisitorStatsResponse.of(totalVisits, onlineNow);
    }

    @Transactional
    public VisitorStatsResponse recordOffline(String rawVisitorId) {
        if (rawVisitorId != null && !rawVisitorId.isBlank()) {
            String visitorId = sanitizeVisitorId(rawVisitorId);
            activeVisitorRepository.deleteByVisitorId(visitorId);
        }
        Long statId = ensureStatRecord();
        long totalVisits = statisticRepository.findTotalVisitsById(statId).orElse(0L);
        long onlineNow = getOnlineCount();
        return VisitorStatsResponse.of(totalVisits, onlineNow);
    }

    private void touchActiveVisitor(String visitorId, LocalDateTime now) {
        int rows = activeVisitorRepository.updateLastSeen(visitorId, now);
        if (rows == 0) {
            try {
                activeVisitorRepository.save(new ActiveVisitor(visitorId, now));
            } catch (Exception e) {
                activeVisitorRepository.updateLastSeen(visitorId, now);
            }
        }
    }

    public long getOnlineCount() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(INACTIVITY_TIMEOUT_SECONDS);
        return activeVisitorRepository.countActiveVisitorsSince(threshold);
    }

    @Scheduled(fixedRate = 600000)
    @Transactional
    public void cleanupExpiredSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(CLEANUP_THRESHOLD_MINUTES);
        int deleted = activeVisitorRepository.deleteExpiredVisitorsBefore(cutoff);
        if (deleted > 0) {
            log.debug("[VisitorAnalytics] Cleaned up {} inactive visitor sessions older than {} minutes", deleted, CLEANUP_THRESHOLD_MINUTES);
        }
    }

    public static String sanitizeVisitorId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "v_" + UUID.randomUUID().toString();
        }
        String clean = raw.replaceAll("[^a-zA-Z0-9_\\-]", "");
        if (clean.length() > 64) {
            clean = clean.substring(0, 64);
        }
        if (clean.isBlank()) {
            return "v_" + UUID.randomUUID().toString();
        }
        return clean;
    }
}