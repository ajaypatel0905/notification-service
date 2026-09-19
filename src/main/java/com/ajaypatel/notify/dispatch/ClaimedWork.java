package com.ajaypatel.notify.dispatch;

import com.ajaypatel.notify.channel.Channel;

import java.util.UUID;

public record ClaimedWork(UUID notificationId, UUID tenantId, Channel channel) {
}
