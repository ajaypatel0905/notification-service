package com.ajaypatel.notify.dispatch;

import com.ajaypatel.notify.channel.ChannelConfig;
import com.ajaypatel.notify.channel.ChannelConfigRepository;
import com.ajaypatel.notify.notification.DeliveryAttempt;
import com.ajaypatel.notify.notification.DeliveryAttemptRepository;
import com.ajaypatel.notify.notification.Notification;
import com.ajaypatel.notify.notification.NotificationAuditor;
import com.ajaypatel.notify.notification.NotificationEvent;
import com.ajaypatel.notify.notification.NotificationRepository;
import com.ajaypatel.notify.notification.NotificationStatus;
import com.ajaypatel.notify.provider.ChannelProvider;
import com.ajaypatel.notify.provider.ChannelProviderRegistry;
import com.ajaypatel.notify.provider.DeliveryRequest;
import com.ajaypatel.notify.provider.DeliveryResult;
import com.ajaypatel.notify.provider.PermanentDeliveryException;
import com.ajaypatel.notify.provider.TransientDeliveryException;
import com.ajaypatel.notify.tenant.Tenant;
import com.ajaypatel.notify.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Executes one leased notification. Three phases:
 * <ol>
 *   <li>short transaction: load and validate the lease, build the provider request</li>
 *   <li>no transaction: call the provider (network time must never pin a DB connection)</li>
 *   <li>short transaction: record the attempt, audit event and next state under a row lock</li>
 * </ol>
 * In-app delivery is DB-only, so it runs inside phase 3 to stay atomic with the state change.
 */
