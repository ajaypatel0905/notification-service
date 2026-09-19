package com.ajaypatel.notify.tenant;

import com.ajaypatel.notify.common.error.ConflictException;
import com.ajaypatel.notify.common.error.NotFoundException;
import com.ajaypatel.notify.common.error.ValidationException;
import com.ajaypatel.notify.tenant.TenantDtos.CreateTenantRequest;
import com.ajaypatel.notify.tenant.TenantDtos.UpdateTenantRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TenantService {
    private final TenantRepository tenants;
    private final PlatformSettingsService settings;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public TenantService(TenantRepository tenants, PlatformSettingsService settings,
                         ApplicationEventPublisher events, Clock clock) {
        this.tenants = tenants;
        this.settings = settings;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Tenant create(CreateTenantRequest req) {
        tenants.findBySlug(req.slug()).ifPresent(t -> {
            throw new ConflictException("Tenant slug already exists: " + req.slug());
        });
        validateRate(req.rateLimitPerSecond());
        Instant now = clock.instant();
        Tenant t = new Tenant();
        t.setId(UUID.randomUUID());
        t.setSlug(req.slug());
        t.setName(req.name());
        t.setStatus(TenantStatus.ACTIVE);
        t.setRateLimitPerSecond(req.rateLimitPerSecond());
        t.setRateLimitBurst(req.rateLimitBurst());
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return tenants.save(t);
    }

    @Transactional
    public Tenant update(UUID id, UpdateTenantRequest req) {
        Tenant t = get(id);
        if (req.name() != null) {
            t.setName(req.name());
        }
        if (req.rateLimitPerSecond() != null) {
            validateRate(req.rateLimitPerSecond());
            t.setRateLimitPerSecond(req.rateLimitPerSecond());
        }
        if (req.rateLimitBurst() != null) {
            t.setRateLimitBurst(req.rateLimitBurst());
        }
        t.setUpdatedAt(clock.instant());
        events.publishEvent(new TenantChangedEvent(t.getId()));
        return t;
    }

    @Transactional
    public Tenant setStatus(UUID id, TenantStatus status) {
        Tenant t = get(id);
        t.setStatus(status);
        t.setUpdatedAt(clock.instant());
        events.publishEvent(new TenantChangedEvent(t.getId()));
        return t;
    }

    @Transactional(readOnly = true)
    public Tenant get(UUID id) {
        return tenants.findById(id).orElseThrow(() -> NotFoundException.of("Tenant", id));
    }

    @Transactional(readOnly = true)
    public List<Tenant> list() {
        return tenants.findAll();
    }

    private void validateRate(Integer rate) {
        if (rate != null && rate > settings.get().maxRateLimitPerSecond()) {
            throw new ValidationException("rateLimitPerSecond exceeds platform maximum "
                    + settings.get().maxRateLimitPerSecond());
        }
    }

    /** Fired when limits or status change so in-memory rate limiters drop cached state. */
    public record TenantChangedEvent(UUID tenantId) {}
}
