package com.weeklyroster.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class SseEmitterService {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterService.class);
    private static final Long DEFAULT_TIMEOUT = 180_000L; // 3 minutes
    private static final int MAX_EMITTERS_PER_USER = 3; // Prevent connection leaks across multiple browser tabs

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> userEmitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required for SSE subscription");
        }

        CopyOnWriteArrayList<SseEmitter> list = userEmitters.computeIfAbsent(username, k -> new CopyOnWriteArrayList<>());
        // Gracefully evict oldest emitter if limit is exceeded (e.g. repeated page refreshes / orphaned tabs)
        while (list.size() >= MAX_EMITTERS_PER_USER) {
            SseEmitter oldest = list.remove(0);
            try {
                oldest.complete();
            } catch (Exception ignored) {}
        }

        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT);
        list.add(emitter);

        emitter.onCompletion(() -> removeEmitter(username, emitter));
        emitter.onTimeout(() -> removeEmitter(username, emitter));
        emitter.onError((ex) -> removeEmitter(username, emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("INIT")
                    .data(Map.of(
                            "status", "CONNECTED",
                            "user", username,
                            "timestamp", LocalDateTime.now().toString(),
                            "message", "Real-time notification stream established"
                    )));
        } catch (Exception e) {
            log.warn("Failed to send initial SSE payload to {}: {}", username, e.getMessage());
            removeEmitter(username, emitter);
        }

        return emitter;
    }

    public void sendToUser(String username, String eventName, Object data) {
        if (username == null) return;
        CopyOnWriteArrayList<SseEmitter> emitters = userEmitters.get(username);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName != null ? eventName : "NOTIFICATION_RECEIVED")
                        .data(data));
            } catch (Exception e) {
                removeEmitter(username, emitter);
            }
        }
    }

    public void broadcast(String eventName, Object data) {
        for (Map.Entry<String, CopyOnWriteArrayList<SseEmitter>> entry : userEmitters.entrySet()) {
            String user = entry.getKey();
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event()
                            .name(eventName != null ? eventName : "BROADCAST")
                            .data(data));
                } catch (Exception e) {
                    removeEmitter(user, emitter);
                }
            }
        }
    }

    @Scheduled(fixedRate = 25000)
    public void sendHeartbeat() {
        if (userEmitters.isEmpty()) return;

        Map<String, Object> pingPayload = Map.of("ping", true, "timestamp", System.currentTimeMillis());

        for (Map.Entry<String, CopyOnWriteArrayList<SseEmitter>> entry : userEmitters.entrySet()) {
            String user = entry.getKey();
            CopyOnWriteArrayList<SseEmitter> emitters = entry.getValue();
            if (emitters == null || emitters.isEmpty()) {
                continue;
            }
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("PING")
                            .data(pingPayload));
                } catch (Exception e) {
                    removeEmitter(user, emitter);
                }
            }
        }

        // Prune any empty user keys to keep memory minimal
        userEmitters.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    private void removeEmitter(String username, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = userEmitters.get(username);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                userEmitters.remove(username);
            }
        }
    }

    public int getActiveConnectionCount() {
        return userEmitters.values().stream().mapToInt(CopyOnWriteArrayList::size).sum();
    }
}