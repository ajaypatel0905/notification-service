package com.ajaypatel.notify.dispatch;

import com.ajaypatel.notify.channel.Channel;
import com.ajaypatel.notify.notification.NotificationRepository;
import com.ajaypatel.notify.ratelimit.TenantRateLimiter;
import com.ajaypatel.notify.tenant.Tenant;
import com.ajaypatel.notify.tenant.TenantRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decides who gets dispatch capacity this cycle.
 * <ol>
 *   <li>Find every tenant with due work and rotate the list so the tenant after last cycle's first goes first
 *       (round-robin: a tenant with 100k queued rows cannot monopolise the pool).</li>
 *   <li>Cap each tenant at {@code claimBatchPerTenant} per cycle and at the tokens its bucket currently holds
 *       (rate limit enforced before work is handed out, so nothing is claimed and then thrown back).</li>
 *   <li>Claim per channel, bounded by that channel pool's free capacity, with {@code FOR UPDATE SKIP LOCKED}
 *       so concurrent claimers never double-lease a row.</li>
 * </ol>
 */
@Component
public class FairShareClaimer {
    private final NotificationRepository notifications;
    private final TenantRepository tenants;
    private final TenantRateLimiter limiter;
    private final WorkerPools pools;
    private final DispatchProperties props;
    private final WorkerIdentity worker;
    private final EntityManager em;
    private final Clock clock;

    private final AtomicInteger cursor = new AtomicInteger();
    private final Map<UUID, AtomicLong> rateLimitedSkips = new ConcurrentHashMap<>();

    public FairShareClaimer(NotificationRepository notifications, TenantRepository tenants, TenantRateLimiter limiter,
                            WorkerPools pools, DispatchProperties props, WorkerIdentity worker, EntityManager em, Clock clock) {
        this.notifications = notifications;
        this.tenants = tenants;
        this.limiter = limiter;
        this.pools = pools;
        this.props = props;
        this.worker = worker;
        this.em = em;
        this.clock = clock;
    }

    @Transactional
    public List<ClaimedWork> claim() {
        Instant now = clock.instant();
        List<UUID> due = new ArrayList<>(notifications.findTenantsWithDueWork(now));
        if (due.isEmpty()) {
            return List.of();
        }
        Collections.sort(due);
        Collections.rotate(due, -(cursor.getAndIncrement() % due.size()));

        List<ClaimedWork> claimed = new ArrayList<>();
        Instant leaseUntil = now.plus(props.leaseDuration());
        for (UUID tenantId : due) {
            if (pools.totalFreeCapacity() - claimed.size() <= 0) {
                break;
            }
            Tenant tenant = tenants.findById(tenantId).orElse(null);
            if (tenant == null || !tenant.isActive()) {
                continue;
            }
            int tokens = (int) Math.floor(limiter.availableTokens(tenant));
            if (tokens <= 0) {
                rateLimitedSkips.computeIfAbsent(tenantId, k -> new AtomicLong()).incrementAndGet();
                continue;
            }
            int budget = Math.min(props.claimBatchPerTenant(), tokens);
            int taken = 0;
            for (Channel channel : Channel.values()) {
                if (budget - taken <= 0) {
                    break;
                }
                int room = Math.min(budget - taken, pools.freeCapacity(channel) - countFor(claimed, channel));
                if (room <= 0) {
                    continue;
                }
                List<UUID> ids = notifications.claimForTenantAndChannel(tenantId, channel.name(), worker.id(), leaseUntil, now, room);
                for (UUID id : ids) {
                    claimed.add(new ClaimedWork(id, tenantId, channel));
                }
                taken += ids.size();
            }
            if (taken > 0) {
                limiter.tryAcquireUpTo(tenant, taken);
            }
        }
        // native UPDATE ... RETURNING bypasses the persistence context; make sure nothing stale is flushed later
        em.clear();
        return claimed;
    }

    public Map<UUID, Long> rateLimitedSkips() {
        Map<UUID, Long> out = new java.util.HashMap<>();
        rateLimitedSkips.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    private static int countFor(List<ClaimedWork> claimed, Channel c) {
        int n = 0;
        for (ClaimedWork w : claimed) {
            if (w.channel() == c) {
                n++;
            }
        }
        return n;
    }
}
