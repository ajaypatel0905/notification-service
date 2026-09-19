package com.ajaypatel.notify.inbox;

import com.ajaypatel.notify.common.error.NotFoundException;
import com.ajaypatel.notify.notification.NotificationDtos.PageResponse;
import com.ajaypatel.notify.security.CurrentPrincipal;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inbox")
@Validated
@Tag(name = "In-app inbox", description = "Read side of the IN_APP channel")
public class InboxController {
    private final InboxMessageRepository inbox;
    private final Clock clock;

    public InboxController(InboxMessageRepository inbox, Clock clock) {
        this.inbox = inbox;
        this.clock = clock;
    }

    public record InboxMessageResponse(UUID id, UUID notificationId, String recipient, String subject, String body,
                                       Instant readAt, Instant createdAt) {
        static InboxMessageResponse from(InboxMessage m) {
            return new InboxMessageResponse(m.getId(), m.getNotificationId(), m.getRecipient(), m.getSubject(),
                    m.getBody(), m.getReadAt(), m.getCreatedAt());
        }
    }

    @GetMapping("/{recipient}")
    public PageResponse<InboxMessageResponse> list(@PathVariable String recipient,
                                                   @RequestParam(defaultValue = "false") boolean unreadOnly,
                                                   @RequestParam(defaultValue = "0") @Min(0) int page,
                                                   @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        UUID tenantId = CurrentPrincipal.get().requireTenantId();
        Page<InboxMessage> p = unreadOnly
                ? inbox.findByTenantIdAndRecipientAndReadAtIsNullOrderByCreatedAtDesc(tenantId, recipient, PageRequest.of(page, size))
                : inbox.findByTenantIdAndRecipientOrderByCreatedAtDesc(tenantId, recipient, PageRequest.of(page, size));
        return new PageResponse<>(p.getContent().stream().map(InboxMessageResponse::from).toList(),
                p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    @GetMapping("/{recipient}/unread-count")
    public Map<String, Long> unreadCount(@PathVariable String recipient) {
        return Map.of("unread", inbox.countByTenantIdAndRecipientAndReadAtIsNull(CurrentPrincipal.get().requireTenantId(), recipient));
    }

    @PostMapping("/messages/{id}/read")
    @Transactional
    public InboxMessageResponse markRead(@PathVariable UUID id) {
        InboxMessage m = inbox.findByIdAndTenantId(id, CurrentPrincipal.get().requireTenantId())
                .orElseThrow(() -> NotFoundException.of("Inbox message", id));
        if (m.getReadAt() == null) {
            m.setReadAt(clock.instant());
        }
        return InboxMessageResponse.from(m);
    }
}
