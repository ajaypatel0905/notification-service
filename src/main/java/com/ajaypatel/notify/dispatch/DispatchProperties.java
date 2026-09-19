package com.ajaypatel.notify.dispatch;

import com.ajaypatel.notify.channel.Channel;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

@ConfigurationProperties(prefix = "notify.dispatch")
public record DispatchProperties(boolean enabled, long pollIntervalMs, int claimBatchPerTenant, Duration leaseDuration,
                                 long leaseReaperIntervalMs, long schedulePromoterIntervalMs,
                                 Map<Channel, Integer> pools, int queueCapacityPerPool) {
    public int poolSize(Channel c) {
        return pools == null ? 4 : pools.getOrDefault(c, 4);
    }
}
