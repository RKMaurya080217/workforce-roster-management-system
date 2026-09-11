package com.weeklyroster.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.weeklyroster.dto.external.v1.ExternalApiResponse;
import com.weeklyroster.entity.ApiClient;
import com.weeklyroster.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();

        // Only process external API endpoints
        if (!uri.startsWith("/api/external/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String apiKey = extractApiKey(request);

        if (apiKey != null && !apiKey.isBlank()) {
            Optional<ApiClient> clientOpt = apiKeyService.validateApiKey(apiKey);
            if (clientOpt.isPresent()) {
                ApiClient client = clientOpt.get();
                Set<String> scopes = parseScopes(client.getScopes());

                List<GrantedAuthority> authorities = new ArrayList<>();
                authorities.add(new SimpleGrantedAuthority("ROLE_EXTERNAL_CLIENT"));
                for (String scope : scopes) {
                    authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
                }

                ApiKeyAuthenticationToken authentication = new ApiKeyAuthenticationToken(
                        client.getClientName(),
                        client.getApiKeyHash(),
                        scopes,
                        authorities
                );

                SecurityContextHolder.getContext().setAuthentication(authentication);
                filterChain.doFilter(request, response);
                return;
            } else {
                // Explicit API key was provided but is invalid or inactive
                sendUnauthorizedResponse(request, response);
                return;
            }
        }

        // Check if an existing authentication (e.g. Admin JWT via Swagger) is already present in security context
        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        // Return 401 Unauthorized JSON envelope
        sendUnauthorizedResponse(request, response);
    }

    private String extractApiKey(HttpServletRequest request) {
        String key = request.getHeader("X-API-Key");
        if (key != null && !key.isBlank()) {
            return key.trim();
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }

        String queryKey = request.getParameter("apiKey");
        if (queryKey != null && !queryKey.isBlank()) {
            return queryKey.trim();
        }

        return null;
    }

    private Set<String> parseScopes(String scopesString) {
        if (scopesString == null || scopesString.isBlank()) {
            return new HashSet<>(Arrays.asList("ROSTER_READ", "EMPLOYEE_READ", "SHIFT_READ", "LEAVE_READ"));
        }
        return Arrays.stream(scopesString.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .collect(Collectors.toSet());
    }

    private void sendUnauthorizedResponse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        String requestId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE_REQUEST_ID);
        if (requestId == null) requestId = request.getHeader(CorrelationIdFilter.HEADER_REQUEST_ID);
        if (requestId == null) requestId = "n/a";

        ExternalApiResponse<Void> errorResponse = ExternalApiResponse.error(
                "UNAUTHORIZED",
                "Authentication required. Provide a valid API key via 'X-API-Key' header or 'Authorization: Bearer <key>'.",
                "Unauthorized access to " + request.getRequestURI(),
                requestId
        );

        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
        response.getWriter().flush();
    }
}
