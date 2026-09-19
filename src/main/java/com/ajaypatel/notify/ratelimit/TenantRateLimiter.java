package com.ajaypatel.notify.ratelimit;

import com.ajaypatel.notify.tenant.PlatformSettings;
import com.ajaypatel.notify.tenant.PlatformSettingsService;
import com.ajaypatel.notify.tenant.Tenant;
import com.ajaypatel.notify.tenant.TenantService.TenantChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One token bucket per tenant, built lazily from the tenant's override or the platform default.
 * The dispatcher asks it how many notifications it may claim for a tenant this cycle, so the limit
 * is enforced before work is handed to a worker rather than by rejecting mid-flight.
 */
@Component
public class TenantRateLimiter {
    private final PlatformSettingsService settings;
    private final Map<UUID, Entry> buckets = new ConcurrentHashMap<>();

    private record Entry(int rate, int burst, TokenBucket bucket) {}

    public TenantRateLimiter(PlatformSettingsService settings) {
        this.settings = settings;
    }

    public int tryAcquireUpTo(Tenant tenant, int wanted) {
        return bucketFor(tenant).tryAcquireUpTo(wanted);
    }

    public boolean tryAcquire(Tenant tenant) {
        return bucketFor(tenant).tryAcquire();
    }

    public long millisUntilNextToken(Tenant tenant) {
        return bucketFor(tenant).millisUntilNextToken();
    }

    public double availableTokens(Tenant tenant) {
        return bucketFor(tenant).availableTokens();
    }

    @EventListener
    public void onTenantChanged(TenantChangedEvent e) {
        buckets.remove(e.tenantId());
    }

    public void reset() {
        buckets.clear();
    }

    private TokenBucket bucketFor(Tenant tenant) {
        PlatformSettings s = settings.get();
        int rate = tenant.getRateLimitPerSecond() != null ? tenant.getRateLimitPerSecond() : s.defaultRateLimitPerSecond();
        int burst = tenant.getRateLimitBurst() != null ? tenant.getRateLimitBurst() : s.defaultRateLimitBurst();
        Entry e = buckets.compute(tenant.getId(), (id, cur) ->
                cur != null && cur.rate == rate && cur.burst == burst
                        ? cur
                        : new Entry(rate, burst, new TokenBucket(rate, burst, System::nanoTime)));
        return e.bucket();
    }
}
