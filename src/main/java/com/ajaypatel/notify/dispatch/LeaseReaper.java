package com.ajaypatel.notify.dispatch;

import com.ajaypatel.notify.notification.Notification;
import com.ajaypatel.notify.notification.NotificationAuditor;
import com.ajaypatel.notify.notification.NotificationEvent;
import com.ajaypatel.notify.notification.NotificationRepository;
import com.ajaypatel.notify.notification.NotificationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Returns rows whose worker died mid-flight to the queue. The attempt counter is not touched:
 * a crash is not the provider's fault. Provider-side idempotency keys make the re-send safe.
 */
@Component
@ConditionalOnProperty(prefix = "notify.dispatch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LeaseReaper {
    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final NotificationRepository notifications;
    private final NotificationAuditor auditor;
    private final TransactionTemplate tx;
    private final Clock clock;

    public LeaseReaper(NotificationRepository notifications, NotificationAuditor auditor, TransactionTemplate tx, Clock clock) {
        this.notifications = notifications;
        this.auditor = auditor;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${notify.dispatch.lease-reaper-interval-ms}")
    public void reap() {
        reapOnce();
    }

    public int reapOnce() {
        Instant now = clock.instant();
        List<UUID> expired = notifications.findExpiredLeases(now);
        int count = 0;
        for (UUID id : expired) {
            Boolean done = tx.execute(s -> {
                Notification n = notifications.lockById(id).orElse(null);
                if (n == null || n.getStatus() != NotificationStatus.PROCESSING
                        || n.getLeaseExpiresAt() == null || !n.getLeaseExpiresAt().isBefore(now)) {
                    return false;
                }
                String previous = n.getLeasedBy();
                n.setLeasedBy(null);
                n.setLeaseExpiresAt(null);
                n.setNextAttemptAt(now);
                auditor.transition(n, NotificationStatus.QUEUED, NotificationEvent.Type.LEASE_EXPIRED, "lease-reaper",
                        "lease held by " + previous + " expired");
                return true;
            });
            if (Boolean.TRUE.equals(done)) {
                count++;
            }
        }
        if (count > 0) {
            log.warn("Re-queued {} notifications with expired leases", count);
        }
        return count;
    }
}
