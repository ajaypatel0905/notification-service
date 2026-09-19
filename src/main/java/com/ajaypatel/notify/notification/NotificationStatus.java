package com.ajaypatel.notify.notification;

import java.util.EnumSet;
import java.util.Set;

public enum NotificationStatus {
    SCHEDULED, QUEUED, PROCESSING, SENT, DELIVERED, FAILED, CANCELLED;

    private static final Set<NotificationStatus> TERMINAL = EnumSet.of(DELIVERED, FAILED, CANCELLED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
