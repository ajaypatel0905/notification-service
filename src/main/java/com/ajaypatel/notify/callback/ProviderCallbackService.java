package com.ajaypatel.notify.callback;

import com.ajaypatel.notify.notification.Notification;
import com.ajaypatel.notify.notification.NotificationAuditor;
import com.ajaypatel.notify.notification.NotificationEvent;
import com.ajaypatel.notify.notification.NotificationRepository;
import com.ajaypatel.notify.notification.NotificationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * Applies a vendor's delivery receipt. Idempotent: a receipt for a notification already in a terminal
 * state is acknowledged and ignored, because vendors retry webhooks.
 */
@Service
public class ProviderCallbackService {
    private static final Logger log = LoggerFactory.getLogger(ProviderCallbackService.class);

    public enum ReceiptStatus { DELIVERED, FAILED }

    public enum Result { APPLIED, ALREADY_FINAL, UNKNOWN_MESSAGE, IGNORED }

    private final NotificationRepository notifications;
    private final NotificationAuditor auditor;
    private final Clock clock;

    public ProviderCallbackService(NotificationRepository notifications, NotificationAuditor auditor, Clock clock) {
        this.notifications = notifications;
        this.auditor = auditor;
        this.clock = clock;
    }

    @Transactional
    public Result apply(String provider, String providerMessageId, ReceiptStatus status, String reason) {
        Optional<Notification> found = notifications.findByProviderMessageId(providerMessageId);
        if (found.isEmpty()) {
            log.info("Callback from {} for unknown message {}", provider, providerMessageId);
            return Result.UNKNOWN_MESSAGE;
        }
        Notification n = notifications.lockById(found.get().getId()).orElseThrow();
        if (n.getStatus().isTerminal()) {
            return Result.ALREADY_FINAL;
        }
        if (n.getStatus() != NotificationStatus.SENT) {
            return Result.IGNORED;
        }
        String actor = "provider:" + provider;
        if (status == ReceiptStatus.DELIVERED) {
            n.setDeliveredAt(clock.instant());
            auditor.transition(n, NotificationStatus.DELIVERED, NotificationEvent.Type.PROVIDER_CALLBACK, actor, reason);
        } else {
            n.setFailedAt(clock.instant());
            n.setLastError("PROVIDER_REPORTED_FAILURE: " + (reason == null ? "no reason" : reason));
            auditor.transition(n, NotificationStatus.FAILED, NotificationEvent.Type.PROVIDER_CALLBACK, actor, reason);
        }
        return Result.APPLIED;
    }
}
