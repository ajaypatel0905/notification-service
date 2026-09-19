package com.ajaypatel.notify.dispatch;

import com.ajaypatel.notify.notification.Notification;
import com.ajaypatel.notify.notification.NotificationAuditor;
import com.ajaypatel.notify.notification.NotificationEvent;
import com.ajaypatel.notify.notification.NotificationRepository;
import com.ajaypatel.notify.notification.NotificationStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Moves SCHEDULED notifications whose time has come into the QUEUED pool the claimer reads. */
@Component
@ConditionalOnProperty(prefix = "notify.dispatch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulePromoter {
    private final NotificationRepository notifications;
    private final NotificationAuditor auditor;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SchedulePromoter(NotificationRepository notifications, NotificationAuditor auditor, TransactionTemplate tx, Clock clock) {
        this.notifications = notifications;
        this.auditor = auditor;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${notify.dispatch.schedule-promoter-interval-ms}")
    public void promote() {
        promoteOnce();
    }

    public int promoteOnce() {
        Instant now = clock.instant();
        int count = 0;
        for (UUID id : notifications.findDueScheduledIds(now)) {
            Boolean done = tx.execute(s -> {
                Notification n = notifications.lockById(id).orElse(null);
                if (n == null || n.getStatus() != NotificationStatus.SCHEDULED) {
                    return false;
                }
                n.setNextAttemptAt(now);
                auditor.transition(n, NotificationStatus.QUEUED, NotificationEvent.Type.PROMOTED, "scheduler",
                        "scheduled time " + n.getScheduledAt() + " reached");
                return true;
            });
            if (Boolean.TRUE.equals(done)) {
                count++;
            }
        }
        return count;
    }
}
