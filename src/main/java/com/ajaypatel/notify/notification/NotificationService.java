package com.ajaypatel.notify.notification;

import com.ajaypatel.notify.channel.Channel;
import com.ajaypatel.notify.channel.ChannelConfig;
import com.ajaypatel.notify.channel.ChannelConfigService;
import com.ajaypatel.notify.common.error.ConflictException;
import com.ajaypatel.notify.common.error.NotFoundException;
import com.ajaypatel.notify.common.error.ValidationException;
import com.ajaypatel.notify.notification.NotificationDtos.SendRequest;
import com.ajaypatel.notify.template.Template;
import com.ajaypatel.notify.template.TemplateDtos.RenderResponse;
import com.ajaypatel.notify.template.TemplateService;
import com.ajaypatel.notify.tenant.PlatformSettingsService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Intake side of the pipeline. Rendering happens here, at accept time, so a bad request fails fast
 * with a 400 and what gets sent later is exactly what was accepted, even if the template changes.
 */
@Service
public class NotificationService {
    private static final Duration MAX_SCHEDULE_AHEAD = Duration.ofDays(30);

    private final NotificationRepository notifications;
    private final DeliveryAttemptRepository attempts;
    private final NotificationEventRepository events;
    private final NotificationAuditor auditor;
    private final TemplateService templates;
    private final ChannelConfigService channelConfigs;
    private final PlatformSettingsService settings;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, DeliveryAttemptRepository attempts,
                               NotificationEventRepository events, NotificationAuditor auditor,
                               TemplateService templates, ChannelConfigService channelConfigs,
                               PlatformSettingsService settings, Clock clock) {
        this.notifications = notifications;
        this.attempts = attempts;
        this.events = events;
        this.auditor = auditor;
        this.templates = templates;
        this.channelConfigs = channelConfigs;
        this.settings = settings;
        this.clock = clock;
    }

    public record AcceptResult(Notification notification, boolean duplicate) {}

    /**
     * Idempotent accept. A repeated idempotency key returns the original notification without creating a
     * second one; the unique constraint is the backstop for two concurrent first requests.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AcceptResult accept(UUID tenantId, SendRequest req, String headerIdempotencyKey) {
        String idemKey = firstNonBlank(req.idempotencyKey(), headerIdempotencyKey);
        if (idemKey == null) {
            idemKey = "auto-" + UUID.randomUUID();
        } else {
            Optional<Notification> existing = notifications.findByTenantIdAndIdempotencyKey(tenantId, idemKey);
            if (existing.isPresent()) {
                return new AcceptResult(existing.get(), true);
            }
        }

        ChannelConfig config = channelConfigs.find(tenantId, req.channel())
                .orElseThrow(() -> new ValidationException("Channel " + req.channel() + " is not configured for this tenant"));
        if (!config.isEnabled()) {
            throw new ValidationException("Channel " + req.channel() + " is disabled for this tenant");
        }

        Instant now = clock.instant();
        Notification n = new Notification();
        n.setId(UUID.randomUUID());
        n.setTenantId(tenantId);
        n.setChannel(req.channel());
        n.setRecipient(req.recipient().trim());
        n.setIdempotencyKey(idemKey);
        n.setVariables(req.variables());
        n.setMetadata(req.metadata());
        n.setPriority((short) (req.priority() == null ? 0 : req.priority()));
        n.setMaxAttempts(config.getMaxAttempts() != null ? config.getMaxAttempts() : settings.get().defaultMaxAttempts());
        n.setCreatedAt(now);
        n.setUpdatedAt(now);

        applyContent(tenantId, req, n);

        if (req.scheduledAt() != null && req.scheduledAt().isAfter(now)) {
            if (req.scheduledAt().isAfter(now.plus(MAX_SCHEDULE_AHEAD))) {
                throw new ValidationException("scheduledAt cannot be more than 30 days ahead");
            }
            n.setStatus(NotificationStatus.SCHEDULED);
            n.setScheduledAt(req.scheduledAt());
            n.setNextAttemptAt(req.scheduledAt());
        } else {
            n.setStatus(NotificationStatus.QUEUED);
            n.setNextAttemptAt(now);
        }

        try {
            notifications.saveAndFlush(n);
        } catch (DataIntegrityViolationException e) {
            Notification winner = notifications.findByTenantIdAndIdempotencyKey(tenantId, idemKey)
                    .orElseThrow(() -> e);
            return new AcceptResult(winner, true);
        }
        auditor.record(n, n.getStatus() == NotificationStatus.SCHEDULED
                ? NotificationEvent.Type.SCHEDULED : NotificationEvent.Type.ACCEPTED, null, n.getStatus(), "api",
                n.getStatus() == NotificationStatus.SCHEDULED ? "scheduled for " + n.getScheduledAt() : null);
        return new AcceptResult(n, false);
    }

    private void applyContent(UUID tenantId, SendRequest req, Notification n) {
        boolean hasTemplate = req.templateCode() != null && !req.templateCode().isBlank();
        boolean hasRaw = req.body() != null && !req.body().isBlank();
        if (hasTemplate == hasRaw) {
            throw new ValidationException("Provide exactly one of templateCode or body");
        }
        if (hasTemplate) {
            Template t = templates.getActive(tenantId, req.templateCode(), req.channel());
            RenderResponse rendered = templates.render(t, req.variables());
            n.setTemplateId(t.getId());
            n.setTemplateVersion(t.getVersion());
            n.setSubject(rendered.subject());
            n.setBody(rendered.body());
        } else {
            if (req.channel() == Channel.EMAIL && (req.subject() == null || req.subject().isBlank())) {
                throw new ValidationException("EMAIL notifications require a subject");
            }
            if (!req.channel().supportsSubject() && req.subject() != null && !req.subject().isBlank()) {
                throw new ValidationException("Channel " + req.channel() + " does not support a subject");
            }
            n.setSubject(req.subject());
            n.setBody(req.body());
        }
    }

    @Transactional
    public Notification cancel(UUID tenantId, UUID id) {
        Notification n = notifications.lockById(id).filter(x -> x.getTenantId().equals(tenantId))
                .orElseThrow(() -> NotFoundException.of("Notification", id));
        if (n.getStatus() == NotificationStatus.CANCELLED) {
            return n;
        }
        if (!NotificationStateMachine.canTransition(n.getStatus(), NotificationStatus.CANCELLED)) {
            throw new ConflictException("Notification in status " + n.getStatus() + " cannot be cancelled");
        }
        auditor.transition(n, NotificationStatus.CANCELLED, NotificationEvent.Type.CANCELLED, "api", null);
        return n;
    }

    @Transactional(readOnly = true)
    public Notification get(UUID tenantId, UUID id) {
        return notifications.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> NotFoundException.of("Notification", id));
    }

    @Transactional(readOnly = true)
    public List<DeliveryAttempt> attempts(UUID notificationId) {
        return attempts.findByNotificationIdOrderByAttemptNo(notificationId);
    }

    @Transactional(readOnly = true)
    public List<NotificationEvent> events(UUID notificationId) {
        return events.findByNotificationIdOrderByIdAsc(notificationId);
    }

    public record Filter(NotificationStatus status, Channel channel, String recipient, Instant from, Instant to,
                         String idempotencyKey) {}

    @Transactional(readOnly = true)
    public Page<Notification> search(UUID tenantId, Filter f, int page, int size) {
        Specification<Notification> spec = (root, q, cb) -> {
            List<jakarta.persistence.criteria.Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("tenantId"), tenantId));
            if (f.status() != null) {
                ps.add(cb.equal(root.get("status"), f.status()));
            }
            if (f.channel() != null) {
                ps.add(cb.equal(root.get("channel"), f.channel()));
            }
            if (f.recipient() != null && !f.recipient().isBlank()) {
                ps.add(cb.equal(root.get("recipient"), f.recipient().trim()));
            }
            if (f.idempotencyKey() != null && !f.idempotencyKey().isBlank()) {
                ps.add(cb.equal(root.get("idempotencyKey"), f.idempotencyKey()));
            }
            if (f.from() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.from()));
            }
            if (f.to() != null) {
                ps.add(cb.lessThan(root.get("createdAt"), f.to()));
            }
            return cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        return notifications.findAll(spec, PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @Transactional(readOnly = true)
    public Map<NotificationStatus, Long> statusCounts(UUID tenantId) {
        Map<NotificationStatus, Long> out = new HashMap<>();
        for (Object[] row : notifications.countByStatus(tenantId)) {
            out.put(NotificationStatus.valueOf((String) row[0]), ((Number) row[1]).longValue());
        }
        return out;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }
}
