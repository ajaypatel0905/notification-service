package com.ajaypatel.notify.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentPrincipal {
    private CurrentPrincipal() {}

    public static ApiPrincipal get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof ApiPrincipal p)) {
            throw new IllegalStateException("No authenticated API principal");
        }
        return p;
    }
}
