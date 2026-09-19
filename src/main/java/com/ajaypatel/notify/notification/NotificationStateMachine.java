package com.ajaypatel.notify.notification;

import com.ajaypatel.notify.common.error.InvalidStateTransitionException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.ajaypatel.notify.notification.NotificationStatus.CANCELLED;
import static com.ajaypatel.notify.notification.NotificationStatus.DELIVERED;
import static com.ajaypatel.notify.notification.NotificationStatus.FAILED;
import static com.ajaypatel.notify.notification.NotificationStatus.PROCESSING;
import static com.ajaypatel.notify.notification.NotificationStatus.QUEUED;
import static com.ajaypatel.notify.notification.NotificationStatus.SCHEDULED;
import static com.ajaypatel.notify.notification.NotificationStatus.SENT;

/** Single source of truth for legal status transitions. */
public final class NotificationStateMachine {
    private static final Map<NotificationStatus, Set<NotificationStatus>> ALLOWED = new EnumMap<>(NotificationStatus.class);

    static {
        ALLOWED.put(SCHEDULED, EnumSet.of(QUEUED, CANCELLED));
        ALLOWED.put(QUEUED, EnumSet.of(PROCESSING, CANCELLED));
        ALLOWED.put(PROCESSING, EnumSet.of(SENT, DELIVERED, FAILED, QUEUED));
        ALLOWED.put(SENT, EnumSet.of(DELIVERED, FAILED));
        ALLOWED.put(DELIVERED, EnumSet.noneOf(NotificationStatus.class));
        ALLOWED.put(FAILED, EnumSet.noneOf(NotificationStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(NotificationStatus.class));
    }

    private NotificationStateMachine() {}

    public static boolean canTransition(NotificationStatus from, NotificationStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static void assertTransition(NotificationStatus from, NotificationStatus to) {
        if (!canTransition(from, to)) {
            throw new InvalidStateTransitionException("Cannot move notification from " + from + " to " + to);
        }
    }
}
