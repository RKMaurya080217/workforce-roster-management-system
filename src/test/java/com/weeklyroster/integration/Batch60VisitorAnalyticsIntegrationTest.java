package com.weeklyroster.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.dto.VisitorHeartbeatRequest;
import com.weeklyroster.dto.VisitorStatsResponse;
import com.weeklyroster.repository.ActiveVisitorRepository;
import com.weeklyroster.repository.VisitorStatisticRepository;
import com.weeklyroster.service.VisitorAnalyticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Batch60VisitorAnalyticsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VisitorAnalyticsService visitorAnalyticsService;

    @Autowired
    private VisitorStatisticRepository statisticRepository;

    @Autowired
    private ActiveVisitorRepository activeVisitorRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        activeVisitorRepository.deleteAll();
    }

    @Test
    @Order(1)
    @DisplayName("Public Access: GET /api/visitor-stats should be accessible without authentication")
    void testGetStatsPublicAccess() throws Exception {
        mockMvc.perform(get("/api/visitor-stats")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.totalVisits").isNumber())
                .andExpect(jsonPath("$.onlineNow").isNumber());
    }

    @Test
    @Order(2)
    @DisplayName("Atomic Increment: POST /api/visitor-stats/visit should atomically increment total visits and register online visitor")
    void testRecordVisitIncrementsTotalAndSetsOnline() throws Exception {
        MvcResult initialResult = mockMvc.perform(get("/api/visitor-stats"))
                .andExpect(status().isOk())
                .andReturn();
        VisitorStatsResponse initialStats = objectMapper.readValue(
                initialResult.getResponse().getContentAsString(), VisitorStatsResponse.class);
        long initialVisits = initialStats.getTotalVisits();

        VisitorHeartbeatRequest request = new VisitorHeartbeatRequest("test-visitor-v1");

        mockMvc.perform(post("/api/visitor-stats/visit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.totalVisits").value(greaterThanOrEqualTo((int) initialVisits + 1)))
                .andExpect(jsonPath("$.onlineNow").value(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(3)
    @DisplayName("Heartbeat Idempotency: POST /api/visitor-stats/heartbeat should NOT increment total visits")
    void testHeartbeatDoesNotIncrementTotalVisits() throws Exception {
        String visitorId = "test-visitor-heartbeat-1";

        MvcResult visitResult = mockMvc.perform(post("/api/visitor-stats/visit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VisitorHeartbeatRequest(visitorId))))
                .andExpect(status().isOk())
                .andReturn();
        VisitorStatsResponse afterVisit = objectMapper.readValue(
                visitResult.getResponse().getContentAsString(), VisitorStatsResponse.class);
        long visitsAfterFirst = afterVisit.getTotalVisits();

        MvcResult heartbeatResult = mockMvc.perform(post("/api/visitor-stats/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VisitorHeartbeatRequest(visitorId))))
                .andExpect(status().isOk())
                .andReturn();
        VisitorStatsResponse afterHeartbeat = objectMapper.readValue(
                heartbeatResult.getResponse().getContentAsString(), VisitorStatsResponse.class);

        assertEquals(visitsAfterFirst, afterHeartbeat.getTotalVisits(),
                "Heartbeat must not increment total visits counter");
        assertTrue(afterHeartbeat.getOnlineNow() >= 1,
                "Visitor must be counted as online");
    }

    @Test
    @Order(4)
    @DisplayName("Deduplication: Multiple heartbeats from same visitorId count as 1 online visitor")
    void testMultipleTabsSameVisitorCountAsOne() throws Exception {
        String sharedVisitorId = "shared-browser-session-999";

        mockMvc.perform(post("/api/visitor-stats/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VisitorHeartbeatRequest(sharedVisitorId))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/visitor-stats/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VisitorHeartbeatRequest(sharedVisitorId))))
                .andExpect(status().isOk());

        MvcResult statsResult = mockMvc.perform(get("/api/visitor-stats"))
                .andExpect(status().isOk())
                .andReturn();
        VisitorStatsResponse stats = objectMapper.readValue(
                statsResult.getResponse().getContentAsString(), VisitorStatsResponse.class);

        assertEquals(1, stats.getOnlineNow(),
                "Multiple tabs/heartbeats from same visitorId must count as exactly 1 online visitor");
    }

    @Test
    @Order(5)
    @DisplayName("Online Window: Inactive visitor older than 90 seconds is not counted as online")
    void testInactivityWindowExcludesOldVisitors() {
        String activeVisitor = "v-recent-active";
        String inactiveVisitor = "v-expired-past-90s";

        LocalDateTime now = LocalDateTime.now();
        visitorAnalyticsService.recordHeartbeat(activeVisitor);

        visitorAnalyticsService.recordHeartbeat(inactiveVisitor);
        activeVisitorRepository.updateLastSeen(inactiveVisitor, now.minusSeconds(120));

        long onlineCount = visitorAnalyticsService.getOnlineCount();
        assertEquals(1, onlineCount,
                "Visitor inactive for > 90 seconds must not be counted as online");
    }

    @Test
    @Order(6)
    @DisplayName("Immediate Offline: POST /api/visitor-stats/offline removes visitor from online pool")
    void testRecordOfflineRemovesVisitor() throws Exception {
        String visitorId = "v-to-go-offline";

        mockMvc.perform(post("/api/visitor-stats/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VisitorHeartbeatRequest(visitorId))))
                .andExpect(status().isOk());

        assertEquals(1, visitorAnalyticsService.getOnlineCount());

        mockMvc.perform(post("/api/visitor-stats/offline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VisitorHeartbeatRequest(visitorId))))
                .andExpect(status().isOk());

        assertEquals(0, visitorAnalyticsService.getOnlineCount(),
                "Visitor session must be cleared after offline notification");
    }

    @Test
    @Order(7)
    @DisplayName("Concurrency: Concurrent visit increments update totalVisits correctly")
    void testConcurrentVisitIncrements() throws Exception {
        MvcResult initialResult = mockMvc.perform(get("/api/visitor-stats"))
                .andExpect(status().isOk())
                .andReturn();
        VisitorStatsResponse initialStats = objectMapper.readValue(
                initialResult.getResponse().getContentAsString(), VisitorStatsResponse.class);
        long startVisits = initialStats.getTotalVisits();

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final String vid = "concurrent-visitor-" + i;
            executor.submit(() -> {
                try {
                    visitorAnalyticsService.recordVisit(vid);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // Log or ignore
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(threadCount, successCount.get(), "All concurrent visit increments should succeed");

        long endVisits = visitorAnalyticsService.getStats().getTotalVisits();
        assertEquals(startVisits + threadCount, endVisits,
                "Total visits counter must increase by exactly the number of concurrent visits");
    }
}