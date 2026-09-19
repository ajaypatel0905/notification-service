package com.ajaypatel.notify.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-API-Key";

    private final ApiKeyService apiKeys;
    private final TenantAccessGuard tenantGuard;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeys, TenantAccessGuard tenantGuard) {
        this.apiKeys = apiKeys;
        this.tenantGuard = tenantGuard;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String raw = request.getHeader(HEADER);
        if (raw != null && !raw.isBlank()) {
            apiKeys.authenticate(raw.trim()).ifPresent(key -> {
                if (key.getTenantId() != null && !tenantGuard.isTenantUsable(key.getTenantId())) {
                    return;
                }
                ApiPrincipal principal = new ApiPrincipal(key.getId(), key.getRole(), key.getTenantId(), key.getLabel());
                var auth = new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + key.getRole().name())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(request, response);
    }
}
