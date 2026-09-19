package com.ajaypatel.notify.notification;

import com.ajaypatel.notify.notification.NotificationDtos.NotificationResponse;
import com.ajaypatel.notify.template.TemplateRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class NotificationMapper {
    private final TemplateRepository templates;

    public NotificationMapper(TemplateRepository templates) {
        this.templates = templates;
    }

    public NotificationResponse toResponse(Notification n) {
        return toResponse(n, new HashMap<>());
    }

    public NotificationResponse toResponse(Notification n, Map<UUID, String> templateCodeCache) {
        String code = null;
        if (n.getTemplateId() != null) {
            code = templateCodeCache.computeIfAbsent(n.getTemplateId(),
                    id -> templates.findById(id).map(t -> t.getCode()).orElse(null));
        }
        return new NotificationResponse(n.getId(), n.getChannel(), n.getRecipient(), n.getStatus(), code,
                n.getTemplateVersion(), n.getSubject(), n.getBody(), n.getIdempotencyKey(), n.getPriority(),
                n.getScheduledAt(), n.getNextAttemptAt(), n.getAttemptCount(), n.getMaxAttempts(),
                n.getProviderMessageId(), n.getLastError(), n.getMetadata(), n.getCreatedAt(), n.getUpdatedAt(),
                n.getSentAt(), n.getDeliveredAt(), n.getFailedAt());
    }
}
