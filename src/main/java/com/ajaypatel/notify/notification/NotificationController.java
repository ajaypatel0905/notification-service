package com.ajaypatel.notify.notification;

import com.ajaypatel.notify.channel.Channel;
import com.ajaypatel.notify.notification.NotificationDtos.AttemptResponse;
import com.ajaypatel.notify.notification.NotificationDtos.BatchSendRequest;
import com.ajaypatel.notify.notification.NotificationDtos.BatchSendResponse;
import com.ajaypatel.notify.notification.NotificationDtos.EventResponse;
import com.ajaypatel.notify.notification.NotificationDtos.NotificationDetailResponse;
import com.ajaypatel.notify.notification.NotificationDtos.NotificationResponse;
import com.ajaypatel.notify.notification.NotificationDtos.PageResponse;
import com.ajaypatel.notify.notification.NotificationDtos.SendRequest;
import com.ajaypatel.notify.notification.NotificationService.AcceptResult;
import com.ajaypatel.notify.security.CurrentPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@Validated
@Tag(name = "Notifications", description = "Tenant admin: send, schedule, cancel and inspect notifications")
public class NotificationController {
    private final NotificationService service;
    private final NotificationMapper mapper;

    public NotificationController(NotificationService service, NotificationMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping
    @Operation(summary = "Send or schedule one notification",
            description = "Returns 202 when accepted, 200 with the original when the idempotency key was seen before.")
    public ResponseEntity<NotificationResponse> send(@Valid @RequestBody SendRequest req,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String idemHeader) {
        AcceptResult r = service.accept(tenant(), req, idemHeader);
        return ResponseEntity.status(r.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(mapper.toResponse(r.notification()));
    }

    @PostMapping("/batch")
    @Operation(summary = "Send up to 1000 notifications; each item is accepted independently")
    public ResponseEntity<BatchSendResponse> sendBatch(@Valid @RequestBody BatchSendRequest req) {
        UUID tenantId = tenant();
        List<NotificationResponse> out = new ArrayList<>(req.notifications().size());
        Map<UUID, String> cache = new HashMap<>();
        int accepted = 0;
        int duplicates = 0;
        for (SendRequest item : req.notifications()) {
            AcceptResult r = service.accept(tenantId, item, null);
            if (r.duplicate()) {
                duplicates++;
            } else {
                accepted++;
            }
            out.add(mapper.toResponse(r.notification(), cache));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new BatchSendResponse(accepted, duplicates, out));
    }

    @GetMapping
    public PageResponse<NotificationResponse> list(
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) Channel channel,
            @RequestParam(required = false) String recipient,
            @RequestParam(required = false) String idempotencyKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        Page<Notification> p = service.search(tenant(),
                new NotificationService.Filter(status, channel, recipient, from, to, idempotencyKey), page, size);
        Map<UUID, String> cache = new HashMap<>();
        return new PageResponse<>(p.getContent().stream().map(n -> mapper.toResponse(n, cache)).toList(),
                p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    @GetMapping("/{id}")
    public NotificationDetailResponse get(@PathVariable UUID id) {
        Notification n = service.get(tenant(), id);
        return new NotificationDetailResponse(mapper.toResponse(n),
                service.attempts(n.getId()).stream().map(AttemptResponse::from).toList(),
                service.events(n.getId()).stream().map(EventResponse::from).toList());
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a SCHEDULED or QUEUED notification")
    public NotificationResponse cancel(@PathVariable UUID id) {
        return mapper.toResponse(service.cancel(tenant(), id));
    }

    @GetMapping("/status-counts")
    public Map<NotificationStatus, Long> statusCounts() {
        return service.statusCounts(tenant());
    }

    private static UUID tenant() {
        return CurrentPrincipal.get().requireTenantId();
    }
}
