package com.weeklyroster.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.Set;

public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final String clientName;
    private final String apiKeyHash;
    private final Set<String> scopes;

    public ApiKeyAuthenticationToken(String clientName, String apiKeyHash, Set<String> scopes,
                                     Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.clientName = clientName;
        this.apiKeyHash = apiKeyHash;
        this.scopes = scopes;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return apiKeyHash;
    }

    @Override
    public Object getPrincipal() {
        return clientName;
    }

    public String getClientName() {
        return clientName;
    }

    public Set<String> getScopes() {
        return scopes;
    }

    public boolean hasScope(String scope) {
        if (scopes == null) return false;
        String normalized = scope.toUpperCase().replace("SCOPE_", "");
        return scopes.contains(normalized) || scopes.contains("*") || scopes.contains("ALL");
    }
}
