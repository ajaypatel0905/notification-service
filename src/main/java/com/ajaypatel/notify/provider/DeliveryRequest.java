package com.ajaypatel.notify.provider;

import com.ajaypatel.notify.channel.Channel;

import java.util.Map;
import java.util.UUID;

/**
 * What a provider needs to send one message. {@code idempotencyKey} is stable across retries of
 * the same notification so a provider that saw it before must return its earlier result.
 */
public record DeliveryRequest(UUID notificationId, UUID tenantId, Channel channel, String recipient,
                              String subject, String body, Map<String, Object> metadata,
                              Map<String, Object> providerSettings, int attemptNo, String idempotencyKey) {
}