@Component
public class DeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
    static final String ACTOR = "dispatcher";

    private final NotificationRepository notifications;
    private final DeliveryAttemptRepository attempts;
    private final ChannelConfigRepository channelConfigs;
    private final TenantRepository tenants;
    private final ChannelProviderRegistry providers;
    private final NotificationAuditor auditor;
    private final ExponentialBackoffPolicy backoff;
    private final WorkerIdentity worker;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DeliveryWorker(NotificationRepository notifications, DeliveryAttemptRepository attempts,
                          ChannelConfigRepository channelConfigs, TenantRepository tenants,
                          ChannelProviderRegistry providers, NotificationAuditor auditor,
                          ExponentialBackoffPolicy backoff, WorkerIdentity worker, TransactionTemplate tx, Clock clock) {
        this.notifications = notifications;
        this.attempts = attempts;
        this.channelConfigs = channelConfigs;
        this.tenants = tenants;
        this.providers = providers;
        this.auditor = auditor;
        this.backoff = backoff;
        this.worker = worker;
        this.tx = tx;
        this.clock = clock;
    }

    private record Prepared(DeliveryRequest request, ChannelProvider provider) {}

    private sealed interface Outcome permits Success, Transient, Permanent {}
    private record Success(DeliveryResult result) implements Outcome {}
    private record Transient(String code, String message) implements Outcome {}
    private record Permanent(String code, String message) implements Outcome {}

    public void process(UUID notificationId) {
        Prepared prepared;
        try {
            prepared = tx.execute(s -> prepare(notificationId));
        } catch (RuntimeException e) {
            log.error("Failed to prepare notification {}", notificationId, e);
            return;
        }
        if (prepared == null) {
            return;
        }

        Instant started = clock.instant();
        Outcome outcome;
        if (prepared.provider().isTransactional()) {
            // DB-backed provider: the send is part of the completion transaction
            tx.executeWithoutResult(s -> complete(notificationId, prepared, invoke(prepared), started));
            return;
        }
        outcome = invoke(prepared);
        Outcome finalOutcome = outcome;
        tx.executeWithoutResult(s -> complete(notificationId, prepared, finalOutcome, started));
    }

    /** Hands a leased row back immediately (pool rejected it) instead of waiting for the lease to expire. */
    public void releaseLease(UUID notificationId, String reason) {
        tx.executeWithoutResult(s -> notifications.lockById(notificationId).ifPresent(n -> {
            if (n.getStatus() == NotificationStatus.PROCESSING && worker.id().equals(n.getLeasedBy())) {
                requeue(n, NotificationEvent.Type.REQUEUED, reason, Duration.ZERO);
            }
        }));
    }

    private Outcome invoke(Prepared p) {
        try {
            return new Success(p.provider().send(p.request()));
        } catch (TransientDeliveryException e) {
            return new Transient(e.code(), e.getMessage());
        } catch (PermanentDeliveryException e) {
            return new Permanent(e.code(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Provider {} threw unexpectedly for {}", p.provider().name(), p.request().notificationId(), e);
            return new Transient("PROVIDER_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Prepared prepare(UUID id) {
        Notification n = notifications.lockById(id).orElse(null);
        if (n == null || n.getStatus() != NotificationStatus.PROCESSING || !worker.id().equals(n.getLeasedBy())) {
            log.debug("Skipping {}: not leased by this worker", id);
            return null;
        }
        Tenant tenant = tenants.findById(n.getTenantId()).orElse(null);
        if (tenant == null || !tenant.isActive()) {
            requeue(n, NotificationEvent.Type.REQUEUED, "tenant suspended", Duration.ofSeconds(30));
            return null;
        }
        Optional<ChannelConfig> cfg = channelConfigs.findByTenantIdAndChannel(n.getTenantId(), n.getChannel());
        if (cfg.isEmpty() || !cfg.get().isEnabled()) {
            fail(n, "CHANNEL_DISABLED", "Channel " + n.getChannel() + " is not enabled for tenant", null, clock.instant());
            return null;
        }
        Optional<ChannelProvider> provider = providers.find(n.getChannel(), cfg.get().getProvider());
        if (provider.isEmpty()) {
            fail(n, "PROVIDER_MISSING", "Provider " + cfg.get().getProvider() + " is not available", null, clock.instant());
            return null;
        }
        int attemptNo = n.getAttemptCount() + 1;
        DeliveryRequest req = new DeliveryRequest(n.getId(), n.getTenantId(), n.getChannel(), n.getRecipient(),
                n.getSubject(), n.getBody(), n.getMetadata() == null ? Map.of() : n.getMetadata(),
                cfg.get().getSettings() == null ? Map.of() : cfg.get().getSettings(), attemptNo, n.getId().toString());
        return new Prepared(req, provider.get());
    }

    private void complete(UUID id, Prepared p, Outcome outcome, Instant started) {
        Instant now = clock.instant();
        Notification n = notifications.lockById(id).orElseThrow();
        boolean leaseHeld = n.getStatus() == NotificationStatus.PROCESSING && worker.id().equals(n.getLeasedBy());
        if (!leaseHeld) {
            // Lease was reaped mid-send. A success is still a success (provider dedups by idempotency key);
            // a failure is left for whoever holds the row now.
            if (outcome instanceof Success s && !n.getStatus().isTerminal() && n.getStatus() != NotificationStatus.SENT) {
                log.warn("Late success for {} after lease loss; applying", id);
                if (n.getStatus() == NotificationStatus.QUEUED) {
                    auditor.transition(n, NotificationStatus.PROCESSING, NotificationEvent.Type.CLAIMED, ACTOR, "late result");
                }
                applySuccess(n, p, s.result(), started, now);
            } else {
                log.warn("Dropping result for {} (status {}, leased by {})", id, n.getStatus(), n.getLeasedBy());
            }
            return;
        }

        switch (outcome) {
            case Success s -> applySuccess(n, p, s.result(), started, now);
            case Permanent f -> {
                recordAttempt(n, p, DeliveryAttempt.Outcome.PERMANENT_FAILURE, null, f.code(), f.message(), started, now);
                fail(n, f.code(), f.message(), null, now);
            }
            case Transient f -> {
                recordAttempt(n, p, DeliveryAttempt.Outcome.TRANSIENT_FAILURE, null, f.code(), f.message(), started, now);
                n.setLastError(f.code() + ": " + f.message());
                if (n.getAttemptCount() >= n.getMaxAttempts()) {
                    fail(n, f.code(), "Exhausted " + n.getMaxAttempts() + " attempts; last error: " + f.message(), null, now);
                } else {
                    Duration delay = backoff.nextDelay(n.getAttemptCount());
                    requeue(n, NotificationEvent.Type.RETRY_SCHEDULED,
                            "attempt " + n.getAttemptCount() + " failed (" + f.code() + "); retry in " + delay.toMillis() + "ms", delay);
                }
            }
        }
    }

    private void applySuccess(Notification n, Prepared p, DeliveryResult result, Instant started, Instant now) {
        recordAttempt(n, p, DeliveryAttempt.Outcome.SUCCESS, result.providerMessageId(), null, null, started, now);
        n.setProviderMessageId(result.providerMessageId());
        n.setLastError(null);
        n.setSentAt(now);
        clearLease(n);
        if (result.delivered()) {
            n.setDeliveredAt(now);
            auditor.transition(n, NotificationStatus.DELIVERED, NotificationEvent.Type.DELIVERED, ACTOR,
                    "delivered by " + p.provider().name());
        } else {
            auditor.transition(n, NotificationStatus.SENT, NotificationEvent.Type.SENT, ACTOR,
                    "accepted by " + p.provider().name() + " as " + result.providerMessageId());
        }
    }

    private void recordAttempt(Notification n, Prepared p, DeliveryAttempt.Outcome outcome, String providerMessageId,
                               String code, String message, Instant started, Instant finished) {
        n.setAttemptCount(n.getAttemptCount() + 1);
        DeliveryAttempt a = new DeliveryAttempt();
        a.setId(UUID.randomUUID());
        a.setNotificationId(n.getId());
        a.setTenantId(n.getTenantId());
        a.setAttemptNo(n.getAttemptCount());
        a.setOutcome(outcome);
        a.setProvider(p.provider().name());
        a.setProviderMessageId(providerMessageId);
        a.setErrorCode(code);
        a.setErrorMessage(message);
        a.setWorkerId(Thread.currentThread().getName());
        a.setStartedAt(started);
        a.setFinishedAt(finished);
        attempts.save(a);
    }

    private void fail(Notification n, String code, String message, DeliveryAttempt.Outcome ignored, Instant now) {
        n.setLastError(code + ": " + message);
        n.setFailedAt(now);
        clearLease(n);
        auditor.transition(n, NotificationStatus.FAILED, NotificationEvent.Type.FAILED, ACTOR, code + ": " + message);
    }

    private void requeue(Notification n, NotificationEvent.Type type, String detail, Duration delay) {
        clearLease(n);
        n.setNextAttemptAt(clock.instant().plus(delay));
        auditor.transition(n, NotificationStatus.QUEUED, type, ACTOR, detail);
    }

    private static void clearLease(Notification n) {
        n.setLeasedBy(null);
        n.setLeaseExpiresAt(null);
    }
}
