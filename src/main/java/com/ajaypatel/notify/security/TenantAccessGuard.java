package com.ajaypatel.notify.security;

import com.ajaypatel.notify.tenant.TenantRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Tenant keys keep working while a tenant is suspended (so the admin can read reports), but the tenant must exist. */
@Component
public class TenantAccessGuard {
    private final TenantRepository tenants;

    public TenantAccessGuard(TenantRepository tenants) {
        this.tenants = tenants;
    }

    public boolean isTenantUsable(UUID tenantId) {
        return tenants.existsById(tenantId);
    }
}
