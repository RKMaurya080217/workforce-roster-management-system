package com.weeklyroster.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.weeklyroster.dto.external.v1.ExternalApiResponse;
import com.weeklyroster.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    public static final int DEFAULT_LIMIT_PER_MINUTE = 60;
    private final ConcurrentHashMap<String, RequestCounter> clientCounters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public RateLimitFilter() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();

        // Only enforce on external API endpoints
        if (!uri.startsWith("/api/external/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIdentifier = resolveClientIdentifier(request);
        long currentMinute = System.currentTimeMillis() / 60000;

        RequestCounter counter = clientCounters.compute(clientIdentifier, (k, existing) -> {
            if (existing == null || existing.minute.get() != currentMinute) {
                return new RequestCounter(currentMinute, 1);
            }
            existing.count.incrementAndGet();
            return existing;
        });

        int limit = DEFAULT_LIMIT_PER_MINUTE;
        int currentCount = counter.count.get();

        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, limit - currentCount)));

        if (currentCount > limit) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", "60");

            String requestId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE_REQUEST_ID);
            if (requestId == null) requestId = request.getHeader(CorrelationIdFilter.HEADER_REQUEST_ID);
            if (requestId == null) requestId = "n/a";

            ExternalApiResponse<Void> errorResponse = ExternalApiResponse.error(
                    "RATE_LIMIT_EXCEEDED",
                    "Rate limit exceeded. Maximum " + limit + " requests per minute allowed.",
                    "Please wait 60 seconds before sending additional requests.",
                    requestId
            );

            response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
            response.getWriter().flush();
            return;
        }

        // Periodic cleanup of stale counters if size grows
        if (clientCounters.size() > 5000) {
            clientCounters.entrySet().removeIf(entry -> entry.getValue().minute.get() < currentMinute - 5);
        }

        filterChain.doFilter(request, response);
    }

    private String resolveClientIdentifier(HttpServletRequest request) {
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return "key:" + ApiKeyService.hashKey(apiKey.trim());
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return "token:" + authHeader.substring(7).trim().hashCode();
        }

        String clientIp = request.getHeader("X-Forwarded-For");
        if (clientIp == null || clientIp.isBlank()) {
            clientIp = request.getRemoteAddr();
        } else {
            clientIp = clientIp.split(",")[0].trim();
        }
        return "ip:" + clientIp;
    }

    private static class RequestCounter {
        final AtomicLong minute;
        final AtomicInteger count;

        RequestCounter(long minute, int initialCount) {
            this.minute = new AtomicLong(minute);
            this.count = new AtomicInteger(initialCount);
        }
    }
}
