package com.ajaypatel.notify.security;

import java.util.UUID;

/** Authenticated caller. {@code tenantId} is null for platform admins. */
public record ApiPrincipal(UUID keyId, ApiRole role, UUID tenantId, String label) {
    public boolean isPlatformAdmin() {
        return role == ApiRole.PLATFORM_ADMIN;
    }

    public UUID requireTenantId() {
        if (tenantId == null) {
            throw new IllegalStateException("Operation requires a tenant-scoped API key");
        }
        return tenantId;
    }
}
