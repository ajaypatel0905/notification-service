package com.ajaypatel.notify.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Append-only audit trail of everything that happened to a notification. */
@Entity
@Table(name = "notification_events")
@Getter
@Setter
public class NotificationEvent {
    public enum Type {
        ACCEPTED, SCHEDULED, PROMOTED, CLAIMED, SENT, DELIVERED, RETRY_SCHEDULED, FAILED, CANCELLED,
        LEASE_EXPIRED, RATE_LIMITED, PROVIDER_CALLBACK, REQUEUED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private Type eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 16)
    private NotificationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 16)
    private NotificationStatus toStatus;

    @Column(nullable = false, length = 64)
    private String actor;

    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
