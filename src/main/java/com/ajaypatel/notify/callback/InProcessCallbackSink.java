package com.ajaypatel.notify.callback;

import com.ajaypatel.notify.provider.ProviderCallbackSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Simulated vendors "call back" through the same service the HTTP endpoint uses, after a delay,
 * so SENT to DELIVERED behaves like production without a second process.
 */
@Component
public class InProcessCallbackSink implements ProviderCallbackSink {
    private static final Logger log = LoggerFactory.getLogger(InProcessCallbackSink.class);

    private final ProviderCallbackService service;
    private final TaskScheduler scheduler;

    public InProcessCallbackSink(ProviderCallbackService service, TaskScheduler scheduler) {
        this.service = service;
        this.scheduler = scheduler;
    }

    @Override
    public void scheduleDelivered(String provider, String providerMessageId, long delayMs) {
        scheduler.schedule(() -> {
            try {
                service.apply(provider, providerMessageId, ProviderCallbackService.ReceiptStatus.DELIVERED, "vendor receipt");
            } catch (RuntimeException e) {
                log.warn("Simulated callback for {} failed: {}", providerMessageId, e.toString());
            }
        }, java.time.Instant.now().plus(Duration.ofMillis(Math.max(0, delayMs))));
    }
}
