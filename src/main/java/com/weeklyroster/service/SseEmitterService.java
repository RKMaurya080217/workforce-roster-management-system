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

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> userEmitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required for SSE subscription");
        }

        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT);
        userEmitters.computeIfAbsent(username, k -> new CopyOnWriteArrayList<>()).add(emitter);

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

        for (Map.Entry<String, CopyOnWriteArrayList<SseEmitter>> entry : userEmitters.entrySet()) {
            String user = entry.getKey();
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("PING")
                            .data(Map.of("ping", true, "timestamp", System.currentTimeMillis())));
                } catch (Exception e) {
                    removeEmitter(user, emitter);
                }
            }
        }
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