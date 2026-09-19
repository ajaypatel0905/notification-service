package com.ajaypatel.notify.notification;

import org.springframework.stereotype.Component;

import java.time.Clock;

/** Writes the audit row for a transition and applies it to the entity in one place. */
@Component
public class NotificationAuditor {
    private final NotificationEventRepository events;
    private final Clock clock;

    public NotificationAuditor(NotificationEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    public void transition(Notification n, NotificationStatus to, NotificationEvent.Type type, String actor, String detail) {
        NotificationStatus from = n.getStatus();
        NotificationStateMachine.assertTransition(from, to);
        n.setStatus(to);
        n.setUpdatedAt(clock.instant());
        record(n, type, from, to, actor, detail);
    }

    public void record(Notification n, NotificationEvent.Type type, NotificationStatus from, NotificationStatus to,
                       String actor, String detail) {
        NotificationEvent e = new NotificationEvent();
        e.setNotificationId(n.getId());
        e.setTenantId(n.getTenantId());
        e.setEventType(type);
        e.setFromStatus(from);
        e.setToStatus(to);
        e.setActor(actor);
        e.setDetail(detail);
        e.setOccurredAt(clock.instant());
        events.save(e);
    }
}
