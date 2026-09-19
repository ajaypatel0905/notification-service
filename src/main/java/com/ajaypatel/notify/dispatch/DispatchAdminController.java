package com.ajaypatel.notify.dispatch;

import com.ajaypatel.notify.notification.NotificationRepository;
import com.ajaypatel.notify.ratelimit.TenantRateLimiter;
import com.ajaypatel.notify.tenant.Tenant;
import com.ajaypatel.notify.tenant.TenantRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/dispatch")
@Tag(name = "Platform admin", description = "Dispatcher visibility and control")
public class DispatchAdminController {
    private final WorkerPools pools;
    private final NotificationRepository notifications;
    private final TenantRepository tenants;
    private final TenantRateLimiter limiter;
    private final FairShareClaimer claimer;
    private final ObjectProvider<DispatchPoller> poller;

    public DispatchAdminController(WorkerPools pools, NotificationRepository notifications, TenantRepository tenants,
                                   TenantRateLimiter limiter, FairShareClaimer claimer, ObjectProvider<DispatchPoller> poller) {
        this.pools = pools;
        this.notifications = notifications;
        this.tenants = tenants;
        this.limiter = limiter;
        this.claimer = claimer;
        this.poller = poller;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        DispatchPoller p = poller.getIfAvailable();
        out.put("poller", p == null ? Map.of("enabled", false)
                : Map.of("enabled", true, "paused", p.isPaused(), "cycles", p.cycles(), "claimedTotal", p.claimedTotal()));
        out.put("pools", pools.stats());

        List<Map<String, Object>> backlog = new ArrayList<>();
        for (Object[] row : notifications.backlogByTenantChannel()) {
            backlog.add(Map.of("tenant", row[0], "channel", row[1], "status", row[2], "count", ((Number) row[3]).longValue()));
        }
        out.put("backlog", backlog);

        Map<String, Object> limiters = new LinkedHashMap<>();
        Map<java.util.UUID, Long> skips = claimer.rateLimitedSkips();
        for (Tenant t : tenants.findAll()) {
            limiters.put(t.getSlug(), Map.of(
                    "availableTokens", Math.floor(limiter.availableTokens(t)),
                    "rateLimitedCycles", skips.getOrDefault(t.getId(), 0L)));
        }
        out.put("rateLimiters", limiters);
        return out;
    }

    @PostMapping("/pause")
    public Map<String, Object> pause() {
        poller.ifAvailable(DispatchPoller::pause);
        return Map.of("paused", true);
    }

    @PostMapping("/resume")
    public Map<String, Object> resume() {
        poller.ifAvailable(DispatchPoller::resume);
        return Map.of("paused", false);
    }
}
