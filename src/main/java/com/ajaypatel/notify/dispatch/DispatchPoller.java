package com.ajaypatel.notify.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Drives the loop: claim a fair batch, hand each item to its channel pool, repeat. */
@Component
@ConditionalOnProperty(prefix = "notify.dispatch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DispatchPoller {
    private static final Logger log = LoggerFactory.getLogger(DispatchPoller.class);

    private final FairShareClaimer claimer;
    private final WorkerPools pools;
    private final DeliveryWorker worker;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicLong cycles = new AtomicLong();
    private final AtomicLong claimedTotal = new AtomicLong();

    public DispatchPoller(FairShareClaimer claimer, WorkerPools pools, DeliveryWorker worker) {
        this.claimer = claimer;
        this.pools = pools;
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${notify.dispatch.poll-interval-ms}")
    public void poll() {
        if (paused.get()) {
            return;
        }
        pollOnce();
    }

    /** One claim-and-submit cycle. Public so tests can drive it deterministically and concurrently. */
    public int pollOnce() {
        List<ClaimedWork> batch;
        try {
            batch = claimer.claim();
        } catch (RuntimeException e) {
            log.error("Claim cycle failed", e);
            return 0;
        }
        cycles.incrementAndGet();
        for (ClaimedWork w : batch) {
            try {
                pools.submit(w.channel(), () -> worker.process(w.notificationId()));
            } catch (RejectedExecutionException e) {
                // Capacity was checked before claiming; concurrent claimers can still over-subscribe a pool.
                log.warn("Pool {} rejected {}; returning it to the queue", w.channel(), w.notificationId());
                worker.releaseLease(w.notificationId(), "pool " + w.channel() + " saturated");
            }
        }
        claimedTotal.addAndGet(batch.size());
        return batch.size();
    }

    public void pause() {
        paused.set(true);
    }

    public void resume() {
        paused.set(false);
    }

    public boolean isPaused() {
        return paused.get();
    }

    public long cycles() {
        return cycles.get();
    }

    public long claimedTotal() {
        return claimedTotal.get();
    }
}
