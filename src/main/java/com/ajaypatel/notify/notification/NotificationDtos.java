package com.ajaypatel.notify.notification;

import com.ajaypatel.notify.channel.Channel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class NotificationDtos {
    private NotificationDtos() {}

    /**
     * Either {@code templateCode} + {@code variables} or raw {@code subject}/{@code body}.
     * {@code idempotencyKey} may also come from the {@code Idempotency-Key} header.
     */
    @io.swagger.v3.oas.annotations.media.Schema(description = "Provide exactly one of templateCode (+variables) or body. EMAIL needs a subject; SMS must not have one.")
    public record SendRequest(
            @NotNull Channel channel,
            @NotBlank @Size(max = 320) String recipient,
            @Size(max = 64) String templateCode,
            Map<String, Object> variables,
            @Size(max = 998) String subject,
            @Size(max = 100_000) String body,
            @Size(max = 128) String idempotencyKey,
            Instant scheduledAt,
            @Min(-10) @Max(10) Integer priority,
            Map<String, Object> metadata
    ) {}

    public record BatchSendRequest(@NotEmpty @Size(max = 1000) List<@Valid SendRequest> notifications) {}

    public record BatchSendResponse(int accepted, int duplicates, List<NotificationResponse> notifications) {}

    public record NotificationResponse(UUID id, Channel channel, String recipient, NotificationStatus status,
                                       String templateCode, Integer templateVersion, String subject, String body,
                                       String idempotencyKey, int priority, Instant scheduledAt, Instant nextAttemptAt,
                                       int attemptCount, int maxAttempts, String providerMessageId, String lastError,
                                       Map<String, Object> metadata, Instant createdAt, Instant updatedAt,
                                       Instant sentAt, Instant deliveredAt, Instant failedAt) {
    }

    public record AttemptResponse(int attemptNo, DeliveryAttempt.Outcome outcome, String provider,
                                  String providerMessageId, String errorCode, String errorMessage, String workerId,
                                  Instant startedAt, Instant finishedAt, long durationMs) {
        public static AttemptResponse from(DeliveryAttempt a) {
            return new AttemptResponse(a.getAttemptNo(), a.getOutcome(), a.getProvider(), a.getProviderMessageId(),
                    a.getErrorCode(), a.getErrorMessage(), a.getWorkerId(), a.getStartedAt(), a.getFinishedAt(),
                    a.getFinishedAt().toEpochMilli() - a.getStartedAt().toEpochMilli());
        }
    }

    public record EventResponse(NotificationEvent.Type type, NotificationStatus fromStatus, NotificationStatus toStatus,
                                String actor, String detail, Instant occurredAt) {
        public static EventResponse from(NotificationEvent e) {
            return new EventResponse(e.getEventType(), e.getFromStatus(), e.getToStatus(), e.getActor(), e.getDetail(),
                    e.getOccurredAt());
        }
    }

    public record NotificationDetailResponse(NotificationResponse notification, List<AttemptResponse> attempts,
                                             List<EventResponse> events) {}

    public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {}
}
